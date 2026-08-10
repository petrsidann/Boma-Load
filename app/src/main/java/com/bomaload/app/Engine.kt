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
    var otherNumber = ""
    var running = false
    var appCtx: Context? = null
    var ui: (() -> Unit)? = null
    var log: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var current: PinItem? = null
    private var decided = false

    const val DELAY_MS = 10_000L     // ⏱ pause between vouchers (change here if needed)
    const val TIMEOUT_MS = 30_000L   // give-up time per voucher
    private const val SELF_TEMPLATE = "*141*%s#"          // your voucher card format ✅
    private const val OTHER_TEMPLATE = "*141*%s*%s#"      // ⚠ test with ONE voucher first

    fun addPin(p: String) {
        if (p.length == 16 && p != "1234567890123456" && queue.none { it.pin == p }) {
            queue.add(PinItem(p)); ui?.invoke()
        }
    }
    fun removeAt(i: Int) { if (!running && i in queue.indices) { queue.removeAt(i); ui?.invoke() } }

    fun accessibilityOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("UssdAutomationService")
    }

    fun start() {
        running = true
        log?.invoke("▶ Automation started – ${queue.count { it.status == "PENDING" }} voucher(s) in queue")
        next()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        current?.let { if (it.status == "LOADING") it.status = "PENDING" }
        log?.invoke("⏹ Stopped.")
        ui?.invoke()
    }

    private fun next() {
        val item = queue.firstOrNull { it.status == "PENDING" }
        if (item == null) {
            running = false
            log?.invoke("🏁 COMPLETE. " + summary())
            ui?.invoke(); return
        }
        current = item; decided = false
        item.status = "LOADING"; ui?.invoke()
        log?.invoke("Dialing for ••••${item.pin.takeLast(4)} …")
        dial(item)
        handler.postDelayed({
            if (!decided && current == item && running) { fail(item, "No confirmation (timeout)"); scheduleNext() }
        }, TIMEOUT_MS)
    }

    private fun dial(item: PinItem) {
        val code = if (mode == "SELF") String.format(SELF_TEMPLATE, item.pin)
                   else String.format(OTHER_TEMPLATE, item.pin, otherNumber)
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + code.replace("#", "%23")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { appCtx?.startActivity(intent) }
        catch (e: SecurityException) { log?.invoke("❌ Phone permission not granted"); running = false }
    }

    /** Called by the Accessibility service whenever any screen text changes. */
    fun onUssdText(text: String) {
        if (!running || decided) return
        val t = text.lowercase()
        if (!listOf("safaricom","balance","top up","voucher","pin","used","invalid","success","ksh","error")
                .any { t.contains(it) }) return
        val item = current ?: return
        when {
            listOf("successfully","topped up","top up successful","new balance","your balance","confirmed")
                .any { t.contains(it) } -> { decided = true; ok(item, short(t)); scheduleNext() }
            listOf("already used","already been used","invalid","not valid","failed","expired","wrong")
                .any { t.contains(it) } -> { decided = true; fail(item, short(t)); scheduleNext() }
        }
    }

    private fun short(t: String) = t.replace(Regex("\\s+"), " ").trim().take(100)
    private fun ok(item: PinItem, m: String)  { item.status = "SUCCESS"; item.note = m; log?.invoke("✅ ••••${item.pin.takeLast(4)} loaded – $m"); ui?.invoke() }
    private fun fail(item: PinItem, m: String){ item.status = "FAILED";  item.note = m; log?.invoke("❌ ••••${item.pin.takeLast(4)} – $m"); ui?.invoke() }
    private fun scheduleNext() = handler.postDelayed({ if (running) next() }, DELAY_MS)
    private fun summary(): String {
        val s = queue.count { it.status == "SUCCESS" }; val f = queue.count { it.status == "FAILED" }
        return "$s success, $f failed, of ${queue.size}"
    }
}
