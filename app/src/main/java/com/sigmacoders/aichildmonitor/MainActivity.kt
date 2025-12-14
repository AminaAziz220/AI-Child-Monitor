package com.sigmacoders.aichildmonitor

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.sigmacoders.aichildmonitor.databinding.ActivityMainBinding
import com.sigmacoders.aichildmonitor.ai.VideoClassifier
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val TAG = "MainActivity"
    private val childrenList = mutableListOf<Pair<String, String>>() // Pair of (Child ID, Child Name)
    private lateinit var childrenAdapter: ArrayAdapter<String>
    private lateinit var sharedPrefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPrefs = getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

        val auth = Firebase.auth
        val userId = auth.currentUser?.uid

        if (userId == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setupChildSpinner(userId)
        setupClickListeners(userId)

        if (!hasUsageStatsPermission()) {
            requestUsageStatsPermission()
        } else {
            getAppUsageStats()
        }
    }

    private fun setupChildSpinner(userId: String) {
        childrenAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, childrenList.map { it.second })
        childrenAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.childSpinner.adapter = childrenAdapter

        fetchChildren(userId)

        binding.childSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedChildId = childrenList[position].first
                // Save the selected child's info for the service to use
                with(sharedPrefs.edit()) {
                    putString("current_user_id", userId)
                    putString("current_child_id", selectedChildId)
                    apply()
                }
                setupFirestoreListener(userId, selectedChildId)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun fetchChildren(userId: String) {
        val db = Firebase.firestore
        db.collection("users").document(userId).collection("children")
            .get()
            .addOnSuccessListener { documents ->
                childrenList.clear()
                for (document in documents) {
                    childrenList.add(Pair(document.id, document.getString("name") ?: "Unnamed"))
                }
                childrenAdapter.clear()
                childrenAdapter.addAll(childrenList.map { it.second })
                childrenAdapter.notifyDataSetChanged()
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Error getting children: ", exception)
            }
    }

    private fun setupFirestoreListener(userId: String, childId: String) {
        val db = Firebase.firestore
        val childRef = db.collection("users").document(userId).collection("children").document(childId)
        var currentJournalText = "No journal entry found."

        childRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Listen failed.", error)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                val riskLevel = snapshot.getString("riskLevel") ?: "Low"
                binding.riskLevelValue.text = "Risk Level: $riskLevel"
                currentJournalText = snapshot.getString("journalText") ?: "No journal entry found."

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
                .setTitle("Today's Journal Entry")
                .setMessage(currentJournalText)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun setupClickListeners(userId: String) {
        binding.logoutButton.setOnClickListener {
            Firebase.auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.addChildButton.setOnClickListener {
            showAddChildDialog(userId)
        }
    }

    private fun showAddChildDialog(userId: String) {
        val editText = EditText(this).apply {
            hint = "Enter child\'s name"
        }

        AlertDialog.Builder(this)
            .setTitle("Add New Child")
            .setView(editText)
            .setPositiveButton("Add") { dialog, _ ->
                val childName = editText.text.toString().trim()
                if (childName.isNotEmpty()) {
                    addNewChild(userId, childName)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addNewChild(userId: String, childName: String) {
        val db = Firebase.firestore
        val newChild = hashMapOf(
            "name" to childName,
            "riskLevel" to "Low",
            "journalText" to "No entry yet."
        )
        db.collection("users").document(userId).collection("children")
            .add(newChild)
            .addOnSuccessListener {
                Log.d(TAG, "New child added successfully")
                fetchChildren(userId) // Refresh the spinner
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Error adding new child", e)
            }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun requestUsageStatsPermission() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("This app needs access to app usage stats to function correctly. Please grant the permission in the next screen.")
            .setPositiveButton("Grant") { _, _ -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getAppUsageStats() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // Get stats from the start of the day to now
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

            // Update total time text view
            val hours = totalScreenTime / (1000 * 60 * 60)
            val minutes = (totalScreenTime / (1000 * 60)) % 60
            binding.totalTimeTextView.text = "Total (Today): ${hours}h ${minutes}m"

        }
    }

    private fun setupBarChart(entries: ArrayList<BarEntry>, labels: ArrayList<String>) {
        val dataSet = BarDataSet(entries, "App Usage in Minutes")
        dataSet.color = Color.parseColor("#673AB7")

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

        binding.appUsageBarChart.invalidate() // Refresh the chart
    }
}