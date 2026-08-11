package com.bomaload.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

object Engine {
    data class PinItem(val pin: String, var status: String = "PENDING", var note: String = "")

    val queue = mutableListOf<PinItem>()
    var mode = "SELF"
    var other0 = ""
    var running = false
    var appCtx: Context? = null
    var service: UssdAutomationService? = null
    var ui: (() -> Unit)? = null
    var log: ((String) -> Unit)? = null
    var balance = ""

    private val handler = Handler(Looper.getMainLooper())
    private var current: PinItem? = null
    private var decided = false
    private var phase = "IDLE"

    const val NEXT_DELAY_MS = 800L
    const val TIMEOUT_MS = 10000L

    fun addPin(p: String) {
        if (p.length == 16 && p != "1234567890123456" && queue.none { it.pin == p }) { queue.add(PinItem(p)); ui?.invoke() }
    }
    fun removeAt(i: Int) { if (!running && i in queue.indices) { queue.removeAt(i); ui?.invoke() } }

    fun accessibilityOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("UssdAutomationService")
    }

    fun start() {
        running = true
        log?.invoke("▶ Started – ${queue.count { it.status == "PENDING" }} voucher(s)")
        next()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        current?.let { if (it.status == "LOADING") it.status = "PENDING" }
        phase = "IDLE"
        log?.invoke("⏹ Stopped.")
        ui?.invoke()
    }

    fun checkBalance() {
        phase = "BALANCE"
        log?.invoke("💰 Checking balance…")
        dial("*144#")
        handler.postDelayed({
            if (phase == "BALANCE") { phase = "IDLE"; log?.invoke("💰 Balance check timed out") }
        }, TIMEOUT_MS)
    }

    private fun next() {
        if (!running) return
        val item = queue.firstOrNull { it.status == "PENDING" }
        if (item == null) {
            running = false; phase = "IDLE"
            log?.invoke("🏁 COMPLETE. " + summary())
            ui?.invoke(); return
        }
        phase = "TOPUP"
        current = item; decided = false
        item.status = "LOADING"; ui?.invoke()
        log?.invoke("Dialing ••••${item.pin.takeLast(4)} …")
        val code = if (mode == "SELF") "*141*${item.pin}#" else "*141*${item.pin}*${other0}*#"
        dial(code)
        handler.postDelayed({
            if (!decided && current == item && running && phase == "TOPUP") {
                fail(item, "No confirmation (timeout)")
                goNext()
            }
        }, TIMEOUT_MS)
    }

    private fun goNext() = handler.postDelayed({ if (running) next() }, NEXT_DELAY_MS)

    private fun dial(code: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + code.replace("#", "%23")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { appCtx?.startActivity(intent) }
        catch (e: SecurityException) { log?.invoke("❌ Phone permission missing"); running = false }
    }

    fun onUssdText(text: String) {
        val t = text.lowercase()

        if (phase == "BALANCE") {
            val m = Regex("ksh\\.?\\s*([0-9][0-9,.]*)").find(t)
            if (m != null) {
                balance = m.groupValues[1]
                log?.invoke("💰 Balance: Ksh $balance")
                ui?.invoke()
                phase = "IDLE"
                service?.clickButton("OK")
            }
            return
        }

        if (!running) return

        if (t.contains("enter the number") || t.contains("msisdn")) {
            service?.respondWithNumber(other0)
            return
        }

        if (decided) { service?.clickButton("OK"); return }

        if (t.contains("kindly wait") || t.contains("processing")) {
            val item = current ?: return
            decided = true
            item.status = "SUCCESS"
            item.note = "accepted – processing"
            log?.invoke("✅ ••••${item.pin.takeLast(4)} – accepted (processing)")
            ui?.invoke()
            service?.clickButton("OK")
            goNext()
            return
        }

        val item = current ?: return
        when {
            listOf("been used", "already used", "invalid", "expired", "not valid", "wrong pin", "error from application", "failed")
                .any { t.contains(it) } -> {
                decided = true; fail(item, short(t)); service?.clickButton("OK"); goNext()
            }
            listOf("successfully", "topped up", "top up successful", "new balance", "your balance", "confirmed", "recharge")
                .any { t.contains(it) } -> {
                decided = true; ok(item, short(t)); service?.clickButton("OK"); goNext()
            }
        }
    }

    private fun short(t: String) = t.replace(Regex("\\s+"), " ").trim().take(100)
    private fun ok(item: PinItem, m: String) { item.status = "SUCCESS"; item.note = m; log?.invoke("✅ ••••${item.pin.takeLast(4)} – $m"); ui?.invoke() }
    private fun fail(item: PinItem, m: String) { item.status = "FAILED"; item.note = m; log?.invoke("❌ ••••${item.pin.takeLast(4)} – $m"); ui?.invoke() }
    private fun summary(): String {
        val s = queue.count { it.status == "SUCCESS" }; val f = queue.count { it.status == "FAILED" }
        return "$s success, $f failed, of ${queue.size}"
    }
}
