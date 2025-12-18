package com.example.chat_compose.model

import java.util.Date

data class ChatUser(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "",
    val photoUrl: String? = null,
    val isOnline: Boolean = false,
    val lastSeen: Date? = null,
    val avatarBytes: ByteArray? = null
)
