package com.example.chat_compose

import android.net.Uri

sealed class Screen(val route: String) {
    object Splash : Screen("splash")
    object Login : Screen("login")
    object Register : Screen("register")
    object Home : Screen("home")

    object Chat : Screen("chat/{partnerId}") {
        fun createRoute(partnerId: String) = "chat/$partnerId"
    }

    // callType: "audio" | "video"
    data object Call : Screen("call/{partnerId}/{partnerName}/{isCaller}/{callId}/{callType}") {
        fun createRoute(
            partnerId: String,
            partnerName: String,
            isCaller: Boolean,
            callId: String,
            callType: String
        ): String {
            val encodedName = Uri.encode(partnerName)
            val safeType = if (callType == "video") "video" else "audio"
            return "call/$partnerId/$encodedName/$isCaller/$callId/$safeType"
        }
    }

    object Settings : Screen("settings")
}
