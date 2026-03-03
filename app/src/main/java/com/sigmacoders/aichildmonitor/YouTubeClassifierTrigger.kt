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
        videoId: String?, 
        parentId: String,
        childId: String
    ) {
        if (title == lastClassifiedTitle) return
        lastClassifiedTitle = title

        val apiKeyProvider = ApiKeyProvider()

        // Get HuggingFace Key and classify title directly
        apiKeyProvider.getHuggingFaceKey(
            onSuccess = { hfApiKey ->
                CoroutineScope(Dispatchers.IO).launch {
                    Log.d("YT_CLASSIFIER", "Classifying title: $title")
                    
                    VideoClassifier().classify(
                        title = title,
                        channel = "YouTube",
                        parentId = parentId,
                        childId = childId,
                        apiKey = hfApiKey,
                        onComplete = { category, confidence, safety ->
                            Log.i("YT_CLASSIFIER", "RESULT → $category | $safety")
                        },
                        onError = { e ->
                            Log.e("YT_CLASSIFIER", "Classification failed", e)
                        }
                    )
                }
            },
            onFailure = { e ->
                Log.e("YT_CLASSIFIER", "Failed to fetch AI key", e)
            }
        )
    }
}
