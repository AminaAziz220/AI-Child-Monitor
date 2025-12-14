package com.sigmacoders.aichildmonitor.ai

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class VideoClassifier {

    private val client = OkHttpClient()
    private val db = FirebaseFirestore.getInstance()

    private val candidateLabels = listOf(
        "educational",
        "entertainment",
        "gaming",
        "violent",
        "adult",
        "harmful",
        "safe"
    )

    fun classify(
        title: String,
        channel: String,
        parentId: String,
        childId: String,
        apiKey: String,
        onComplete: (String, Double, String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val text = "$title Channel: $channel"

        val payload = JSONObject().apply {
            put("inputs", text)
            put("parameters", JSONObject().apply {
                put("candidate_labels", JSONArray(candidateLabels))
            })
        }

        val body = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://router.huggingface.co/hf-inference/models/facebook/bart-large-mnli")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val raw = response.body?.string() ?: ""

                    Log.d("HF_RAW", raw)

                    // Handle model loading or rate limits
                    if (raw.contains("loading", ignoreCase = true)) {
                        onError(Exception("Model is loading. Try again in a few seconds."))
                        return
                    }

                    // Convert raw JSON to array of label-score pairs
                    val arr = JSONArray(raw)

                    val topLabel = arr.getJSONObject(0).getString("label")
                    val topScore = arr.getJSONObject(0).getDouble("score")

                    val cleanLabel = topLabel.replace("[", "").replace("]", "")

                    val safety = when (cleanLabel.lowercase()) {
                        "violent", "adult", "harmful" -> "unsafe"
                        else -> "safe"
                    }

                    val data = hashMapOf(
                        "title" to title,
                        "channel" to channel,
                        "category" to cleanLabel,
                        "confidence" to topScore,
                        "safety" to safety,
                        "timestamp" to System.currentTimeMillis()
                    )

                    // Save classification result
                    db.collection("users")
                        .document(parentId)
                        .collection("children")
                        .document(childId)
                        .collection("videoLogs")
                        .add(data)

                    onComplete(cleanLabel, topScore, safety)

                } catch (e: Exception) {
                    onError(e)
                }
            }
        })
    }
}
