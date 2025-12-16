package com.sigmacoders.aichildmonitor

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sigmacoders.aichildmonitor.databinding.ActivityChildHomeBinding
import com.sigmacoders.aichildmonitor.uploader.UsageStatsUploader

class ChildHomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildHomeBinding
    private lateinit var usageStatsUploader: UsageStatsUploader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usageStatsUploader = UsageStatsUploader(this)

        val parentId = intent.getStringExtra("PARENT_ID")
        val childId = intent.getStringExtra("CHILD_ID")

        if (parentId != null && childId != null) {
            if (usageStatsUploader.hasUsageStatsPermission()) {
                usageStatsUploader.uploadUsageStats(parentId, childId)
            } else {
                // If permission is not granted, this will take the user to the settings screen.
                // The upload will happen the next time the app is opened after permission is granted.
                usageStatsUploader.requestUsageStatsPermission()
            }
        }
    }
}