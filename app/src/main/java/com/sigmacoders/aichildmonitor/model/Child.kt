package com.sigmacoders.aichildmonitor.model

import com.google.firebase.firestore.Exclude

data class Child(
    @get:Exclude var id: String = "", // Exclude from Firestore serialization
    var name: String = "",
    var isPaired: Boolean = false,
    var parentId: String = "" // Add parentId field
)
