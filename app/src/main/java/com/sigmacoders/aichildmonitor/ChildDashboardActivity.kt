package com.sigmacoders.aichildmonitor

import android.os.Bundle
import android.util.Log
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

class ChildDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildDashboardBinding
    private val tag = "ChildDashboardActivity"
    private var childId: String? = null
    private var parentId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get IDs from the intent that started this activity
        childId = intent.getStringExtra("CHILD_ID")
        parentId = intent.getStringExtra("PARENT_ID")

        if (parentId == null || childId == null) {
            Log.e(tag, "Parent ID or Child ID is null. Finishing activity.")
            finish()
            return
        }

        setupFirestoreListener(parentId!!, childId!!)
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
                // Update basic child info
                val childName = snapshot.getString("name") ?: "Child"
                val riskLevel = snapshot.getString("riskLevel") ?: "Low"
                currentJournalText = snapshot.getString("journalText") ?: getString(R.string.no_journal_entry)

                binding.childNameTextView.text = childName
                binding.riskLevelValue.text = getString(R.string.risk_level, riskLevel)

                when (riskLevel.lowercase()) {
                    "low" -> binding.emotionalAvatar.text = "😊"
                    "medium" -> binding.emotionalAvatar.text = "😐"
                    "high" -> binding.emotionalAvatar.text = "😔"
                    else -> binding.emotionalAvatar.text = "🤷"
                }

                // Update UI from Firestore usage data
                displayUsageDataFromSnapshot(snapshot)
            } else {
                Log.w(tag, "Child document does not exist.")
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
    private fun displayUsageDataFromSnapshot(snapshot: com.google.firebase.firestore.DocumentSnapshot) {
        val usageData = snapshot.get("usage") as? Map<String, Any>

        if (usageData != null) {
            val totalMinutes = (usageData["totalMinutes"] as? Long) ?: 0L
            val topApps = (usageData["topApps"] as? List<Map<String, Any>>) ?: emptyList()

            // Update total time text view
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            binding.totalTimeTextView.text = getString(R.string.total_today, hours, minutes)

            // Update bar chart
            val entries = ArrayList<BarEntry>()
            val labels = ArrayList<String>()
            topApps.forEachIndexed { index, app ->
                val appName = app["appName"] as? String ?: "Unknown"
                val usageMinutes = (app["usageMinutes"] as? Long)?.toFloat() ?: 0f
                entries.add(BarEntry(index.toFloat(), usageMinutes))
                labels.add(appName)
            }

            if (entries.isNotEmpty()) {
                setupBarChart(entries, labels)
            } else {
                binding.appUsageBarChart.data = null
                binding.appUsageBarChart.invalidate()
            }
        } else {
            // If no usage data is present, clear the views
            binding.totalTimeTextView.text = getString(R.string.total_today, 0, 0)
            binding.appUsageBarChart.data = null
            binding.appUsageBarChart.invalidate()
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