package com.example.chat_compose.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.chat_compose.MainActivity
import com.example.chat_compose.R
import kotlin.random.Random

object NotificationHelper {

    const val CHANNEL_ID = "chat_channel_id"
    const val CHANNEL_NAME = "Chat Notifications"
    const val EXTRA_OPEN_CHAT_PARTNER_ID = "open_chat_partner_id"

    // === THÊM BIẾN NÀY ===
    // Biến này lưu ID người đang chat hiện tại. Nếu đang chat với ai thì ID người đó nằm ở đây.
    var currentChatPartnerId: String? = null
    // ====================

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Thông báo tin nhắn mới" }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    fun showMessage(
        context: Context,
        partnerId: String,
        title: String,
        body: String,
        largeIcon: Bitmap? = null
    ) {
        // === THÊM ĐOẠN CHECK NÀY ===
        // Nếu người dùng đang mở màn hình chat với đúng người này -> KHÔNG HIỆN THÔNG BÁO
        if (currentChatPartnerId == partnerId) {
            return
        }
        // ============================

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CHAT_PARTNER_ID, partnerId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            partnerId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (largeIcon != null) builder.setLargeIcon(largeIcon)

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Dùng hashCode để gom nhóm tin nhắn của cùng 1 người
            NotificationManagerCompat.from(context).notify(partnerId.hashCode(), builder.build())
        }
    }
}