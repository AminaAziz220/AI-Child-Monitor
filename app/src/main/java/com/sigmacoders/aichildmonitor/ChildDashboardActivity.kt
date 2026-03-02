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
import java.text.SimpleDateFormat
import java.util.*

class ChildDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildDashboardBinding
    private val tag = "ChildDashboardActivity"
    private var childId: String? = null
    private var parentId: String? = null
    private val client = OkHttpClient()
    private var childGender: String = "Boy"
    private var childAge: Int = 12
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    // Current navigation state (0 = Today, 1 = Yesterday, etc.)
    private var currentDaysOffset = 0
    private var cachedUsageByDate: Map<String, Any>? = null
    private var currentJournalText = ""

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

        setupNavigation()
        setupFirestoreListener(parentId!!, childId!!)
    }

    private fun setupNavigation() {
        updateDateDisplay()

        binding.btnPrevDay.setOnClickListener {
            if (currentDaysOffset < 6) { // Limit to 7 days (0 to 6)
                currentDaysOffset++
                updateDateDisplay()
                refreshData()
            } else {
                Toast.makeText(this, "Only past 7 days available", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnNextDay.setOnClickListener {
            if (currentDaysOffset > 0) {
                currentDaysOffset--
                updateDateDisplay()
                refreshData()
            }
        }
    }

    private fun updateDateDisplay() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -currentDaysOffset)
        val dateKey = dateFormat.format(calendar.time)

        binding.usageDateLabel.text = when (currentDaysOffset) {
            0 -> "Today"
            1 -> "Yesterday"
            else -> dateKey
        }

        // Disable "Next" button if we are already at today
        binding.btnNextDay.isEnabled = currentDaysOffset > 0
        binding.btnNextDay.alpha = if (currentDaysOffset > 0) 1.0f else 0.3f
    }

    private fun refreshData() {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -currentDaysOffset)
        val dateKey = dateFormat.format(calendar.time)
        
        cachedUsageByDate?.let { 
            renderUsageForDate(it, dateKey) 
        }
    }

    private fun setupFirestoreListener(parentId: String, childId: String) {
        val db = Firebase.firestore
        val childRef = db.collection("users").document(parentId).collection("children").document(childId)

        childRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(tag, "Listen failed.", error)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val childName = snapshot.getString("name") ?: "Child"
                val riskLevelString = snapshot.getString("riskLevel") ?: "Low"
                childGender = snapshot.getString("gender") ?: "Boy"
                childAge = snapshot.getLong("age")?.toInt() ?: 12
                currentJournalText = snapshot.getString("journalText") ?: getString(R.string.no_journal_entry)

                binding.childNameTextView.text = childName
                binding.riskLevelValue.text = getString(R.string.risk_level, riskLevelString)
                updateAvatar(riskLevelString, childGender)

                @Suppress("UNCHECKED_CAST")
                val usageByDate = snapshot.get("usageByDate") as? Map<String, Any>
                if (usageByDate != null) {
                    cachedUsageByDate = usageByDate
                    refreshData()
                }
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

    @Suppress("UNCHECKED_CAST")
    private fun renderUsageForDate(usageByDate: Map<String, Any>, dateKey: String) {
        val dayData = usageByDate[dateKey] as? Map<String, Any>

        if (dayData == null) {
            binding.totalTimeTextView.text = "No data for this day"
            binding.appUsageBarChart.clear()
            binding.appUsageBarChart.invalidate()
            return
        }

        val totalMinutes = (dayData["totalMinutes"] as? Number)?.toLong() ?: 0L
        val phoneChecks = (dayData["phoneChecks"] as? Number)?.toInt() ?: 0
        val topApps = (dayData["topApps"] as? List<Map<String, Any>>) ?: emptyList()

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        binding.totalTimeTextView.text = getString(R.string.total_today, hours, minutes)

        var socialMinutes = 0L
        var gamingMinutes = 0L

        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()

        topApps.forEachIndexed { index, app ->
            val appName = app["appName"] as? String ?: "Unknown"
            val usageMinutesLong = (app["usageMinutes"] as? Number)?.toLong() ?: 0L
            val category = (app["category"] as? Number)?.toInt() ?: -1

            entries.add(BarEntry(index.toFloat(), usageMinutesLong.toFloat()))
            labels.add(appName)

            val name = appName.lowercase()
            val isSocialManual = name.contains("instagram") || name.contains("facebook") ||
                    name.contains("whatsapp") || name.contains("youtube") ||
                    name.contains("snapchat") || name.contains("tiktok")
            val isGamingManual = name.contains("pubg") || name.contains("free fire") ||
                    name.contains("cod") || name.contains("minecraft") ||
                    name.contains("roblox") || name.contains("hungryshark")

            if (isSocialManual || category == ApplicationInfo.CATEGORY_SOCIAL || category == ApplicationInfo.CATEGORY_VIDEO) {
                socialMinutes += usageMinutesLong
            } else if (isGamingManual || category == ApplicationInfo.CATEGORY_GAME) {
                gamingMinutes += usageMinutesLong
            }
        }

        Log.d("APP_CATEGORY", "Date: $dateKey | Social: $socialMinutes min, Gaming: $gamingMinutes min, Checks: $phoneChecks")

        if (entries.isNotEmpty()) {
            setupBarChart(entries, labels)
        }

        val totalHours = totalMinutes / 60.0
        val socialHours = socialMinutes / 60.0
        val gamingHours = gamingMinutes / 60.0
        val entertainmentRatio = if (totalHours > 0) (socialHours + gamingHours) / totalHours else 0.0
        val gamingRatio = if (totalHours > 0) gamingHours / totalHours else 0.0
        val socialRatio = if (totalHours > 0) socialHours / totalHours else 0.0
        val engagementIntensity = totalHours * 50

        sendRiskRequest(totalHours, socialHours, gamingHours, phoneChecks, entertainmentRatio, gamingRatio, socialRatio, engagementIntensity)
    }

    private fun updateAvatar(riskLevel: String, gender: String) {
        val level = riskLevel.lowercase()
        if (gender == "Girl") {
            when (level) {
                "low" -> binding.emotionalAvatar.setImageResource(R.drawable.girl_smile)
                "medium" -> binding.emotionalAvatar.setImageResource(R.drawable.girl_mid)
                "high" -> binding.emotionalAvatar.setImageResource(R.drawable.girl_sad)
                else -> binding.emotionalAvatar.setImageResource(R.drawable.girl_mid)
            }
        } else {
            when (level) {
                "low" -> binding.emotionalAvatar.setImageResource(R.drawable.boy_smile)
                "medium" -> binding.emotionalAvatar.setImageResource(R.drawable.boy_mid)
                "high" -> binding.emotionalAvatar.setImageResource(R.drawable.boy_sad)
                else -> binding.emotionalAvatar.setImageResource(R.drawable.boy_mid)
            }
        }
    }

    private fun sendRiskRequest(totalHours: Double, socialHours: Double, gamingHours: Double, phoneChecks: Int, entRatio: Double, gameRatio: Double, socRatio: Double, intensity: Double) {
        val json = JSONObject().apply {
            put("avg_screen_time", totalHours)
            put("social_media_hours", socialHours)
            put("gaming_hours", gamingHours)
            put("night_usage", 2) 
            put("phone_checks_per_day", phoneChecks)
            put("Age", childAge)
            put("entertainment_ratio", entRatio)
            put("night_usage_ratio", 0.28)
            put("engagement_intensity", intensity)
            put("gaming_ratio", gameRatio)
            put("social_ratio", socRatio)
        }

        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url("http://10.0.2.2:5000/predict").post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("API_ERROR", "Failed to connect to ML server: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d("API_RESPONSE", "ML response: $responseBody")

                if (response.isSuccessful && responseBody != null) {
                    val jsonResp = JSONObject(responseBody)
                    val riskLevelInt = jsonResp.optInt("risk_level", 0)
                    val riskLevelString = when (riskLevelInt) {
                        0 -> "Low" 1 -> "Medium" 2 -> "High" else -> "Low"
                    }

                    if (parentId != null && childId != null) {
                        Firebase.firestore.collection("users").document(parentId!!).collection("children").document(childId!!)
                            .update("riskLevel", riskLevelString)
                    }

                    runOnUiThread {
                        binding.riskLevelValue.text = getString(R.string.risk_level, riskLevelString)
                        updateAvatar(riskLevelString, childGender)
                    }
                }
            }
        })
    }

    private fun setupBarChart(entries: ArrayList<BarEntry>, labels: ArrayList<String>) {
        val dataSet = BarDataSet(entries, getString(R.string.app_usage_in_minutes))
        dataSet.color = "#673AB7".toColorInt()
        val barData = BarData(dataSet)
        barData.barWidth = 0.5f
        binding.appUsageBarChart.apply {
            data = barData
            setFitBars(true)
            description.isEnabled = false
            legend.isEnabled = false
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.granularity = 1f
            xAxis.labelRotationAngle = -45f
            axisLeft.axisMinimum = 0f
            axisRight.isEnabled = false
            invalidate()
        }
    }
}
