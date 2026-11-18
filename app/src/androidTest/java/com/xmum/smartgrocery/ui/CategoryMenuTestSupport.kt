package com.xmum.smartgrocery.ui

import android.app.Instrumentation
import android.view.accessibility.AccessibilityNodeInfo

/** Exercise bounded dropdown scrolling so the final options work on smaller screens too. */
internal object CategoryMenuTestSupport {
    fun reveal(instrumentation: Instrumentation, label: String) {
        fun find(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (matches(node)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
                return null
            }
            return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        }
        fun visible() = find { it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label) } != null
        if (visible()) return
        for (direction in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            for (attempt in 0 until 8) {
                val scroll = find { it.isScrollable } ?: break
                if (!scroll.performAction(direction)) break
                instrumentation.waitForIdleSync()
                // Accessibility scroll actions animate; wait for the viewport to settle before reversing direction.
                Thread.sleep(450)
                if (visible()) return
            }
        }
    }
}
