package com.example.chat_compose.push

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Khi token thay đổi thì cập nhật lại lên Firestore
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val data = mapOf("fcmToken" to token)
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .set(data, SetOptions.merge())
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // =============================
        // FIX: Đồng bộ luồng "incoming" giữa FCM -> HomeScreen
        // - HomeScreen đang nghe Broadcast ACTION_NEW_MESSAGE để tăng unread + snackbar
        // - Trước đây service chỉ show notification, KHÔNG broadcast => Home không cập nhật
        // =============================

        val data = remoteMessage.data

        // Ưu tiên đọc từ data payload (đúng cho FCM data message)
        val partnerId = data["partnerId"]
            ?: data["fromId"]
            ?: ""

        val fromName = data["fromName"]
            ?: data["senderName"]
            ?: data["title"]
            ?: ""

        val body = data["body"]
            ?: data["text"]
            ?: remoteMessage.notification?.body
            ?: ""

        val title = data["title"]
            ?: fromName.ifBlank { "Tin nhắn mới" }

        if (partnerId.isBlank() && body.isBlank()) return

        // 1) Broadcast nội bộ để HomeScreen (và/hoặc UI khác) cập nhật unread/snackbar realtime
        sendNewMessageBroadcast(
            fromId = partnerId,
            fromName = fromName,
            text = body
        )

        // 2) Show notification (để background vẫn thấy thông báo)
        // NOTE: Nếu bạn muốn "foreground chỉ snackbar, không notification",
        // bạn có thể bổ sung cờ isForeground trong Application rồi check tại đây.
        NotificationHelper.ensureChannel(applicationContext)
        NotificationHelper.showMessage(
            context = applicationContext,
            partnerId = partnerId,
            title = title,
            body = body
        )
    }

    private fun sendNewMessageBroadcast(
        fromId: String,
        fromName: String,
        text: String
    ) {
        if (fromId.isBlank()) return

        val intent = android.content.Intent(PushActions.ACTION_NEW_MESSAGE).apply {
            // Giới hạn broadcast trong app (tránh app khác nghe lén)
            `package` = packageName
            putExtra(PushActions.EXTRA_FROM_ID, fromId)
            putExtra(PushActions.EXTRA_FROM_NAME, fromName)
            putExtra(PushActions.EXTRA_TEXT, text)
        }

        sendBroadcast(intent)
    }
}
