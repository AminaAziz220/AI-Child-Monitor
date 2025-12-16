package com.sigmacoders.aichildmonitor

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.sigmacoders.aichildmonitor.databinding.ActivityChildLoginBinding

class ChildLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChildLoginBinding
    private val tag = "ChildLoginActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChildLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Disable button until we are authenticated
        binding.pairButton.isEnabled = false
        binding.pairButton.text = "Authenticating..."

        // Sign in anonymously to get a temporary ID for pairing & data uploads
        if (Firebase.auth.currentUser == null) {
            Firebase.auth.signInAnonymously()
                .addOnSuccessListener {
                    Log.d(tag, "Anonymous sign-in successful.")
                    binding.pairButton.isEnabled = true
                    binding.pairButton.text = "Pair Device"
                }
                .addOnFailureListener { e ->
                    Log.e(tag, "Anonymous sign-in failed.", e)
                    Toast.makeText(this, "Authentication failed. Check connection.", Toast.LENGTH_LONG).show()
                }
        } else {
            // Already authenticated from a previous session
            binding.pairButton.isEnabled = true
            binding.pairButton.text = "Pair Device"
        }

        binding.pairButton.setOnClickListener {
            val pairingKey = binding.pairingKeyEditText.text.toString().trim()
            if (pairingKey.length == 4) {
                pairDevice(pairingKey)
            } else {
                Toast.makeText(this, "Please enter a valid 4-digit key", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun pairDevice(pairingKey: String) {
        val db = Firebase.firestore
        val pairingKeyRef = db.collection("pairingKeys").document(pairingKey)

        pairingKeyRef.get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    Toast.makeText(this, "Invalid pairing key.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                val parentId = document.getString("parentId")
                if (parentId != null) {
                    showNameDialog(parentId, pairingKeyRef)
                } else {
                    Toast.makeText(this, "Invalid pairing data.", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                Log.w(tag, "Error checking pairing key", e)
                Toast.makeText(this, "Failed to pair device: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showNameDialog(parentId: String, pairingKeyRef: com.google.firebase.firestore.DocumentReference) {
        val editText = EditText(this).apply {
            hint = "Enter your name"
        }

        AlertDialog.Builder(this)
            .setTitle("Enter Your Name")
            .setView(editText)
            .setCancelable(false)
            .setPositiveButton("Confirm") { dialog, _ ->
                val childName = editText.text.toString().trim()
                if (childName.isNotEmpty()) {
                    createChildRecord(parentId, childName, pairingKeyRef)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createChildRecord(parentId: String, childName: String, pairingKeyRef: com.google.firebase.firestore.DocumentReference) {
        val db = Firebase.firestore
        val childData = hashMapOf(
            "name" to childName,
            "isPaired" to true,
            "parentId" to parentId
        )

        db.collection("users").document(parentId).collection("children")
            .add(childData)
            .addOnSuccessListener { childDocRef ->
                // Pairing is complete, now delete the temporary key
                pairingKeyRef.delete()

                Toast.makeText(this, "Device paired successfully!", Toast.LENGTH_SHORT).show()

                // Navigate to the new ChildHomeActivity
                val intent = Intent(this, ChildHomeActivity::class.java)
                intent.putExtra("PARENT_ID", parentId)
                intent.putExtra("CHILD_ID", childDocRef.id)
                startActivity(intent)
                finishAffinity()
            }
            .addOnFailureListener { e ->
                Log.w(tag, "Failed to create child record", e)
                Toast.makeText(this, "Failed to finalize pairing: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }
}
