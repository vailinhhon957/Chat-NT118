package com.example.chat_compose.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.chat_compose.MainActivity
import com.example.chat_compose.R
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

        val data = remoteMessage.data
        if (data.isEmpty()) return

        // Kiểm tra loại thông báo: Cuộc gọi hay Tin nhắn
        val type = data["type"]

        if (type == "call") {
            handleIncomingCall(data)
        } else {
            handleChatMessage(data, remoteMessage.notification)
        }
    }

    // ==========================================
    // XỬ LÝ CUỘC GỌI (Full Screen Intent)
    // ==========================================
    private fun handleIncomingCall(data: Map<String, String>) {
        val callId = data["callId"] ?: return
        val callerName = data["callerName"] ?: "Ai đó"
        val callType = data["callType"] ?: "audio"
        val callerId = data["callerId"] ?: ""

        // 1. Intent mở MainActivity (kèm cờ đánh thức)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("is_incoming_call", true)
            putExtra("call_id", callId)
            putExtra("caller_name", callerName)
            putExtra("call_type", callType)
            putExtra("caller_id", callerId)
        }

        // 2. PendingIntent (Bắt buộc FLAG_IMMUTABLE trên Android 12+)
        val pendingIntent = PendingIntent.getActivity(
            this,
            callId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Channel độ ưu tiên MAX (có âm thanh, rung)
        val channelId = "call_channel_high_priority"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Cuộc gọi đến",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo cuộc gọi đến toàn màn hình"
                setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI, null)
                enableVibration(true)
                // Quan trọng: Cho phép hiển thị trên màn hình khóa
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }

        // 4. Build Notification Full Screen
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Thay bằng icon app của bạn
            .setContentTitle("Cuộc gọi đến từ $callerName")
            .setContentText("Chạm để trả lời...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(pendingIntent, true) // Dòng này giúp bật màn hình
            .setOngoing(true) // Không cho quẹt tắt khi đang đổ chuông
            .build()

        notificationManager.notify(callId.hashCode(), notification)
    }

    // ==========================================
    // XỬ LÝ TIN NHẮN CHAT (Broadcast + Notification)
    // ==========================================
    private fun handleChatMessage(data: Map<String, String>, notification: RemoteMessage.Notification?) {
        // Lấy dữ liệu an toàn
        val partnerId = data["partnerId"] ?: data["fromId"] ?: ""
        val fromName = data["fromName"] ?: data["senderName"] ?: data["title"] ?: ""
        val body = data["body"] ?: data["text"] ?: notification?.body ?: ""
        val title = data["title"] ?: fromName.ifBlank { "Tin nhắn mới" }

        if (partnerId.isBlank() && body.isBlank()) return

        // 1. Gửi Broadcast để cập nhật UI (Home/ChatList) ngay lập tức
        sendNewMessageBroadcast(partnerId, fromName, body)

        // 2. Hiển thị thông báo (NotificationHelper lo việc check đang chat hay không)
        NotificationHelper.ensureChannel(applicationContext)
        NotificationHelper.showMessage(
            context = applicationContext,
            partnerId = partnerId,
            title = title,
            body = body
        )
    }

    private fun sendNewMessageBroadcast(fromId: String, fromName: String, text: String) {
        val intent = Intent(PushActions.ACTION_NEW_MESSAGE).apply {
            `package` = packageName // Giới hạn broadcast trong app
            putExtra(PushActions.EXTRA_FROM_ID, fromId)
            putExtra(PushActions.EXTRA_FROM_NAME, fromName)
            putExtra(PushActions.EXTRA_TEXT, text)
        }
        sendBroadcast(intent)
    }
}