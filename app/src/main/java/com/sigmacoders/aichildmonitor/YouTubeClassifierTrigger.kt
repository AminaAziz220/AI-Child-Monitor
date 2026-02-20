package com.sigmacoders.aichildmonitor

import android.content.Context
import android.util.Log
import com.sigmacoders.aichildmonitor.ai.VideoClassifier
import com.sigmacoders.aichildmonitor.ai.YouTubeCommentsFetcher
import com.sigmacoders.aichildmonitor.ai.YouTubeSearchFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object YouTubeClassifierTrigger {

    private var lastClassifiedTitle: String? = null

    fun classifyIfNeeded(
        context: Context,
        title: String,
        videoId: String?, // kept for compatibility
        parentId: String,
        childId: String
    ) {

        if (title == lastClassifiedTitle) return
        lastClassifiedTitle = title

        val apiKeyProvider = ApiKeyProvider()

        // 🔵 STEP 1: Get HuggingFace Key
        apiKeyProvider.getHuggingFaceKey(
            onSuccess = { hfApiKey ->

                CoroutineScope(Dispatchers.IO).launch {

                    Log.d("YT_CLASSIFIER", "Classifying video: $title")

                    val classifier = VideoClassifier()

                    // 🔵 STEP 2: Get YouTube Key from Firestore
                    apiKeyProvider.getYouTubeKey(
                        onSuccess = { youtubeApiKey ->

                            // 🔵 STEP 3: Search videoId using title
                            YouTubeSearchFetcher().searchVideoIdByTitle(
                                title = title,
                                apiKey = youtubeApiKey,
                                onSuccess = { foundVideoId ->

                                    if (foundVideoId == null) {
                                        Log.w("YT_SEARCH", "No videoId found. Fallback.")

                                        classifyTitleOnly(
                                            classifier,
                                            title,
                                            parentId,
                                            childId,
                                            hfApiKey
                                        )
                                        return@searchVideoIdByTitle
                                    }

                                    Log.d("YT_SEARCH", "Found videoId: $foundVideoId")

                                    // 🔵 STEP 4: Fetch comments
                                    YouTubeCommentsFetcher().fetchTopComments(
                                        videoId = foundVideoId,
                                        apiKey = youtubeApiKey,
                                        onSuccess = { comments ->

                                            Log.d("YT_COMMENTS", "Fetched ${comments.size} comments")

                                            val combinedText = buildString {
                                                append(title)
                                                if (comments.isNotEmpty()) {
                                                    append(". User comments: ")
                                                    comments.take(5).forEach {
                                                        append(it).append(" ")
                                                    }
                                                }
                                            }

                                            classifier.classify(
                                                title = combinedText,
                                                channel = "Unknown",
                                                parentId = parentId,
                                                childId = childId,
                                                apiKey = hfApiKey,
                                                onComplete = { category, confidence, safety ->
                                                    Log.i(
                                                        "YT_CLASSIFIER",
                                                        "RESULT (with comments) → $category | $safety | $confidence"
                                                    )
                                                },
                                                onError = { e ->
                                                    Log.e("YT_CLASSIFIER", "Classification failed", e)
                                                }
                                            )
                                        },
                                        onFailure = {
                                            Log.w("YT_COMMENTS", "Comments failed. Fallback.")
                                            classifyTitleOnly(
                                                classifier,
                                                title,
                                                parentId,
                                                childId,
                                                hfApiKey
                                            )
                                        }
                                    )
                                },
                                onFailure = {
                                    Log.w("YT_SEARCH", "Search failed. Fallback.")
                                    classifyTitleOnly(
                                        classifier,
                                        title,
                                        parentId,
                                        childId,
                                        hfApiKey
                                    )
                                }
                            )
                        },
                        onFailure = { e ->
                            Log.e("YT_KEY", "Failed to fetch YouTube key", e)

                            // If YouTube key fails, still classify title
                            classifier.classify(
                                title = title,
                                channel = "Unknown",
                                parentId = parentId,
                                childId = childId,
                                apiKey = hfApiKey,
                                onComplete = { category, confidence, safety ->
                                    Log.i(
                                        "YT_CLASSIFIER",
                                        "RESULT (no YT key) → $category | $safety | $confidence"
                                    )
                                },
                                onError = { err ->
                                    Log.e("YT_CLASSIFIER", "Classification failed", err)
                                }
                            )
                        }
                    )
                }
            },
            onFailure = { e ->
                Log.e("YT_CLASSIFIER", "Failed to fetch HuggingFace key", e)
            }
        )
    }

    // 🔵 Helper for fallback classification
    private fun classifyTitleOnly(
        classifier: VideoClassifier,
        title: String,
        parentId: String,
        childId: String,
        hfApiKey: String
    ) {
        classifier.classify(
            title = title,
            channel = "Unknown",
            parentId = parentId,
            childId = childId,
            apiKey = hfApiKey,
            onComplete = { category, confidence, safety ->
                Log.i(
                    "YT_CLASSIFIER",
                    "RESULT (title-only fallback) → $category | $safety | $confidence"
                )
            },
            onError = { e ->
                Log.e("YT_CLASSIFIER", "Classification failed", e)
            }
        )
    }
}
