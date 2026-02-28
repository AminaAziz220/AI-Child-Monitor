package com.sigmacoders.aichildmonitor

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sigmacoders.aichildmonitor.YouTubeClassifierTrigger


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
        var videoId: String? = null


        if (watchPanelNodes.isNotEmpty()) {
            // Search inside the watch_panel for the first piece of text.
            title = findFirstTextInNode(watchPanelNodes[0])
            videoId = extractVideoId(rootNode.text?.toString() ?: "")

        }

        // --- IMPORTANT: Recycle the nodes obtained from the system ---
        watchPanelNodes.forEach { it.recycle() }
        rootNode.recycle()

        // Check if we found a new, valid title that's not just a timestamp
        if (
            !title.isNullOrBlank() &&
            title != lastDetectedTitle &&
            !isTimestamp(title.toString()) &&
            isValidVideoTitle(title.toString())
        ) {

            lastDetectedTitle = title
            Log.d(TAG, "----------------------------------------------------")
            Log.d(TAG, "VIDEO TITLE DETECTED: $title")
            Log.d(TAG, "----------------------------------------------------")
            // In the future, you will call your VideoClassifier from here.
            // Trigger AI classification
            YouTubeClassifierTrigger.classifyIfNeeded(
                context = this,
                title = title.toString(),
                videoId = videoId,
                parentId = "PARENT_ID_HERE",
                childId = "CHILD_ID_HERE"
            )


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

    private fun extractVideoId(text: String): String? {
        val regex = Regex("v=([a-zA-Z0-9_-]{11})")
        return regex.find(text)?.groupValues?.get(1)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "YouTube Accessibility Service Connected")
    }

    private fun isValidVideoTitle(text: String): Boolean {

        val trimmed = text.trim().lowercase()

        // Ignore very short text
        if (trimmed.length < 8) return false

        // Block common YouTube UI labels
        val blockedWords = listOf(
            "live",
            "comments",
            "shorts",
            "home",
            "library",
            "subscriptions",
            "trending",
            "search",
            "share",
            "like",
            "dislike"
        )

        if (blockedWords.contains(trimmed)) return false

        // Ignore single-word titles (often UI elements)
        if (!trimmed.contains(" ")) return false

        return true
    }

}
