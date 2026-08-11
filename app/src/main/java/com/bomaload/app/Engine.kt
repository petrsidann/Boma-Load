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
    private var balanceThen: (() -> Unit)? = null

    const val DELAY_MS = 2500L          // pause between vouchers – raise to 5000 if Safaricom ever complains
    const val TIMEOUT_MS = 20000L
    const val BALANCE_TIMEOUT_MS = 12000L

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
        checkBalanceThen { next() }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        current?.let { if (it.status == "LOADING") it.status = "PENDING" }
        phase = "IDLE"; balanceThen = null
        log?.invoke("⏹ Stopped.")
        ui?.invoke()
    }

    private fun checkBalanceThen(then: () -> Unit) {
        if (!running) return
        phase = "BALANCE"
        balanceThen = then
        dial("*100#")
        handler.postDelayed({
            if (running && phase == "BALANCE") { phase = "TOPUP"; balanceThen = null; then() }
        }, BALANCE_TIMEOUT_MS)
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
        val code = if (mode == "SELF") "*141*${item.pin}#" else "*141*${item.pin}*${other0}#"
        dial(code)
        handler.postDelayed({
            if (!decided && current == item && running && phase == "TOPUP") { fail(item, "No confirmation (timeout)"); scheduleNext() }
        }, TIMEOUT_MS)
    }

    private fun scheduleNext() = handler.postDelayed({ if (running) next() }, DELAY_MS)

    private fun dial(code: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + code.replace("#", "%23")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { appCtx?.startActivity(intent) }
        catch (e: SecurityException) { log?.invoke("❌ Phone permission missing"); running = false }
    }

    fun onUssdText(text: String) {
        if (!running) return
        val t = text.lowercase()

        if (phase == "BALANCE") {
            val m = Regex("ksh\\.?\\s*([0-9][0-9,.]*)").find(t)
            if (m != null) {
                balance = m.groupValues[1]
                log?.invoke("💰 Balance: Ksh $balance")
                ui?.invoke()
                val then = balanceThen; balanceThen = null; phase = "TOPUP"
                handler.postDelayed({ then?.invoke() }, 800)
            }
            return
        }

        if (decided) return
        if (t.contains("kindly wait") || t.contains("processing")) return   // still working – wait
        if (t.contains("enter the number") || t.contains("msisdn")) {
            log?.invoke("↪ Number prompt – auto-filling $other0 and pressing SEND")
            service?.respondWithNumber(other0)
            return
        }
        val item = current ?: return
        if (!listOf("safaricom", "balance", "top up", "voucher", "pin", "used", "invalid", "success", "ksh", "error").any { t.contains(it) }) return

        when {
            listOf("already used", "already been used", "invalid pin", "invalid voucher", "not valid", "expired", "wrong pin", "error from application")
                .any { t.contains(it) } -> {
                decided = true; fail(item, short(t)); scheduleNext()
            }
            listOf("successfully", "topped up", "top up successful", "new balance", "your balance", "confirmed")
                .any { t.contains(it) } -> {
                decided = true; ok(item, short(t))
                handler.postDelayed({ if (running) checkBalanceThen { next() } }, DELAY_MS)
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
