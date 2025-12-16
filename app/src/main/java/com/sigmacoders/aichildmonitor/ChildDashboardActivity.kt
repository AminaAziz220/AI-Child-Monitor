package com.sigmacoders.aichildmonitor

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.sigmacoders.aichildmonitor.databinding.ActivityChildDashboardBinding
import java.util.Calendar

class ChildDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildDashboardBinding
    private val tag = "ChildDashboardActivity"
    private var childId: String? = null
    private var parentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        childId = intent.getStringExtra("CHILD_ID")
        parentId = Firebase.auth.currentUser?.uid

        if (parentId == null || childId == null) {
            // Can't function without these, so close
            finish()
            return
        }

        setupFirestoreListener(parentId!!, childId!!)
        setupClickListeners()

        if (!hasUsageStatsPermission()) {
            requestUsageStatsPermission()
        } else {
            getAppUsageStats()
        }
    }

    private fun setupFirestoreListener(parentId: String, childId: String) {
        val db = Firebase.firestore
        val childRef = db.collection("users").document(parentId).collection("children").document(childId)
        var currentJournalText = getString(R.string.no_journal_entry)

        childRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(tag, "Listen failed.", error)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val childName = snapshot.getString("name") ?: "Child"
                val riskLevel = snapshot.getString("riskLevel") ?: "Low"
                currentJournalText = snapshot.getString("journalText") ?: getString(R.string.no_journal_entry)

                binding.childNameTextView.text = childName
                binding.riskLevelValue.text = getString(R.string.risk_level, riskLevel)

                // Update avatar based on risk level
                when (riskLevel.lowercase()) {
                    "low" -> binding.emotionalAvatar.text = "😊"
                    "medium" -> binding.emotionalAvatar.text = "😐"
                    "high" -> binding.emotionalAvatar.text = "😔"
                    else -> binding.emotionalAvatar.text = "🤷"
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

    private fun setupClickListeners() {
        // Future buttons on this screen can be set up here
    }

    // --- Usage Stats and Charting Logic (Copied from old MainActivity) ---

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageStatsPermission() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_required))
            .setMessage(getString(R.string.usage_stats_permission_message))
            .setPositiveButton(getString(R.string.grant)) { _, _ -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun getAppUsageStats() {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startTime = cal.timeInMillis
        val endTime = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)

        if (stats != null) {
            val sortedStats = stats.sortedByDescending { it.totalTimeInForeground }
            val entries = ArrayList<BarEntry>()
            val labels = ArrayList<String>()
            var totalScreenTime = 0L

            sortedStats.forEachIndexed { index, usageStats ->
                if (usageStats.totalTimeInForeground > 0) {
                    totalScreenTime += usageStats.totalTimeInForeground
                    if (index < 5) {
                        val appName = usageStats.packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
                        val totalTimeMinutes = usageStats.totalTimeInForeground / (1000 * 60f)
                        entries.add(BarEntry(index.toFloat(), totalTimeMinutes))
                        labels.add(appName)
                    }
                }
            }

            if (entries.isNotEmpty()) {
                setupBarChart(entries, labels)
            }

            val hours = totalScreenTime / (1000 * 60 * 60)
            val minutes = (totalScreenTime / (1000 * 60)) % 60
            binding.totalTimeTextView.text = getString(R.string.total_today, hours, minutes)
        }
    }

    private fun setupBarChart(entries: ArrayList<BarEntry>, labels: ArrayList<String>) {
        val dataSet = BarDataSet(entries, getString(R.string.app_usage_in_minutes))
        dataSet.color = "#673AB7".toColorInt()
        val barData = BarData(dataSet)
        barData.barWidth = 0.5f

        binding.appUsageBarChart.data = barData
        binding.appUsageBarChart.setFitBars(true)
        binding.appUsageBarChart.description.isEnabled = false
        binding.appUsageBarChart.legend.isEnabled = false

        val xAxis = binding.appUsageBarChart.xAxis
        xAxis.valueFormatter = IndexAxisValueFormatter(labels)
        xAxis.setDrawGridLines(false)
        xAxis.granularity = 1f
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.labelRotationAngle = -45f

        binding.appUsageBarChart.axisLeft.setDrawGridLines(false)
        binding.appUsageBarChart.axisLeft.axisMinimum = 0f
        binding.appUsageBarChart.axisRight.isEnabled = false

        binding.appUsageBarChart.invalidate() // Refresh chart
    }
}