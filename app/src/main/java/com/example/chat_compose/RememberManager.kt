package com.example.chat_compose

import android.content.Context

class RememberManager(context: Context) {

    private val prefs = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)

    fun setRemember(value: Boolean) {
        prefs.edit().putBoolean("remember_login", value).apply()
    }

    fun isRemember(): Boolean {
        return prefs.getBoolean("remember_login", false)
    }
}