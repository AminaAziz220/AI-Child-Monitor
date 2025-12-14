package com.sigmacoders.aichildmonitor

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class YouTubeAccessibilityService : AccessibilityService() {

    private val TAG = "YT_MONITOR"
    private var lastDetectedTitle: CharSequence? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We only care about window content changes on the YouTube app
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (event.packageName != "com.google.android.youtube") return

        val rootNode = rootInActiveWindow ?: return

        // From your logs, we found a stable container ID for video details.
        val watchPanelNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/watch_panel")

        var title: CharSequence? = null

        if (watchPanelNodes.isNotEmpty()) {
            // Search inside the watch_panel for the first piece of text.
            title = findFirstTextInNode(watchPanelNodes[0])
        }

        // --- IMPORTANT: Recycle the nodes obtained from the system ---
        watchPanelNodes.forEach { it.recycle() }
        rootNode.recycle()

        // Check if we found a new, valid title that's not just a timestamp
        if (!title.isNullOrBlank() && title != lastDetectedTitle && !isTimestamp(title.toString())) {
            lastDetectedTitle = title
            Log.d(TAG, "----------------------------------------------------")
            Log.d(TAG, "VIDEO TITLE DETECTED: $title")
            Log.d(TAG, "----------------------------------------------------")
            // In the future, you will call your VideoClassifier from here.
        }
    }

    /**
     * Performs a recursive search within a given node to find the first non-empty text.
     */
    private fun findFirstTextInNode(nodeInfo: AccessibilityNodeInfo): CharSequence? {
        if (!nodeInfo.text.isNullOrBlank()) {
            return nodeInfo.text
        }

        for (i in 0 until nodeInfo.childCount) {
            val child = nodeInfo.getChild(i)
            if (child != null) {
                val textFromChild = findFirstTextInNode(child)
                if (textFromChild != null) {
                    return textFromChild
                }
            }
        }
        return null
    }

    /**
     * A simple heuristic to avoid logging timestamps as titles.
     */
    private fun isTimestamp(text: String): Boolean {
        // Example: "0:00 / 12:34" or "1:23"
        return text.matches(Regex("^\\d{1,2}:\\d{2}.*"))
    }


    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "YouTube Accessibility Service Connected")
    }
}
