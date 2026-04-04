package com.sigmacoders.aichildmonitor

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
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
    private var childName: String = "Child"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private var currentDaysOffset = 0
    private var cachedUsageByDate: Map<String, Any>? = null
    private var currentJournalText = ""
    private var currentRiskLevel = "Low"
    private val attemptedDates = mutableSetOf<String>()

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

        binding.setLimitButton.setOnClickListener {
            showSetLimitDialog()
        }
    }

    private fun showSetLimitDialog() {
        val input = EditText(this)
        input.hint = "Enter limit in minutes (e.g. 120)"
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER

        AlertDialog.Builder(this)
            .setTitle("Set Daily Screen Time Limit")
            .setMessage("The parent will be notified if the child exceeds this many minutes today.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val limitStr = input.text.toString()
                if (limitStr.isNotEmpty()) {
                    val limitMinutes = limitStr.toLong()
                    saveLimitToFirestore(limitMinutes)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveLimitToFirestore(minutes: Long) {
        if (parentId != null && childId != null) {
            Firebase.firestore.collection("users").document(parentId!!).collection("children").document(childId!!)
                .update("screenTimeLimit", minutes)
                .addOnSuccessListener {
                    Toast.makeText(this, "Limit set to $minutes minutes.", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun setupNavigation() {
        updateDateDisplay()

        binding.btnPrevDay.setOnClickListener {
            if (currentDaysOffset < 6) {
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
                childName = snapshot.getString("name") ?: "Child"
                val riskLevelString = snapshot.getString("riskLevel") ?: "Low"
                childGender = snapshot.getString("gender") ?: "Boy"
                childAge = snapshot.getLong("age")?.toInt() ?: 12
                currentJournalText = snapshot.getString("journalText") ?: getString(R.string.no_journal_entry)

                // Display screen time limit if it exists
                val limit = snapshot.getLong("screenTimeLimit")
                if (limit != null) {
                    binding.limitTextView.text = getString(R.string.limit_min, limit)
                } else {
                    binding.limitTextView.text = getString(R.string.limit_not_set)
                }

                binding.childNameTextView.text = childName
                binding.riskLevelValue.text = getString(R.string.risk_level, riskLevelString)
                updateAvatar(riskLevelString, childGender)

                @Suppress("UNCHECKED_CAST")
                val usageByDate = snapshot.get("usageByDate") as? Map<String, Any>

                if (usageByDate != null) {
                    cachedUsageByDate = usageByDate

                    // Weekly trend chart (new feature)
                    renderWeeklyTrend(usageByDate)

                    // Weekly risk (new feature)
                    val weeklyRisk = computeWeeklyRisk(usageByDate)
                    binding.weeklyRiskText.text = "Weekly Risk: $weeklyRisk"

                    Firebase.firestore.collection("users")
                        .document(parentId)
                        .collection("children")
                        .document(childId)
                        .update("weeklyRiskLevel", weeklyRisk)

                    refreshData()
                }
            }
        }

        binding.journalButton.setOnClickListener {
            val title = if (currentDaysOffset == 0) "Today's Wellness Journal" else "${binding.usageDateLabel.text}'s Wellness Journal"
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(currentJournalText)
                .setPositiveButton(getString(R.string.close), null)
                .show()
        }
    }

    private fun computeWeeklyRisk(usageByDate: Map<String, Any>): String {

        var totalMinutes = 0.0
        var totalNightMinutes = 0.0
        var totalPhoneChecks = 0
        var daysCount = 0

        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.SUNDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)

        val weekStart = calendar.clone() as Calendar
        val today = Calendar.getInstance()

        while (!weekStart.after(today)) {

            val key = dateFormat.format(weekStart.time)
            val dayData = usageByDate[key] as? Map<String, Any>

            if (dayData != null) {
                val minutes = (dayData["totalMinutes"] as? Number)?.toDouble() ?: 0.0
                val night = (dayData["nightUsageMinutes"] as? Number)?.toDouble() ?: 0.0
                val checks = (dayData["phoneChecks"] as? Number)?.toInt() ?: 0

                totalMinutes += minutes
                totalNightMinutes += night
                totalPhoneChecks += checks
                daysCount++
            }

            weekStart.add(Calendar.DAY_OF_YEAR, 1)
        }

        if (daysCount == 0) return "Low"

        val avgDailyHours = (totalMinutes / daysCount) / 60.0
        val nightRatio = if (totalMinutes > 0) totalNightMinutes / totalMinutes else 0.0
        val avgChecks = totalPhoneChecks / daysCount.toDouble()

        val usageScore = avgDailyHours / 6.0
        val nightScore = nightRatio
        val checkScore = avgChecks / 80.0

        val riskScore = 0.5 * usageScore + 0.3 * nightScore + 0.2 * checkScore

        return when {
            riskScore < 0.33 -> "Low"
            riskScore < 0.66 -> "Medium"
            else -> "High"
        }
    }

    private fun renderWeeklyTrend(usageByDate: Map<String, Any>) {

        val entries = ArrayList<Entry>()
        val labels = ArrayList<String>()

        val calendar = Calendar.getInstance()
        calendar.firstDayOfWeek = Calendar.SUNDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)

        val today = Calendar.getInstance()
        var index = 0

        while (!calendar.after(today)) {

            val key = dateFormat.format(calendar.time)
            val dayData = usageByDate[key] as? Map<String, Any>
            val minutes = (dayData?.get("totalMinutes") as? Number)?.toFloat() ?: 0f

            entries.add(Entry(index.toFloat(), minutes))
            labels.add(SimpleDateFormat("EEE", Locale.US).format(calendar.time))

            calendar.add(Calendar.DAY_OF_YEAR, 1)
            index++
        }

        val dataSet = LineDataSet(entries, "Weekly Screen Time")
        dataSet.lineWidth = 3f
        dataSet.circleRadius = 4f
        dataSet.color = "#673AB7".toColorInt()
        dataSet.setCircleColor("#673AB7".toColorInt())

        val data = LineData(dataSet)

        binding.weeklyTrendChart.apply {
            this.data = data
            xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            axisRight.isEnabled = false
            description.isEnabled = false
            invalidate()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderUsageForDate(usageByDate: Map<String, Any>, dateKey: String) {
        val dayData = usageByDate[dateKey] as? Map<String, Any>

        if (dayData == null) {
            binding.totalTimeTextView.text = getString(R.string.no_data_day)
            binding.appUsageBarChart.clear()
            binding.appUsageBarChart.invalidate()
            currentJournalText = "No data available for this date."
            binding.riskLevelValue.text = getString(R.string.risk_level, "N/A")
            return
        }

        val totalMinutes = (dayData["totalMinutes"] as? Number)?.toLong() ?: 0L
        val phoneChecks = (dayData["phoneChecks"] as? Number)?.toInt() ?: 0

        val nightUsageMinutes = (dayData["nightUsageMinutes"] as? Number)?.toDouble() ?: 0.0
        val nightUsageRatio = (dayData["nightUsageRatio"] as? Number)?.toDouble() ?: 0.0

        val topApps = (dayData["topApps"] as? List<Map<String, Any>>) ?: emptyList()

        // Load persisted risk & journal from Firestore day data (written back by Flask)
        currentRiskLevel = dayData["riskLevel"] as? String ?: "Calculating..."
        currentJournalText = dayData["journalText"] as? String ?: "Analyzing usage patterns..."

        binding.riskLevelValue.text = getString(R.string.risk_level, currentRiskLevel)
        updateAvatar(currentRiskLevel, childGender)

        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        binding.totalTimeTextView.text = getString(R.string.total_hm, hours, minutes)

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
                    name.contains("roblox") || name.contains("hungryshark") || name.contains("juggle")

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

        // Journal generation logic: only call Flask if we don't have a real journal yet
        val isLocalFallback = currentJournalText.startsWith("Digital Narrative")
        val isPythonFallback = currentJournalText.startsWith("Today's usage summary shows")
        val isPlaceholder = currentJournalText == "Analyzing usage patterns..." || currentJournalText.isEmpty()

        val needsRealJournal = isLocalFallback || isPythonFallback || isPlaceholder

        if (needsRealJournal && !attemptedDates.contains(dateKey)) {
            attemptedDates.add(dateKey)
            // Show a local fallback immediately so the screen isn't blank
            currentJournalText = generateEnhancedJournal(totalMinutes, topApps, phoneChecks, nightUsageMinutes, "Low (Initial)", socialMinutes, gamingMinutes)
            // Fire network request to Flask/Gemini
            sendRiskRequest(
                dk = dateKey,
                tm = totalMinutes,
                ta = topApps,
                totalHours = totalHours,
                socialHours = socialHours,
                gamingHours = gamingHours,
                phoneChecks = phoneChecks,
                nightUsage = nightUsageMinutes,
                nightRatio = nightUsageRatio,
                socialMin = socialMinutes,
                gamingMin = gamingMinutes
            )
        } else if (!needsRealJournal) {
            // Already have a real AI journal — still update ML risk via original endpoint
            sendRiskRequestLegacy(
                totalHours = totalHours,
                socialHours = socialHours,
                gamingHours = gamingHours,
                phoneChecks = phoneChecks,
                nightUsage = nightUsageMinutes,
                nightRatio = nightUsageRatio,
                entRatio = entertainmentRatio,
                gameRatio = gamingRatio,
                socRatio = socialRatio,
                intensity = totalHours * 50
            )
        }
    }

    private fun generateEnhancedJournal(
        totalMinutes: Long,
        topApps: List<Map<String, Any>>,
        phoneChecks: Int,
        nightMinutes: Double,
        riskLevel: String,
        socialMinutes: Long,
        gamingMinutes: Long
    ): String {
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        val topAppMap = topApps.firstOrNull()
        val topAppName = topAppMap?.get("appName") as? String ?: "various apps"

        val journal = StringBuilder()

        journal.append("Digital Narrative\n")
        journal.append("-----------------\n")
        journal.append("$childName spent ${h}h ${m}m on the phone today. ")
        journal.append("They reached for their device $phoneChecks times, with \"$topAppName\" being their favorite destination. ")

        if (nightMinutes > 15) {
            journal.append("Notably, ${nightMinutes.toInt()} minutes of usage happened late at night, which might be impacting their sleep quality. ")
        } else {
            journal.append("Great news! There was almost no activity during sleep hours. ")
        }

        journal.append("\n\nRisk Analysis: $riskLevel\n")
        journal.append("-----------------\n")
        val cleanRisk = riskLevel.split(" ").first()
        when (cleanRisk) {
            "Low" -> journal.append("Healthy balance! The digital footprint looks stable and age-appropriate.")
            "Medium" -> journal.append("Caution: Screen time is creeping up. The frequency of checks suggests a growing dependency.")
            "High" -> journal.append("Alert: Critical usage detected. The combination of high screen time and $phoneChecks checks points toward potential digital burnout.")
            else -> journal.append("Assessing usage patterns based on screen time and app categories.")
        }

        journal.append("\n\nParenting Prompts (Tailored Advice:)\n")
        journal.append("-----------------\n")

        val prompts = mutableListOf<String>()

        if (nightMinutes > 30) {
            prompts.add("1. \"I noticed you were using your phone late last night. Was everything okay, or did you just lose track of time?\"")
            prompts.add("2. \"How about we set a 'digital sunset' where all devices go to sleep at 9 PM tonight?\"")
        } else if (phoneChecks > 60) {
            prompts.add("1. \"You checked your phone quite often today ($phoneChecks times!). Did you feel like you were waiting for something important?\"")
            prompts.add("2. \"Let's try a challenge: can we go for a walk together without either of us checking our phones?\"")
        } else if (gamingMinutes > socialMinutes && gamingMinutes > 120) {
            prompts.add("1. \"That was quite a gaming session today! What's the most challenging part of $topAppName right now?\"")
            prompts.add("2. \"I'd love to see you play for a bit. Want to show me how it works?\"")
        } else if (socialMinutes > gamingMinutes && socialMinutes > 120) {
            prompts.add("1. \"You spent a lot of time on social media today. Did you see anything that made you laugh or feel inspired?\"")
            prompts.add("2. \"Sometimes social media can feel a bit overwhelming. Do you ever feel like you need a break from it?\"")
        } else if (totalMinutes > 360) {
            prompts.add("1. \"Today was a big screen day ($h hours!). How do your eyes and head feel right now?\"")
            prompts.add("2. \"Let's pick one offline activity we can both do tomorrow to balance out the screen time.\"")
        } else {
            prompts.add("1. \"You did a great job managing your time today! What was the highlight of your day?\"")
            prompts.add("2. \"Is there any new app or hobby you've been curious about lately?\"")
        }

        prompts.forEach { journal.append(it).append("\n") }

        return journal.toString()
    }

    // New sendRiskRequest: calls /analyze, handles AI journal from Flask/Gemini
    private fun sendRiskRequest(
        dk: String,
        tm: Long,
        ta: List<Map<String, Any>>,
        totalHours: Double,
        socialHours: Double,
        gamingHours: Double,
        phoneChecks: Int,
        nightUsage: Double,
        nightRatio: Double,
        socialMin: Long,
        gamingMin: Long
    ) {
        val intensity = totalHours * 50
        val er = if (totalHours > 0) (socialHours + gamingHours) / totalHours else 0.0
        val gr = if (totalHours > 0) gamingHours / totalHours else 0.0
        val sr = if (totalHours > 0) socialHours / totalHours else 0.0

        val json = JSONObject().apply {
            put("avg_screen_time", totalHours)
            put("social_media_hours", socialHours)
            put("gaming_hours", gamingHours)
            put("night_usage", nightUsage)
            put("phone_checks_per_day", phoneChecks)
            put("Age", childAge)
            put("entertainment_ratio", er)
            put("night_usage_ratio", nightRatio)
            put("engagement_intensity", intensity)
            put("gaming_ratio", gr)
            put("social_ratio", sr)
        }

        val request = Request.Builder()
            .url("http://10.0.2.2:5000/analyze")
            .post(json.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("API_ERROR", "Failed to connect to /analyze: ${e.message}")
                val estimatedRisk = if (totalHours > 6) "High (Est.)" else if (totalHours > 3) "Medium (Est.)" else "Low (Est.)"
                val fallbackJournal = generateEnhancedJournal(tm, ta, phoneChecks, nightUsage, estimatedRisk, socialMin, gamingMin)
                runOnUiThread {
                    updateCurrentViewIfMatch(dk, fallbackJournal, estimatedRisk)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                Log.d("API_RESPONSE", "ML /analyze response: $body")

                if (response.isSuccessful && body != null) {
                    val jsonResp = JSONObject(body)
                    val riskInt = jsonResp.optInt("risk_level", 0)
                    val riskLevelString = when (riskInt) {
                        1 -> "Medium"
                        2 -> "High"
                        else -> "Low"
                    }
                    val aiJournal = jsonResp.optString(
                        "journal",
                        generateEnhancedJournal(tm, ta, phoneChecks, nightUsage, riskLevelString, socialMin, gamingMin)
                    )

                    if (parentId != null && childId != null) {
                        Firebase.firestore.collection("users").document(parentId!!).collection("children").document(childId!!)
                            .update(
                                "usageByDate.$dk.riskLevel", riskLevelString,
                                "usageByDate.$dk.journalText", aiJournal,
                                "riskLevel", riskLevelString
                            )
                    }

                    runOnUiThread {
                        updateCurrentViewIfMatch(dk, aiJournal, riskLevelString)
                    }
                }
            }
        })
    }

    // Legacy sendRiskRequest: calls /predict (original endpoint), no journal
    private fun sendRiskRequestLegacy(
        totalHours: Double,
        socialHours: Double,
        gamingHours: Double,
        phoneChecks: Int,
        nightUsage: Double,
        nightRatio: Double,
        entRatio: Double,
        gameRatio: Double,
        socRatio: Double,
        intensity: Double
    ) {
        val json = JSONObject().apply {
            put("avg_screen_time", totalHours)
            put("social_media_hours", socialHours)
            put("gaming_hours", gamingHours)
            put("night_usage", nightUsage)
            put("phone_checks_per_day", phoneChecks)
            put("Age", childAge)
            put("entertainment_ratio", entRatio)
            put("night_usage_ratio", nightRatio)
            put("engagement_intensity", intensity)
            put("gaming_ratio", gameRatio)
            put("social_ratio", socRatio)
        }

        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder().url("http://10.0.2.2:5000/analyze").post(body).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("API_ERROR", "Failed to connect to ML server: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d("API_RESPONSE", "ML /predict response: $responseBody")

                if (response.isSuccessful && responseBody != null) {
                    val jsonResp = JSONObject(responseBody)
                    val riskLevelInt = jsonResp.optInt("risk_level", 0)
                    val riskLevelString = when (riskLevelInt) {
                        0 -> "Low"
                        1 -> "Medium"
                        2 -> "High"
                        else -> "Low"
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

    private fun updateCurrentViewIfMatch(dateKey: String, journal: String, risk: String) {
        val displayedDate = dateFormat.format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -currentDaysOffset) }.time
        )
        if (displayedDate == dateKey) {
            currentJournalText = journal
            currentRiskLevel = risk
            binding.riskLevelValue.text = getString(R.string.risk_level, risk)
            updateAvatar(risk, childGender)
        }
    }

    private fun updateAvatar(riskLevel: String, gender: String) {
        val level = riskLevel.lowercase()
        if (gender == "Girl") {
            when {
                level.contains("low") -> binding.emotionalAvatar.setImageResource(R.drawable.girl_smile)
                level.contains("high") -> binding.emotionalAvatar.setImageResource(R.drawable.girl_sad)
                else -> binding.emotionalAvatar.setImageResource(R.drawable.girl_mid)
            }
        } else {
            when {
                level.contains("low") -> binding.emotionalAvatar.setImageResource(R.drawable.boy_smile)
                level.contains("high") -> binding.emotionalAvatar.setImageResource(R.drawable.boy_sad)
                else -> binding.emotionalAvatar.setImageResource(R.drawable.boy_mid)
            }
        }
    }

    private fun setupBarChart(entries: ArrayList<BarEntry>, labels: ArrayList<String>) {
        val dataSet = BarDataSet(entries, "App Usage (min)")
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