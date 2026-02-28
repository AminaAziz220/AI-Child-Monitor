package com.sigmacoders.aichildmonitor.worker

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.Timestamp
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class UsageStatsWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val tag = "UsageStatsWorker"

    override suspend fun doWork(): Result {
        val parentId = inputData.getString("PARENT_ID")
        val childId = inputData.getString("CHILD_ID")

        if (parentId.isNullOrEmpty() || childId.isNullOrEmpty()) {
            Log.e(tag, "Parent ID or Child ID is missing. Cannot perform work.")
            return Result.failure()
        }

        Log.d(tag, "Worker started for parent: $parentId, child: $childId")

        if (!hasUsageStatsPermission()) {
            Log.e(tag, "Usage stats permission not granted. Stopping worker.")
            return Result.failure()
        }

        return try {
            uploadUsageStats(parentId, childId)
            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "Error during usage stats upload", e)
            Result.failure()
        }
    }

    private suspend fun uploadUsageStats(parentId: String, childId: String) {
        val usageStatsManager =
            applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

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
            var totalScreenTime = 0L
            val topApps = mutableListOf<Map<String, Any>>()

            sortedStats.forEach { usageStats ->
                if (usageStats.totalTimeInForeground > 0) {
                    totalScreenTime += usageStats.totalTimeInForeground
                    if (topApps.size < 5) {
                        val appInfo = getAppInfo(usageStats.packageName)
                        if (appInfo != null) {
                            val appName = appInfo.loadLabel(applicationContext.packageManager).toString()
                            val usageMinutes = usageStats.totalTimeInForeground / (1000 * 60)
                            if (usageMinutes > 0) {
                                topApps.add(mapOf(
                                    "appName" to appName,
                                    "usageMinutes" to usageMinutes,
                                    "category" to appInfo.category // Include the category
                                ))
                            }
                        }
                    }
                }
            }

            val totalMinutes = totalScreenTime / (1000 * 60)
            val usageMap = hashMapOf(
                "totalMinutes" to totalMinutes,
                "topApps" to topApps,
                "date" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                "lastUpdated" to Timestamp.now()
            )

            val db = Firebase.firestore
            val childRef = db.collection("users").document(parentId).collection("children").document(childId)

            childRef.set(hashMapOf("usage" to usageMap), SetOptions.merge()).await()
            Log.d(tag, "Successfully uploaded usage stats for child $childId.")
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            applicationContext.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun getAppInfo(packageName: String): ApplicationInfo? {
        return try {
            applicationContext.packageManager.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}