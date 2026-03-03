package com.sigmacoders.aichildmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObject
import com.google.firebase.ktx.Firebase
import com.sigmacoders.aichildmonitor.adapter.ChildrenAdapter
import com.sigmacoders.aichildmonitor.databinding.ActivityMainBinding
import com.sigmacoders.aichildmonitor.model.Child
import com.sigmacoders.aichildmonitor.utils.NotificationHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tag = "MainActivity"
    private val childrenList = mutableListOf<Child>()
    private lateinit var childrenAdapter: ChildrenAdapter
    
    private lateinit var notificationHelper: NotificationHelper
    private val activeChildListeners = mutableMapOf<String, ListenerRegistration>()
    private val lastProcessedTimestamp = mutableMapOf<String, Long>()
    private val sessionStartTime = System.currentTimeMillis()

    // Permission launcher for Android 13+
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notifications are disabled. You won't receive safety alerts.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notificationHelper = NotificationHelper(this)
        checkNotificationPermission()

        val auth = Firebase.auth
        val userId = auth.currentUser?.uid

        if (userId == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setupRecyclerView()
        setupClickListeners(userId)
        fetchChildren(userId)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupRecyclerView() {
        childrenAdapter = ChildrenAdapter(childrenList) { child ->
            val intent = Intent(this, ChildDashboardActivity::class.java)
            intent.putExtra("PARENT_ID", child.parentId)
            intent.putExtra("CHILD_ID", child.id)
            startActivity(intent)
        }
        binding.childrenRecyclerView?.adapter = childrenAdapter
        binding.childrenRecyclerView?.layoutManager = LinearLayoutManager(this)
    }

    private fun setupClickListeners(userId: String) {
        binding.logoutButton.setOnClickListener {
            Firebase.auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        binding.addChildButton.setOnClickListener {
            generateAndSavePairingKey(userId)
        }
    }

    private fun generateAndSavePairingKey(userId: String) {
        val pairingKey = (1000..9999).random().toString()
        val db = Firebase.firestore

        val pairingRef = db.collection("pairingKeys").document(pairingKey)
        val pairingData = hashMapOf("parentId" to userId)

        pairingRef.set(pairingData)
            .addOnSuccessListener {
                Log.d(tag, "Pairing key created: $pairingKey")
                showPairingKeyDialog(pairingKey)
            }
            .addOnFailureListener { e ->
                Log.w(tag, "Error generating pairing key", e)
                Toast.makeText(this, "Failed to generate pairing key.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showPairingKeyDialog(pairingKey: String) {
        AlertDialog.Builder(this)
            .setTitle("Your Pairing Key")
            .setMessage("Share this key with your child to pair their device:\n\n$pairingKey")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun fetchChildren(userId: String) {
        val db = Firebase.firestore
        db.collection("users").document(userId).collection("children")
            .whereEqualTo("isPaired", true)
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.w(tag, "Listen failed.", e)
                    return@addSnapshotListener
                }

                childrenList.clear()
                snapshots?.forEach { doc ->
                    val child = doc.toObject<Child>().copy(
                        id = doc.id,
                        parentId = userId
                    )
                    childrenList.add(child)
                    
                    // Start listening to the child document for new video logs
                    setupChildDocumentListener(child)
                }
                childrenAdapter.notifyDataSetChanged()
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setupChildDocumentListener(child: Child) {
        if (activeChildListeners.containsKey(child.id)) return

        val db = Firebase.firestore
        val childRef = db.collection("users").document(child.parentId).collection("children").document(child.id)

        val listener = childRef.addSnapshotListener { snapshot, e ->
            if (e != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener

            val videoLogs = snapshot.get("videoLogs") as? Map<String, Any> ?: return@addSnapshotListener
            
            var latestUnsafeTitle: String? = null
            var latestTimestamp = lastProcessedTimestamp[child.id] ?: sessionStartTime

            videoLogs.forEach { (key, value) ->
                val logData = value as? Map<String, Any> ?: return@forEach
                val ts = logData["timestamp"] as? Long ?: 0L
                val safety = logData["safety"] as? String ?: ""
                
                if (ts > latestTimestamp && safety == "unsafe") {
                    latestTimestamp = ts
                    latestUnsafeTitle = logData["title"] as? String
                }
            }

            if (latestUnsafeTitle != null) {
                lastProcessedTimestamp[child.id] = latestTimestamp
                notificationHelper.showUnsafeContentAlert(child.name, latestUnsafeTitle!!)
            }
        }

        activeChildListeners[child.id] = listener
    }

    override fun onDestroy() {
        super.onDestroy()
        activeChildListeners.values.forEach { it.remove() }
        activeChildListeners.clear()
    }
}
