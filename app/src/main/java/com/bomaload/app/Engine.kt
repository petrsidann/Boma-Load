package com.bomaload.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

object Engine {
    data class PinItem(val pin: String, var status: String = "PENDING", var note: String = "", var target: String = "", var retries: Int = 0, var timeouts: Int = 0, var pend: String = "", var journey: String = "")
    data class Target(val number0: String, val label: String, var enabled: Boolean, var today: Int = 0, var nextAt: Long = 0L)
    data class AddResult(val new: Int, val used: Int, val usedPins: List<String>)

    val queue = mutableListOf<PinItem>()
    val review = mutableListOf<String>()
    val targets = mutableListOf<Target>()
    var fast = false
    var running = false
    var appCtx: Context? = null
    var service: UssdAutomationService? = null
    var ui: (() -> Unit)? = null
    var log: ((String) -> Unit)? = null
    var balance = ""
    var lastBatchSecs: Long = 0
    var lastBatchCount: Int = 0
    var downgrades: Int = 0

    const val SAFE_GAP_MS = 2500L
    const val FAST_GAP_MS = 250L
    const val COOLDOWN_MS = 10_000L
    const val TIMEOUT_MS = 10_000L
    const val MAX_CONN_RETRIES = 3
    const val MAX_SWEEPS = 2
    private fun settleMs() = if (fast) 1500L else 2000L

    fun speedName() = if (fast) "FAST" else "SAFE"
    private fun gapMs() = if (fast) FAST_GAP_MS else SAFE_GAP_MS
    private fun nextMs() = if (fast) FAST_GAP_MS else 800L
    fun estimateSec(p: Int, f: Boolean) = Ledger.estimateSec(p, f)

    private val handler = Handler(Looper.getMainLooper())
    private var current: PinItem? = null
    private var phase = "IDLE"
    private var consecFails = 0
    private var successRun = 0
    private var sweep = 0
    private var cooldownUntil = 0L
    private var dialAt = 0L
    private var graceUntil = 0L
    private var lastText = ""
    private var lastTextAt = 0L
    private var batchStartAt = 0L
    private var pendingTimeout: Runnable? = null
    private var settleRun: Runnable? = null

    private fun dayStart() = Ledger.dayStart()

