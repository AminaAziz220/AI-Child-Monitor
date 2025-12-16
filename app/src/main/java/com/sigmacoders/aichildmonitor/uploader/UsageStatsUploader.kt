package com.sigmacoders.aichildmonitor.uploader

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class UsageStatsUploader(private val context: Context) {

    private val tag = "UsageStatsUploader"

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        context.startActivity(intent)
    }

    fun uploadUsageStats(parentId: String, childId: String) {
        if (!hasUsageStatsPermission()) {
            Log.w(tag, "Usage stats permission not granted. Cannot upload.")
            // Optionally, request permission again if the app is opened.
            requestUsageStatsPermission()
            return
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
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
                        val appName = getAppName(usageStats.packageName)
                        val usageMinutes = usageStats.totalTimeInForeground / (1000 * 60)
                        if (usageMinutes > 0) {
                            topApps.add(mapOf(
                                "appName" to appName,
                                "usageMinutes" to usageMinutes
                            ))
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

            // Use set with merge to create or update the usage field without overwriting other child data
            childRef.set(hashMapOf("usage" to usageMap), SetOptions.merge())
                .addOnSuccessListener { Log.d(tag, "Successfully uploaded usage stats for child $childId.") }
                .addOnFailureListener { e -> Log.e(tag, "Error uploading usage stats", e) }
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }
}