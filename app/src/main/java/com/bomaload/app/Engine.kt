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
    data class PinItem(val pin: String, var status: String = "PENDING", var note: String = "", var target: String = "", var retries: Int = 0)
    data class Target(val number0: String, val label: String, var enabled: Boolean, var today: Int = 0, var nextAt: Long = 0L)

    val queue = mutableListOf<PinItem>()
    val review = mutableListOf<String>()
    val targets = mutableListOf<Target>()
    var turbo = false
    var running = false
    var appCtx: Context? = null
    var service: UssdAutomationService? = null
    var ui: (() -> Unit)? = null
    var log: ((String) -> Unit)? = null
    var balance = ""

    const val SAFE_GAP_MS = 2500L
    const val TURBO_GAP_MS = 250L
    const val COOLDOWN_MS = 10_000L
    const val TIMEOUT_MS = 8000L

    private val handler = Handler(Looper.getMainLooper())
    private var current: PinItem? = null
    private var decided = false
    private var phase = "IDLE"
    private var consecFails = 0
    private var successRun = 0
    private var cooldownUntil = 0L
    private val vault = mutableSetOf<String>()
    private val history = mutableListOf<String>()

    fun init(ctx: Context) {
        appCtx = ctx
        val sp = ctx.getSharedPreferences("boma", Context.MODE_PRIVATE)
        vault.addAll(sp.getStringSet("vault", emptySet()) ?: emptySet())
        history.addAll((sp.getString("history", "") ?: "").split("\n").filter { it.isNotBlank() })
        targets.clear()
        targets.add(Target("SELF", "My Number (SIM)", true))
        val saved = sp.getString("targets", "0111363967|1\n0115108066|1") ?: "0111363967|1\n0115108066|1"
        saved.split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size == 2 && p[0].length >= 10) targets.add(Target(p[0], p[0], p[1] == "1"))
        }
        turbo = sp.getBoolean("turbo", false)
        queue.clear()
        (sp.getString("queue", "") ?: "").split("\n").forEach { line ->
            val p = line.split("|")
            if (p.size >= 2 && p[0].length == 16) queue.add(PinItem(p[0], p[1]))
        }
    }

    fun save() {
        val sp = appCtx?.getSharedPreferences("boma", Context.MODE_PRIVATE) ?: return
        sp.edit()
            .putStringSet("vault", vault.toSet())
            .putString("history", history.joinToString("\n"))
            .putString("queue", queue.joinToString("\n") { "${it.pin}|${it.status}" })
            .putString("targets", targets.filter { it.number0 != "SELF" }
                .joinToString("\n") { "${it.number0}|${if (it.enabled) "1" else "0" }" })
            .putBoolean("turbo", turbo)
            .apply()
    }

    fun addPins(list: List<String>): IntArray {
        var new = 0; var used = 0
        list.forEach { p ->
            when {
                vault.contains(p) -> used++
                queue.none { it.pin == p } -> { queue.add(PinItem(p)); new++ }
            }
        }
        save(); ui?.invoke()
        return intArrayOf(new, used)
    }

    fun addPin(p: String) { addPins(listOf(p)) }
    fun removeAt(i: Int) { if (!running && i in queue.indices) { queue.removeAt(i); save(); ui?.invoke() } }
    fun promoteReview(i: Int) {
        if (i in review.indices) { queue.add(PinItem(review.removeAt(i))); save(); ui?.invoke() }
    }
    fun addTarget(num0: String) {
        if (targets.none { it.number0 == num0 }) { targets.add(Target(num0, num0, true)); save(); ui?.invoke() }
    }
    fun removeTarget(num0: String) {
        if (num0 != "SELF" && !running) {
            targets.removeAll { it.number0 == num0 }
            save(); ui?.invoke()
        }
    }

    fun accessibilityOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("UssdAutomationService")
    }

    fun start() {
        if (targets.none { it.enabled }) { log?.invoke("HALT: enable at least one target"); return }
        running = true; consecFails = 0; successRun = 0; cooldownUntil = 0L
        val n = queue.count { it.status == "PENDING" }
        log?.invoke("START: $n voucher(s), mode ${if (turbo) "TURBO" else "SAFE"}")
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
        log?.invoke("PLAN: " + assign.map { (k, v) -> "$v -> ${en.first { it.number0 == k }.label}" }
            .joinToString(", "))
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
                phase = "IDLE"
                log?.invoke("BAL: no response")
                service?.clickButton("OK")
            }
        }, TIMEOUT_MS)
    }

    private fun complete() {
        running = false; phase = "IDLE"
        log?.invoke("DONE. " + summary())
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
        val pace = if (turbo) 0L else maxOf(0L, t.nextAt - now)
        val cool = if (turbo) 0L else maxOf(0L, cooldownUntil - now)
        val wait = maxOf(pace, cool)
        if (cool > 0) log?.invoke("COOLDOWN: ${cool / 1000}s")
        handler.postDelayed({ if (running) send(item, t) }, wait)
    }

    private fun send(item: PinItem, t: Target) {
        if (!running) return
        phase = "TOPUP"
        current = item; decided = false
        item.status = "LOADING"; item.target = t.label
        t.today++; t.nextAt = System.currentTimeMillis() + if (turbo) TURBO_GAP_MS else SAFE_GAP_MS
        ui?.invoke()
        log?.invoke("DIAL ••••${item.pin.takeLast(4)} -> ${t.label}")
        val code = if (t.number0 == "SELF") "*141*${item.pin}#" else "*141*${item.pin}*${t.number0}*#"
        dial(code)
        handler.postDelayed({
            if (!decided && current == item && running && phase == "TOPUP") {
                fail(item, "No confirmation (timeout)"); goNext()
            }
        }, TIMEOUT_MS)
    }

    private fun goNext() = handler.postDelayed({ if (running) next() }, if (turbo) TURBO_GAP_MS else 800L)

    private fun dial(code: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + code.replace("#", "%23")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { appCtx?.startActivity(intent) }
        catch (e: SecurityException) { log?.invoke("FAIL: phone permission missing"); running = false }
    }

    private fun doubleOk() = handler.postDelayed({ service?.clickButton("OK") }, 250)

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
        val marker = t.contains("safaricom message") || t.contains("kindly wait") ||
                t.contains("msisdn") || t.contains("ussd") || t.contains("voucher") ||
                t.contains("top up") || t.contains("the voucher")
        if (!marker) return

        if (t.contains("enter the number") || (t.contains("msisdn") && t.contains("back"))) {
            service?.respondWithNumber(current?.target ?: ""); return
        }
        if (decided) { service?.clickButton("OK"); return }

        if (t.contains("kindly wait") || t.contains("processing")) {
            val item = current ?: return
            decided = true
            ok(item, "accepted - processing")
            service?.clickButton("OK"); doubleOk(); goNext()
            return
        }

        val item = current ?: return
        when {
            listOf("connection problem", "invalid mmi", "network error", "try again")
                .any { t.contains(it) } -> {
                if (item.retries < 1) {
                    item.retries++; decided = true; item.status = "PENDING"
                    log?.invoke("RETRY ••••${item.pin.takeLast(4)}")
                    service?.clickButton("OK")
                    handler.postDelayed({ if (running) next() }, 300)
                } else { decided = true; fail(item, short(t)); service?.clickButton("OK"); doubleOk(); goNext() }
            }
            listOf("been used", "already used", "invalid", "expired", "not valid", "wrong pin",
                "does not exist", "error from application", "failed")
                .any { t.contains(it) } -> {
                decided = true; fail(item, short(t)); service?.clickButton("OK"); doubleOk(); goNext()
            }
            listOf("successfully", "topped up", "top up successful", "new balance", "your balance",
                "confirmed", "recharge")
                .any { t.contains(it) } -> {
                decided = true; ok(item, short(t)); service?.clickButton("OK"); doubleOk(); goNext()
            }
        }
    }

    private fun ok(item: PinItem, m: String) {
        item.status = "SUCCESS"; item.note = m
        vault.add(item.pin)
        history.add("${System.currentTimeMillis()}|${item.pin}|${item.target}|SUCCESS|${m.replace("|", "/")}")
        consecFails = 0
        successRun++
        if (!turbo && successRun % 10 == 0) {
            cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
            log?.invoke("COOLDOWN: 10 loaded - 10s rest")
        }
        log?.invoke("OK ••••${item.pin.takeLast(4)} -> ${item.target} - $m")
        tick()
        save(); ui?.invoke()
    }

    private fun fail(item: PinItem, m: String) {
        item.status = "FAILED"; item.note = m
        history.add("${System.currentTimeMillis()}|${item.pin}|${item.target}|FAILED|${m.replace("|", "/")}")
        consecFails++
        log?.invoke("FAIL ••••${item.pin.takeLast(4)} -> ${item.target} - $m")
        if (consecFails >= 3) {
            log?.invoke("HALT: 3 fails in a row - rest that line")
            stop()
        }
        save(); ui?.invoke()
    }

    private fun tick() {
        try {
            (appCtx?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                ?.vibrate(VibrationEffect.createOneShot(60, 140))
        } catch (_: Exception) { }
    }

    fun historyList(): List<String> = history.reversed()
    fun clearHistory() { history.clear(); save(); }
    fun retryFailed(): Int {
        var n = 0
        history.filter { it.contains("|FAILED|") }
            .filter { listOf("timeout", "connection", "mmi", "network").any { w -> it.contains(w) } }
            .forEach { line ->
                val pin = line.split("|").getOrNull(1) ?: return@forEach
                if (pin.length == 16 && !vault.contains(pin) && queue.none { it.pin == pin }) {
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
            val v = appCtx?.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    private fun short(t: String) = t.replace(Regex("\\s+"), " ").trim().take(100)
    private fun summary(): String {
        val s = queue.count { it.status == "SUCCESS" }; val f = queue.count { it.status == "FAILED" }
        return "$s success, $f failed, of ${queue.size}"
    }
}
