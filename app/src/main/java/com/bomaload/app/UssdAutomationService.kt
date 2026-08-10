package com.bomaload.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class UssdAutomationService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (event.packageName?.toString() == packageName) return
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        collect(root, sb)
        if (sb.length > 3) Engine.onUssdText(sb.toString())
    }
    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder) {
        node.text?.let { sb.append(it).append(' ') }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, sb) }
    }
    override fun onInterrupt() {}
}
