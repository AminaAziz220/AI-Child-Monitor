package com.sigmacoders.aichildmonitor

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import com.sigmacoders.aichildmonitor.databinding.ActivityChildHomeBinding
import com.sigmacoders.aichildmonitor.worker.UsageStatsWorker
import java.util.concurrent.TimeUnit

class ChildHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildHomeBinding
    private val tag = "ChildHomeActivity"

    private var parentId: String? = null
    private var childId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        parentId = intent.getStringExtra("PARENT_ID")
        childId = intent.getStringExtra("CHILD_ID")

        Log.d(tag, "Parent=$parentId Child=$childId")

        binding.grantPermissionButton.setOnClickListener {
            requestUsageStatsPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiAndScheduleWork()
    }

    private fun updateUiAndScheduleWork() {

        if (!hasUsageStatsPermission()) {
            showPermissionNeededState()
            return
        }

        showGrantedState()

        if (parentId.isNullOrEmpty() || childId.isNullOrEmpty()) {
            Log.e(tag, "IDs missing — cannot schedule worker")
            return
        }

        scheduleUsageStatsUpload(parentId!!, childId!!)
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )

        if (mode == AppOpsManager.MODE_ALLOWED)
            return true

        return try {
            val usageStatsManager =
                getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                System.currentTimeMillis() - 60000,
                System.currentTimeMillis()
            )
            stats != null
        } catch (e: Exception) {
            false
        }
    }

    private fun requestUsageStatsPermission() {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    private fun showGrantedState() {
        binding.welcomeTextView.visibility = View.VISIBLE
        binding.statusTextView.visibility = View.VISIBLE
        binding.permissionTextView.visibility = View.GONE
        binding.grantPermissionButton.visibility = View.GONE
    }

    private fun showPermissionNeededState() {
        binding.welcomeTextView.visibility = View.GONE
        binding.statusTextView.visibility = View.GONE
        binding.permissionTextView.visibility = View.VISIBLE
        binding.grantPermissionButton.visibility = View.VISIBLE
    }

    private fun scheduleUsageStatsUpload(parentId: String, childId: String) {

        val workerData = workDataOf(
            "PARENT_ID" to parentId,
            "CHILD_ID" to childId
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workManager = WorkManager.getInstance(applicationContext)

        // 🔹 Immediate one-time execution (replace existing)
        val immediateWork =
            OneTimeWorkRequestBuilder<UsageStatsWorker>()
                .setConstraints(constraints)
                .setInputData(workerData)
                .build()

        workManager.enqueueUniqueWork(
            "USAGE_STATS_IMMEDIATE",
            ExistingWorkPolicy.REPLACE,
            immediateWork
        )

        Log.d(tag, "Immediate worker scheduled")

        // 🔹 Periodic execution (minimum allowed = 15 min)
        val periodicWork =
            PeriodicWorkRequestBuilder<UsageStatsWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(workerData)
                .build()

        workManager.enqueueUniquePeriodicWork(
            "USAGE_STATS_PERIODIC",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )

        Log.d(tag, "Periodic worker scheduled (15 min)")
    }
}