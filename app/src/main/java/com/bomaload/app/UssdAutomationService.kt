package com.bomaload.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UssdAutomationService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Engine.service = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Engine.service = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return                                    // ignore our own app
        if (pkg.contains("systemui") || pkg.contains("launcher")) return  // ignore notification shade
        val dialerPkg = pkg.contains("phone") || pkg.contains("dialer") ||
                pkg.contains("telecom") || pkg.contains("caller")
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        collect(root, sb)
        val text = sb.toString()
        val ussdMarker = text.contains("USSD", true) || text.contains("Safaricom Message", true) ||
                text.contains("Kindly wait", true) || text.contains("MSISDN", true)
        if ((dialerPkg || ussdMarker) && text.length > 3) Engine.onUssdText(text)
    }

    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, sb) }
    }

    /** Types the target number into the USSD prompt and presses SEND. */
    fun respondWithNumber(number: String) {
        try {
            val root = rootInActiveWindow ?: return
            val edit = findEdit(root)
            if (edit != null) {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, number)
                edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            val sends = root.findAccessibilityNodeInfosByText("SEND")
            if (sends != null) {
                for (n in sends) {
                    var c: AccessibilityNodeInfo? = n
                    while (c != null && !c.isClickable) c = c.parent
                    if (c != null) {
                        c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        break
                    }
                }
            }
        } catch (_: Exception) { }
    }

    private fun findEdit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.toString() == "android.widget.EditText") return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findEdit(child)
            if (hit != null) return hit
        }
        return null
    }

    override fun onInterrupt() {}
}
