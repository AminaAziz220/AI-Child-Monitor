package com.sigmacoders.aichildmonitor

import android.content.Context
import android.util.Log
import com.sigmacoders.aichildmonitor.ai.VideoClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object YouTubeClassifierTrigger {

    private var lastClassifiedTitle: String? = null

    fun classifyIfNeeded(
        context: Context,
        title: String,
        channel: String = "Unknown",
        parentId: String,
        childId: String,
        apiKey: String
    ) {
        if (title == lastClassifiedTitle) return
        lastClassifiedTitle = title

        CoroutineScope(Dispatchers.IO).launch {
            Log.d("YT_CLASSIFIER", "Classifying video: $title")

            VideoClassifier().classify(
                title = title,
                channel = channel,
                parentId = parentId,
                childId = childId,
                apiKey = apiKey,
                onComplete = { category, confidence, safety ->
                    Log.i(
                        "YT_CLASSIFIER",
                        "RESULT → $category | $safety | $confidence"
                    )
                },
                onError = { e ->
                    Log.e("YT_CLASSIFIER", "Classification failed", e)
                }
            )
        }
    }
}
