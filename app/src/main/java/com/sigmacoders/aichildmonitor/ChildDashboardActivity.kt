package com.sigmacoders.aichildmonitor

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.sigmacoders.aichildmonitor.databinding.ActivityChildDashboardBinding
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class ChildDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildDashboardBinding
    private val tag = "ChildDashboardActivity"
    private var childId: String? = null
    private var parentId: String? = null
    private val client = OkHttpClient()
    private var childGender: String = "Boy" // Variable to store gender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        childId = intent.getStringExtra("CHILD_ID")
        parentId = intent.getStringExtra("PARENT_ID")

        if (parentId == null || childId == null) {
            Log.e(tag, "Parent ID or Child ID is null.")
            finish()
            return
        }

        setupFirestoreListener(parentId!!, childId!!)
    }

    private fun setupFirestoreListener(parentId: String, childId: String) {
        val db = Firebase.firestore
        val childRef =
            db.collection("users").document(parentId)
                .collection("children").document(childId)

        var currentJournalText = getString(R.string.no_journal_entry)

        childRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(tag, "Listen failed.", error)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val childName = snapshot.getString("name") ?: "Child"
                val riskLevelString = snapshot.getString("riskLevel") ?: "Unknown"
                childGender = snapshot.getString("gender") ?: "Boy" // Get and store the gender
                currentJournalText =
                    snapshot.getString("journalText")
                        ?: getString(R.string.no_journal_entry)

                binding.childNameTextView.text = childName
                binding.riskLevelValue.text = getString(R.string.risk_level, riskLevelString)

                // Set initial image based on gender and risk level from Firestore
                updateAvatar(riskLevelString, childGender)

                displayUsageDataFromSnapshot(snapshot)
            }
        }

        binding.journalButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.todays_journal_entry))
                .setMessage(currentJournalText)
                .setPositiveButton(getString(R.string.close), null)
                .show()
        }
    }

    private fun updateAvatar(riskLevel: String, gender: String) {
        if (gender == "Girl") {
            when (riskLevel.lowercase()) {
                "low" -> binding.emotionalAvatar.setImageResource(R.drawable.girl_smile)
                "medium" -> binding.emotionalAvatar.setImageResource(R.drawable.girl_mid)
                "high" -> binding.emotionalAvatar.setImageResource(R.drawable.girl_sad)
            }
        } else { // Default to Boy
            when (riskLevel.lowercase()) {
                "low" -> binding.emotionalAvatar.setImageResource(R.drawable.boy_smile)
                "medium" -> binding.emotionalAvatar.setImageResource(R.drawable.boy_mid)
                "high" -> binding.emotionalAvatar.setImageResource(R.drawable.boy_sad)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun displayUsageDataFromSnapshot(snapshot: com.google.firebase.firestore.DocumentSnapshot) {

        val usageData = snapshot.get("usage") as? Map<String, Any> ?: return

        val totalMinutes = (usageData["totalMinutes"] as? Long) ?: 0L
        val topApps =
            (usageData["topApps"] as? List<Map<String, Any>>) ?: emptyList()

        var socialMinutes = 0L
        var gamingMinutes = 0L

        // ===== TOTAL TIME DISPLAY =====
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        binding.totalTimeTextView.text =
            getString(R.string.total_today, hours, minutes)

        // ===== BAR CHART =====
        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        topApps.forEachIndexed { index, app ->
            val appName = app["appName"] as? String ?: "Unknown"
            val usageMinutesLong = (app["usageMinutes"] as? Long) ?: 0L
            val category = (app["category"] as? Long)?.toInt() ?: -1 // Get the category

            entries.add(BarEntry(index.toFloat(), usageMinutesLong.toFloat()))
            labels.add(appName)

            // Sum minutes based on the official app category
            when (category) {
                ApplicationInfo.CATEGORY_GAME -> gamingMinutes += usageMinutesLong
                ApplicationInfo.CATEGORY_SOCIAL -> socialMinutes += usageMinutesLong
            }
        }

        if (entries.isNotEmpty()) {
            setupBarChart(entries, labels)
        } else {
            binding.appUsageBarChart.data = null
            binding.appUsageBarChart.invalidate()
        }

        // ===== FEATURE ENGINEERING =====
        val totalHours = totalMinutes / 60.0
        val socialHours = socialMinutes / 60.0
        val gamingHours = gamingMinutes / 60.0

        val entertainmentRatio =
            if (totalHours > 0)
                (socialHours + gamingHours) / totalHours
            else 0.0

        val gamingRatio =
            if (totalHours > 0)
                gamingHours / totalHours
            else 0.0

        val socialRatio =
            if (totalHours > 0)
                socialHours / totalHours
            else 0.0

        val engagementIntensity = totalHours * 50   // temporary proxy

        sendRiskRequest(
            totalHours,
            socialHours,
            gamingHours,
            entertainmentRatio,
            gamingRatio,
            socialRatio,
            engagementIntensity
        )
    }

    private fun sendRiskRequest(
        totalHours: Double,
        socialHours: Double,
        gamingHours: Double,
        entertainmentRatio: Double,
        gamingRatio: Double,
        socialRatio: Double,
        engagementIntensity: Double
    ) {

        val json = JSONObject()
        json.put("avg_screen_time", totalHours)
        json.put("social_media_hours", socialHours)
        json.put("gaming_hours", gamingHours)
        json.put("night_usage", 2) // Placeholder
        json.put("phone_checks_per_day", 50) // Placeholder
        json.put("Age", 12) // Placeholder
        json.put("entertainment_ratio", entertainmentRatio)
        json.put("night_usage_ratio", 0.28) // Placeholder
        json.put("engagement_intensity", engagementIntensity)
        json.put("gaming_ratio", gamingRatio)
        json.put("social_ratio", socialRatio)

        val body = json.toString()
            .toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url("http://192.168.1.78:5000/predict")
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                Log.e("API_ERROR", "OkHttp onFailure: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(applicationContext, "API Failure: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()

                if (response.isSuccessful && responseBody != null) {
                    Log.d("API_RESPONSE", responseBody)
                    val jsonResponse = JSONObject(responseBody)
                    val riskLevelInt = jsonResponse.optInt("risk_level", -1)

                    val riskLevelString = when(riskLevelInt) {
                        0 -> "Low"
                        1 -> "Medium"
                        2 -> "High"
                        else -> "Unknown"
                    }

                    if(parentId != null && childId != null) {
                        Firebase.firestore.collection("users").document(parentId!!).collection("children").document(childId!!)
                            .update("riskLevel", riskLevelString)
                            .addOnSuccessListener { Log.d(tag, "Successfully updated risk level in Firestore.") }
                            .addOnFailureListener { e -> Log.w(tag, "Failed to update risk level in Firestore.", e) }
                    }

                    runOnUiThread {
                        binding.riskLevelValue.text = getString(R.string.risk_level, riskLevelString)
                        // Update the UI with the new data from the API, using the stored gender
                        updateAvatar(riskLevelString, childGender)
                    }
                } else {
                    Log.e("API_ERROR", "Unsuccessful response: ${response.code} ${response.message}\nBody: $responseBody")
                    runOnUiThread {
                         Toast.makeText(applicationContext, "API Error: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    private fun setupBarChart(
        entries: ArrayList<BarEntry>,
        labels: ArrayList<String>
    ) {

        val dataSet =
            BarDataSet(entries, getString(R.string.app_usage_in_minutes))
        dataSet.color = "#673AB7".toColorInt()

        val barData = BarData(dataSet)
        barData.barWidth = 0.5f

        binding.appUsageBarChart.data = barData
        binding.appUsageBarChart.setFitBars(true)
        binding.appUsageBarChart.description.isEnabled = false
        binding.appUsageBarChart.legend.isEnabled = false

        val xAxis = binding.appUsageBarChart.xAxis
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f
        xAxis.labelRotationAngle = -45f

        binding.appUsageBarChart.axisLeft.axisMinimum = 0f
        binding.appUsageBarChart.axisRight.isEnabled = false

        binding.appUsageBarChart.invalidate()
    }
}