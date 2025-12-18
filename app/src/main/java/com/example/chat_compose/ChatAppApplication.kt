package com.example.chat_compose

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.chat_compose.data.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ChatAppApplication : Application() {

    private val appScope = CoroutineScope(Dispatchers.IO)
    private val repo by lazy { ChatRepository() }

    override fun onCreate() {
        super.onCreate()

        // lắng nghe app vào foreground / background
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // toàn app vào foreground -> online
                    appScope.launch {
                        repo.updateOnlineStatus(true)
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    // toàn app vào background -> offline
                    appScope.launch {
                        repo.updateOnlineStatus(false)
                    }
                }
            }
        )
    }
}