    fun init(ctx: Context) {
        appCtx = ctx
        Ledger.init(ctx)
        val sp = ctx.getSharedPreferences("boma", Context.MODE_PRIVATE)
        targets.clear()
        targets.add(Target("SELF", "My Number (SIM)", true))
        val saved = sp.getString("targets", "0111363967|1\n0115108066|1") ?: "0111363967|1\n0115108066|1"
        saved.split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size == 2 && p[0].length >= 10) targets.add(Target(p[0], p[0], p[1] == "1"))
        }
        fast = sp.getBoolean("turbo", false)
        queue.clear()
        (sp.getString("queue", "") ?: "").split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size >= 2 && p[0].length == 16) queue.add(PinItem(p[0], p[1]))
        }
        val t0 = dayStart()
        targets.forEach { t ->
            t.today = Ledger.history.count { line ->
                val p = line.split("|")
                p.size >= 4 && p[3] == "SUCCESS" && (p[0].toLongOrNull() ?: 0L) >= t0 && p[2] == t.label
            }
        }
    }

    fun save() {
        val sp = appCtx?.getSharedPreferences("boma", Context.MODE_PRIVATE) ?: return
        sp.edit()
            .putString("queue", queue.joinToString("\n") { "${it.pin}|${it.status}" })
            .putString("targets", targets.filter { it.number0 != "SELF" }
                .joinToString("\n") { "${it.number0}|${if (it.enabled) "1" else "0" }" })
            .putBoolean("turbo", fast)
            .apply()
        Ledger.save()
    }

    fun inVault(p: String) = Ledger.inVault(p)
    fun vaultList() = Ledger.vaultList()
    fun vaultRemove(p: String) { Ledger.vaultRemove(p); ui?.invoke() }
    fun vaultClear() { Ledger.vaultClear(); ui?.invoke() }
    fun historyList() = Ledger.historyList()
    fun clearHistory() { Ledger.clearHistory() }
    fun todayLoaded() = Ledger.todayLoaded()
    fun todayFails() = Ledger.todayFails()
    fun todayStats() = Ledger.todayStats()

    fun clearQueue() {
        if (!running) { queue.clear(); review.clear(); save(); ui?.invoke() }
    }

    fun ghostList(): List<PinItem> = queue.filter { Ledger.inVault(it.pin) }
    fun purgeGhosts(): Int {
        val g = ghostList()
        queue.removeAll(g)
        val before = queue.size
        val seen = mutableSetOf<String>()
        queue.removeAll { !seen.add(it.pin) }
        val removed = g.size + (before - queue.size)
        save(); ui?.invoke()
        return removed
    }

    fun addPins(list: List<String>): AddResult {
        var new = 0; val usedPins = mutableListOf<String>()
        list.forEach { p ->
            when {
                Ledger.inVault(p) -> usedPins.add(p)
                queue.none { it.pin == p } -> { queue.add(PinItem(p)); new++ }
            }
        }
        save(); ui?.invoke()
        return AddResult(new, usedPins.size, usedPins)
    }

    fun addPin(p: String) { addPins(listOf(p)) }
    fun removeAt(i: Int) { if (!running && i in queue.indices) { queue.removeAt(i); save(); ui?.invoke() } }
    fun promoteReview(i: Int) {
        if (i in review.indices) {
            val rv = review.removeAt(i)
            if (queue.none { it.pin == rv }) queue.add(PinItem(rv))
            save(); ui?.invoke()
        }
    }
    fun dropReview(i: Int) {
        if (i in review.indices) { review.removeAt(i); save(); ui?.invoke() }
    }
    fun clearReview() { review.clear(); save(); ui?.invoke() }
    fun addTarget(num0: String) {
        if (targets.none { it.number0 == num0 }) { targets.add(Target(num0, num0, true)); save(); ui?.invoke() }
    }
    fun removeTarget(num0: String) {
        if (num0 != "SELF" && !running) { targets.removeAll { it.number0 == num0 }; save(); ui?.invoke() }
    }

    fun requeueUnloaded(): Int {
        var n = 0
        queue.forEach {
            if (it.status == "FAILED" && !isDefinitive(it.note)) {
                it.status = "PENDING"; it.retries = 0; it.timeouts = 0; it.pend = ""; it.journey = ""; n++
            }
        }
        review.forEach { rv -> if (queue.none { it.pin == rv }) queue.add(PinItem(rv)); n++ }
        review.clear()
        save(); ui?.invoke()
        return n
    }

    fun todaySkips(): List<String> {
        val t0 = dayStart()
        return Ledger.history.filter { line ->
            val p = line.split("|")
            p.size >= 5 && p[3] == "FAILED" && (p[0].toLongOrNull() ?: 0L) >= t0 && isRetryable(p[4])
        }.mapNotNull { it.split("|").getOrNull(1) }
    }

    private fun isDefinitive(note: String) =
        listOf("invalid", "does not exist", "expired", "wrong pin").any { note.contains(it, true) }
    private fun isRetryable(note: String) =
        listOf("timeout", "connection", "mmi", "network", "no confirmation").any { note.contains(it, true) }
    private fun isConnFail(t: String) =
        listOf("connection problem", "invalid mmi", "network error", "try again").any { t.contains(it) }

    fun accessibilityOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("UssdAutomationService")
    }

    fun start() {
        if (targets.none { it.enabled }) { log?.invoke("HALT: enable at least one target"); return }
        running = true; consecFails = 0; successRun = 0; sweep = 0; cooldownUntil = 0L
        batchStartAt = System.currentTimeMillis()
        val n = queue.count { it.status == "PENDING" }
        log?.invoke("START: $n voucher(s), mode ${speedName()}")
        logPlan(n)
        next()
    }

    private fun logPlan(count: Int) {
        val en = targets.filter { it.enabled }
        if (en.isEmpty() || count == 0) return
        val tmp = en.associate { it.number0 to it.today }.toMutableMap()
        val assign = linkedMapOf<String, Int>()
        repeat(count) {
            val pick = tmp.minByOrNull { it.value }?.key ?: return
            tmp[pick] = (tmp[pick] ?: 0) + 1
            assign[pick] = (assign[pick] ?: 0) + 1
        }
        log?.invoke("PLAN: " + assign.map { (k, v) -> "$v -> ${en.first { it.number0 == k }.label}" }.joinToString(", "))
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        current?.let { if (it.status == "LOADING") it.status = "PENDING" }
        phase = "IDLE"
        log?.invoke("STOP.")
        save(); ui?.invoke()
    }

    fun checkBalance() {
        if (running) { log?.invoke("BAL: wait - automation running"); return }
        phase = "BALANCE"
        log?.invoke("BAL: checking...")
        dial("*144#")
        handler.postDelayed({
            if (phase == "BALANCE") {
                phase = "IDLE"; log?.invoke("BAL: no response"); service?.clickButton("OK")
            }
        }, TIMEOUT_MS)
    }

    private fun complete() {
        val retryable = queue.filter { it.status == "FAILED" && isRetryable(it.note) }
        if (sweep < MAX_SWEEPS && retryable.isNotEmpty()) {
            sweep++
            retryable.forEach { it.status = "PENDING"; it.retries = 0; it.timeouts = 0; it.pend = ""; it.journey = "" }
            log?.invoke("SWEEP $sweep: healing ${retryable.size} pin(s)")
            ui?.invoke(); next(); return
        }
        running = false; phase = "IDLE"
        lastBatchSecs = (System.currentTimeMillis() - batchStartAt) / 1000
        lastBatchCount = queue.count { it.status == "SUCCESS" || it.status == "FAILED" }
        log?.invoke("DONE: $lastBatchCount pins in ${lastBatchSecs}s. " + summary())
        celebrate(); save(); ui?.invoke()
    }

    private fun next() {
        if (!running) return
        val item = queue.firstOrNull { it.status == "PENDING" }
        if (item == null) { complete(); return }
        val t = targets.filter { it.enabled }
            .minWithOrNull(compareBy<Target> { it.today }.thenBy { it.nextAt })
        if (t == null) { complete(); return }
        val now = System.currentTimeMillis()
        val pace = if (fast) 0L else maxOf(0L, t.nextAt - now)
        val cool = if (fast) 0L else maxOf(0L, cooldownUntil - now)
        val wait = maxOf(pace, cool)
        if (cool > 0) log?.invoke("COOLDOWN: ${cool / 1000}s")
        handler.postDelayed({ if (running) send(item, t) }, wait)
    }

    private fun send(item: PinItem, t: Target) {
        if (!running) return
        phase = "TOPUP"
        current = item
        item.status = "LOADING"; item.target = t.label
        item.pend = ""; item.journey = "DIAL"
        t.nextAt = System.currentTimeMillis() + gapMs()
        ui?.invoke()
        dialAt = System.currentTimeMillis()
        graceUntil = dialAt + 400L
        service?.clickButton("OK")
        log?.invoke("DIAL ••••${item.pin.takeLast(4)} -> ${t.label}")
        val code = if (t.number0 == "SELF") "*141*${item.pin}#" else "*141*${item.pin}*${t.number0}*#"
        dial(code)
        armTimeout(item)
    }

    private fun armTimeout(item: PinItem) {
        pendingTimeout?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (current == item && running && phase == "TOPUP" && item.pend == "") {
                if (item.timeouts < 1) {
                    item.timeouts++; item.journey += "+RETRY"
                    log?.invoke("RETRY ••••${item.pin.takeLast(4)} (no response)")
                    handler.postDelayed({
                        if (running) send(item, targets.firstOrNull { tg -> tg.label == item.target } ?: return@postDelayed)
                    }, 300)
                } else pendFail(item, "No confirmation (timeout)", "TIMEOUT")
            }
        }
        pendingTimeout = r
        handler.postDelayed(r, TIMEOUT_MS)
    }

    private fun startSettle(item: PinItem) {
        settleRun?.let { handler.removeCallbacks(it) }
        val r = Runnable { if (current == item && running && item.pend != "") commit(item) }
        settleRun = r
        handler.postDelayed(r, settleMs())
    }

    private fun commit(item: PinItem) {
        val ss = secsStr()
        if (item.pend == "OK") {
            item.status = "SUCCESS"
            targets.firstOrNull { it.label == item.target }?.let { it.today++ }
            Ledger.add("${System.currentTimeMillis()}|${item.pin}|${item.target}|SUCCESS|${item.journey} ($ss)")
            consecFails = 0; successRun++
            Ledger.learn(fast, secs())
            if (!fast && successRun % 10 == 0) {
                cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                log?.invoke("COOLDOWN: 10 loaded - 10s rest")
            }
            log?.invoke("OK ••••${item.pin.takeLast(4)} -> ${item.target} ${item.journey} ($ss)")
            tick()
        } else {
            item.status = "FAILED"
            Ledger.add("${System.currentTimeMillis()}|${item.pin}|${item.target}|FAILED|${item.journey} - ${item.note} ($ss)")
            consecFails++
            log?.invoke("FAIL ••••${item.pin.takeLast(4)} -> ${item.target} ${item.journey} - ${item.note} ($ss)")
            if (consecFails >= 3) { log?.invoke("HALT: 3 fails in a row - rest that line"); stop() }
        }
        save(); ui?.invoke()
        goNext()
    }

    private fun pendOk(item: PinItem, tag: String) {
        item.pend = "OK"; item.journey += "+$tag"
        item.status = "SUCCESS"
        Ledger.vaultAdd(item.pin)
        service?.clickButton("OK"); doubleOk()
        startSettle(item)
    }

    private fun pendFail(item: PinItem, note: String, tag: String) {
        item.pend = "FAIL"; item.note = note; item.journey += "+$tag"
        item.status = "FAILED"
        service?.clickButton("OK"); doubleOk()
        startSettle(item)
    }

    private fun downgrade(item: PinItem) {
        downgrades++
        Ledger.vault.remove(item.pin)
        settleRun?.let { handler.removeCallbacks(it) }
        item.pend = ""; item.status = "PENDING"; item.retries++
        item.journey += "+CONN"
        log?.invoke("WARN ••••${item.pin.takeLast(4)} connection after accept - retry ${item.retries}/$MAX_CONN_RETRIES")
        Ledger.save(); save(); ui?.invoke()
        service?.clickButton("OK")
        if (item.retries <= MAX_CONN_RETRIES) {
            handler.postDelayed({
                if (running) send(item, targets.firstOrNull { tg -> tg.label == item.target } ?: return@postDelayed)
            }, 1000)
        } else pendFail(item, "connection problem (retries exhausted)", "DEAD")
    }

    private fun goNext() = handler.postDelayed({ if (running) next() }, nextMs())

    private fun dial(code: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + code.replace("#", "%23")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { appCtx?.startActivity(intent) }
        catch (e: SecurityException) { log?.invoke("FAIL: phone permission missing"); running = false }
    }

    private fun doubleOk() = handler.postDelayed({ service?.clickButton("OK") }, 250)
    private fun secs() = (System.currentTimeMillis() - dialAt) / 1000.0
    private fun secsStr() = String.format("%.1fs", secs())

    fun onUssdText(text: String) {
        val t = text.lowercase()
        if (phase == "BALANCE") {
            val m = Regex("bal[^0-9]{0,6}([0-9][0-9,.]{2,})").find(t)
                ?: Regex("([0-9][0-9,.]{2,})\\s*ksh").find(t)
            if (m != null) {
                balance = m.groupValues[1]
                log?.invoke("BAL: Ksh $balance")
                ui?.invoke(); phase = "IDLE"
                service?.clickButton("OK"); doubleOk()
            }
            return
        }
        if (!running) return
        if (System.currentTimeMillis() < graceUntil) return
        val now = System.currentTimeMillis()
        if (t == lastText && now - lastTextAt < 1200) return
        lastText = t; lastTextAt = now
        val marker = t.contains("safaricom message") || t.contains("kindly wait") ||
                t.contains("msisdn") || t.contains("ussd") || t.contains("voucher") ||
                t.contains("top up") || t.contains("the voucher")
        if (!marker) return
        val item = current ?: return
        if (t.contains("enter the number") || (t.contains("msisdn") && t.contains("back"))) {
            service?.respondWithNumber(item.target); return
        }
        val good = t.contains("kindly wait") || t.contains("processing") ||
                listOf("successfully", "topped up", "top up successful", "new balance", "your balance", "confirmed", "recharge").any { t.contains(it) }
        val used = t.contains("been used") || t.contains("already used")
        val dead = listOf("invalid", "expired", "not valid", "wrong pin", "does not exist", "error from application").any { t.contains(it) }
        when {
            isConnFail(t) -> {
                if (item.pend == "OK") downgrade(item)
                else if (item.pend == "") {
                    if (item.retries < MAX_CONN_RETRIES) {
                        item.retries++; item.journey += "+CONN"
                        log?.invoke("RETRY ••••${item.pin.takeLast(4)} (network ${item.retries}/$MAX_CONN_RETRIES)")
                        service?.clickButton("OK")
                        handler.postDelayed({
                            if (running) send(item, targets.firstOrNull { tg -> tg.label == item.target } ?: return@postDelayed)
                        }, 1000)
                    } else pendFail(item, short(t), "CONN")
                } else service?.clickButton("OK")
            }
            good || used -> {
                if (item.pend == "") pendOk(item, if (used) "USED" else "WAIT")
                else if (item.pend == "FAIL") {
                    Ledger.vaultAdd(item.pin)
                    item.pend = "OK"; item.status = "SUCCESS"; item.journey += "+${if (used) "USED" else "WAIT"}"
                    startSettle(item)
                } else service?.clickButton("OK")
            }
            dead -> {
                if (item.pend == "") pendFail(item, short(t), "DEAD")
                else service?.clickButton("OK")
            }
            else -> service?.clickButton("OK")
        }
    }

    private fun tick() {
        try {
            (appCtx?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?.vibrate(VibrationEffect.createOneShot(60, 140))
        } catch (_: Exception) { }
    }

    fun retryFailed(): Int {
        var n = 0
        Ledger.history.filter { it.contains("|FAILED|") && !isDefinitive(it.substringAfter("|FAILED|", "")) }
            .forEach { line ->
                val pin = line.split("|").getOrNull(1) ?: return@forEach
                if (pin.length == 16 && !Ledger.inVault(pin) && queue.none { it.pin == pin }) {
                    queue.add(PinItem(pin)); n++
                }
            }
        save(); ui?.invoke()
        return n
    }

    private fun celebrate() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tg.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 700)
        } catch (_: Exception) { }
        try {
            (appCtx?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    private fun short(t: String) = t.replace(Regex("\\s+"), " ").trim().take(100)
    private fun summary(): String {
        val s = queue.count { it.status == "SUCCESS" }; val f = queue.count { it.status == "FAILED" }
        return "$s success, $f failed, of ${queue.size}"
    }
}
