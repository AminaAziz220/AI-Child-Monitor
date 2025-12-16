package com.sigmacoders.aichildmonitor

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.toObject
import com.google.firebase.ktx.Firebase
import com.sigmacoders.aichildmonitor.adapter.ChildrenAdapter
import com.sigmacoders.aichildmonitor.databinding.ActivityMainBinding
import com.sigmacoders.aichildmonitor.model.Child

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val tag = "MainActivity"
    private val childrenList = mutableListOf<Child>()
    private lateinit var childrenAdapter: ChildrenAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

    private fun setupRecyclerView() {
        childrenAdapter = ChildrenAdapter(childrenList) { child ->
            val intent = Intent(this, ChildDashboardActivity::class.java)
            intent.putExtra("CHILD_ID", child.id)
            startActivity(intent)
        }
        binding.childrenRecyclerView.adapter = childrenAdapter
        binding.childrenRecyclerView.layoutManager = LinearLayoutManager(this)
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

        // Create a temporary document in a public collection for pairing
        val pairingRef = db.collection("pairingKeys").document(pairingKey)
        val pairingData = hashMapOf("parentId" to userId)

        pairingRef.set(pairingData)
            .addOnSuccessListener {
                Log.d(tag, "Pairing key created in public collection: $pairingKey")
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
                snapshots?.let {
                    for (doc in it) {
                        val child = doc.toObject<Child>().copy(id = doc.id)
                        childrenList.add(child)
                    }
                }
                childrenAdapter.notifyDataSetChanged()
            }
    }
}