package com.sigmacoders.aichildmonitor

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager // <-- THE MISSING IMPORT
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

        binding.grantPermissionButton.setOnClickListener {
            requestUsageStatsPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        // Check the permission state every time the user returns to this screen.
        updateUiAndScheduleWork()
    }

    private fun updateUiAndScheduleWork() {
        if (hasUsageStatsPermission()) {
            // Permission is granted, show the normal UI.
            showGrantedState()
            // Schedule the work if IDs are available.
            if (parentId != null && childId != null) {
                scheduleUsageStatsUpload(parentId!!, childId!!)
            }
        } else {
            // Permission is not granted, show the permission request UI.
            showPermissionNeededState()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        if (mode == AppOpsManager.MODE_ALLOWED) {
            return true
        }
        // A fallback check for devices that fail to report MODE_ALLOWED correctly.
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, System.currentTimeMillis() - 1000 * 60, System.currentTimeMillis())
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

        val immediateWorkRequest = OneTimeWorkRequestBuilder<UsageStatsWorker>()
            .setConstraints(constraints)
            .setInputData(workerData)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<UsageStatsWorker>(
            1, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInputData(workerData)
            .build()

        val workManager = WorkManager.getInstance(applicationContext)
        
        workManager.enqueue(immediateWorkRequest)
        Log.d(tag, "One-time usage stats upload worker has been enqueued.")

        workManager.enqueueUniquePeriodicWork(
            "USAGE_STATS_UPLOAD_WORK",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )

        Log.d(tag, "Periodic usage stats upload worker has been scheduled.")
    }
}