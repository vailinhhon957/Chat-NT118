package com.example.chat_compose.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class NewMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PushActions.ACTION_NEW_MESSAGE) return

        val fromId = intent.getStringExtra(PushActions.EXTRA_FROM_ID).orEmpty()
        val fromName = intent.getStringExtra(PushActions.EXTRA_FROM_NAME).orEmpty()
        val text = intent.getStringExtra(PushActions.EXTRA_TEXT).orEmpty()

        Log.d("NEW_MESSAGE_BR", "from=$fromId name=$fromName text=$text")

        // Nếu bạn muốn: lưu badge, cập nhật local DB, v.v.
        // UI Compose nên dùng dynamic receiver (ở ChatScreen/HomeScreen) để refresh list realtime.
    }
}