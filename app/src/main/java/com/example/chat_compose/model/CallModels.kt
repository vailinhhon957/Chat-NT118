package com.example.chat_compose.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId

data class CallDoc(
    @DocumentId val id: String = "",
    val from: String = "",
    val to: String = "",
    val status: String = "ringing", // ringing | accepted | ended
    val createdAt: Timestamp? = null,

    // WebRTC (tuỳ bạn dùng hay chưa)
    val offerSdp: String? = null,
    val answerSdp: String? = null
)