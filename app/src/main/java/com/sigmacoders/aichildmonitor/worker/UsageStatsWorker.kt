package com.sigmacoders.aichildmonitor.worker

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class UsageStatsWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val tag = "UsageStatsWorker"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override suspend fun doWork(): Result {
        val parentId = inputData.getString("PARENT_ID")
        val childId = inputData.getString("CHILD_ID")

        if (parentId.isNullOrEmpty() || childId.isNullOrEmpty()) return Result.failure()
        if (!hasUsageStatsPermission()) return Result.failure()

        return try {
            uploadUsageStats(parentId, childId)
            Result.success()
        } catch (e: Exception) {
            Log.e(tag, "Error during usage stats upload", e)
            Result.failure()
        }
    }

    private suspend fun uploadUsageStats(parentId: String, childId: String) {
        val db = Firebase.firestore
        val childRef = db.collection("users").document(parentId).collection("children").document(childId)

        val usageByDate = hashMapOf<String, Any>()
        val activeDateKeys = mutableListOf<String>()

        for (i in 0..6) {
            val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
            val dateKey = dateFormat.format(calendar.time)
            usageByDate[dateKey] = fetchDayUsage(calendar)
            activeDateKeys.add(dateKey)
        }

        usageByDate["lastUpdated"] = Timestamp.now()
        childRef.set(hashMapOf("usageByDate" to usageByDate), SetOptions.merge()).await()
        flushOldData(childRef, activeDateKeys)
    }

    private fun fetchDayUsage(dayCal: Calendar): Map<String, Any> {
        val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val startCal = dayCal.clone() as Calendar
        startCal.set(Calendar.HOUR_OF_DAY, 0)
        startCal.set(Calendar.MINUTE, 0)
        startCal.set(Calendar.SECOND, 0)
        val startTime = startCal.timeInMillis

        val endCal = dayCal.clone() as Calendar
        endCal.set(Calendar.HOUR_OF_DAY, 23)
        endCal.set(Calendar.MINUTE, 59)
        endCal.set(Calendar.SECOND, 59)
        val endTime = minOf(endCal.timeInMillis, System.currentTimeMillis())

        // 1. Fetch Stats and Deduplicate using a Map
        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
        val appMap = mutableMapOf<String, Long>() // PackageName -> Minutes

        stats?.forEach { usageStat ->
            if (usageStat.totalTimeInForeground > 0) {
                val pkg = usageStat.packageName
                val minutes = usageStat.totalTimeInForeground / (1000 * 60)
                if (minutes > 0) {
                    // Update map with max value to prevent duplicates
                    appMap[pkg] = maxOf(appMap[pkg] ?: 0L, minutes)
                }
            }
        }

        val topApps = appMap.entries
            .sortedByDescending { it.value }
            .take(15)
            .map { entry ->
                mapOf(
                    "appName" to getAppName(entry.key),
                    "usageMinutes" to entry.value,
                    "category" to getCategory(entry.key)
                )
            }

        val totalMinutes = appMap.values.sum()
        val phoneChecks = calculatePhoneChecks(startTime, endTime)
        val nightMinutes = calculateNightUsage(dayCal)
        val nightRatio = if (totalMinutes > 0) nightMinutes.toDouble() / totalMinutes else 0.0

        Log.d(tag, "Date: ${dateFormat.format(dayCal.time)} | Total: $totalMinutes min | Night: $nightMinutes min | Ratio: $nightRatio")

        return mapOf(
            "totalMinutes" to totalMinutes,
            "phoneChecks" to phoneChecks,
            "nightUsageMinutes" to nightMinutes,
            "nightUsageRatio" to nightRatio,
            "topApps" to topApps
        )
    }

    private fun calculateNightUsage(dayCal: Calendar): Long {

        val usageStatsManager =
            applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val nightStart = dayCal.clone() as Calendar
        nightStart.set(Calendar.HOUR_OF_DAY, 0)
        nightStart.set(Calendar.MINUTE, 0)
        nightStart.set(Calendar.SECOND, 0)

        val nightEnd = dayCal.clone() as Calendar
        nightEnd.set(Calendar.HOUR_OF_DAY, 6)
        nightEnd.set(Calendar.MINUTE, 0)
        nightEnd.set(Calendar.SECOND, 0)

        val startTime = nightStart.timeInMillis
        val endTime = minOf(nightEnd.timeInMillis, System.currentTimeMillis())

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        var totalNightMs = 0L

        stats?.forEach { usageStat ->
            val appTime = usageStat.totalTimeInForeground
            if (appTime > 0) {
                totalNightMs += appTime
            }
        }

        return totalNightMs / (1000 * 60)
    }

    private fun calculatePhoneChecks(start: Long, end: Long): Int {
        val usageStatsManager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usageStatsManager.queryEvents(start, end)
        val event = UsageEvents.Event()
        var count = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) count++
        }
        return count
    }

    private suspend fun flushOldData(childRef: com.google.firebase.firestore.DocumentReference, activeKeys: List<String>) {
        try {
            val snapshot = childRef.get().await()
            @Suppress("UNCHECKED_CAST")
            val usageByDate = snapshot.get("usageByDate") as? Map<String, Any> ?: return
            val keysToDelete = usageByDate.keys.filter { key -> key != "lastUpdated" && !activeKeys.contains(key) }
            if (keysToDelete.isNotEmpty()) {
                val updates = keysToDelete.associate { "usageByDate.$it" to FieldValue.delete() }
                childRef.update(updates).await()
            }
        } catch (e: Exception) { Log.e(tag, "Error flushing old data", e) }
    }

    private fun getAppName(packageName: String): String {
        val pm = applicationContext.packageManager
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun getCategory(packageName: String): Int {
        return try {
            applicationContext.packageManager.getApplicationInfo(packageName, 0).category
        } catch (_: Exception) { -1 }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), applicationContext.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
