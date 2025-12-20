package com.example.chat_compose.model
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

enum class MessageStatus {
    SENT,  // Đã gửi
    READ   // Đã xem
}
data class Message(
    val id: String = "",
    val fromId: String = "",
    val toId: String = "",
    val text: String = "",
    val replyToId: String? = null,
    val replyPreview: String? = null,
    val reactions: Map<String, String> = emptyMap(),
    val createdAt: Date? = null,
    // mới thêm – để bubble biết là message này có ảnh
    val imageUrl: String? = null,
    val imageBase64: String? = null,
    val imageBytes: ByteArray? = null,
    val audioUrl: String? = null,        // Link file ghi âm
    var translatedText: String? = null,
    val status: MessageStatus = MessageStatus.SENT,
    val expiryTime: Long? = null
)