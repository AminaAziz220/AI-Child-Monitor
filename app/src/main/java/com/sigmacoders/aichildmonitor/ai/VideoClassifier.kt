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

    fun classify(
        title: String,
        channel: String,
        parentId: String,
        childId: String,
        apiKey: String,
        onComplete: (String, Double, String) -> Unit,
        onError: (Exception) -> Unit
    ) {

        val text = "$title. Channel: $channel"

        val candidateLabels = listOf(
            "educational",
            "entertainment",
            "gaming",
            "violent",
            "adult",
            "harmful"
        )

        val json = JSONObject().apply {
            put("inputs", text)
            put("parameters", JSONObject().apply {
                put("candidate_labels", candidateLabels)
            })
            put("options", JSONObject().apply {
                put("wait_for_model", true)
            })
        }

        val requestBody = json.toString()
            .toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("https://router.huggingface.co/hf-inference/models/facebook/bart-large-mnli")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                onError(e)
            }

            override fun onResponse(call: Call, response: Response) {

                val body = response.body?.string() ?: ""

                try {
                    Log.d("HF_RAW", body)

                    val jsonArray = JSONArray(body)

                    var topCategory = ""
                    var topConfidence = 0.0

                    var adultScore = 0.0
                    var harmfulScore = 0.0
                    var violentScore = 0.0

                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)

                        val label = item.getString("label")
                            .replace("[", "")
                            .replace("]", "")
                            .lowercase()
                            .trim()

                        val score = item.getDouble("score")

                        // First item = highest confidence
                        if (i == 0) {
                            topCategory = label
                            topConfidence = score
                        }

                        when (label) {
                            "adult" -> adultScore = score
                            "harmful" -> harmfulScore = score
                            "violent" -> violentScore = score
                        }
                    }

                    val safety = if (
                        adultScore > 0.40 ||
                        harmfulScore > 0.35 ||
                        violentScore > 0.35
                    ) {
                        "unsafe"
                    } else {
                        "safe"
                    }

                    Log.i(
                        "CLASSIFICATION_RESULT",
                        "Top=$topCategory ($topConfidence) | adult=$adultScore | harmful=$harmfulScore | violent=$violentScore | safety=$safety"
                    )

                    val data = hashMapOf(
                        "title" to title,
                        "channel" to channel,
                        "category" to topCategory,
                        "confidence" to topConfidence,
                        "adultScore" to adultScore,
                        "harmfulScore" to harmfulScore,
                        "violentScore" to violentScore,
                        "safety" to safety,
                        "timestamp" to System.currentTimeMillis()
                    )

                    db.collection("users")
                        .document(parentId)
                        .collection("children")
                        .document(childId)
                        .collection("videoLogs")
                        .add(data)

                    onComplete(topCategory, topConfidence, safety)

                } catch (e: Exception) {
                    onError(Exception("Failed to parse JSON. Response was: $body", e))
                }
            }
        })
    }
}
