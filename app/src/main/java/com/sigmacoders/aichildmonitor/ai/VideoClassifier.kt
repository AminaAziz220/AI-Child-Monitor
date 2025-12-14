package com.sigmacoders.aichildmonitor.ai

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
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
            "harmful",
            "safe"
        )

        // Create the JSON payload for the API request
        val json = JSONObject().apply {
            put("inputs", text)
            put("parameters", JSONObject().apply {
                put("candidate_labels", candidateLabels)
            })
            // Add an option to wait for the model to be ready
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
                // Network failure
                onError(e)
            }

            override fun onResponse(call: Call, response: Response) {

                val body = response.body?.string() ?: ""   // ✅ declare OUTSIDE try

                try {
                    Log.d("HF_RAW", body)

                    val jsonArray = JSONArray(body)

                    val topResult = jsonArray.getJSONObject(0)
                    val rawLabel = topResult.getString("label")
                    val confidence = topResult.getDouble("score")

                    val category = rawLabel
                        .replace("[", "")
                        .replace("]", "")
                        .trim()

                    val safety = when (category.lowercase()) {
                        "violent", "adult", "harmful" -> "unsafe"
                        else -> "safe"
                    }

                    val data = hashMapOf(
                        "title" to title,
                        "channel" to channel,
                        "category" to category,
                        "confidence" to confidence,
                        "safety" to safety,
                        "timestamp" to System.currentTimeMillis()
                    )

                    db.collection("users")
                        .document(parentId)
                        .collection("children")
                        .document(childId)
                        .collection("videoLogs")
                        .add(data)

                    onComplete(category, confidence, safety)

                } catch (e: Exception) {
                    onError(Exception("Failed to parse JSON. Response was: $body", e))
                }
            }


        })
    }

    private fun saveResultToFirestore(
        parentId: String,
        childId: String,
        title: String,
        channel: String,
        category: String,
        confidence: Double,
        safety: String
    ) {
        val data = hashMapOf(
            "title" to title,
            "channel" to channel,
            "category" to category,
            "confidence" to confidence,
            "safety" to safety,
            "timestamp" to System.currentTimeMillis()
        )

        db.collection("users")
            .document(parentId)
            .collection("children")
            .document(childId)
            .collection("videoLogs")
            .add(data)
            .addOnSuccessListener {
                Log.d("FIRESTORE_SUCCESS", "Video log saved successfully.")
            }
            .addOnFailureListener { e ->
                Log.e("FIRESTORE_ERROR", "Error saving video log", e)
                // You might want to handle this failure case, perhaps with another callback
            }
    }
}