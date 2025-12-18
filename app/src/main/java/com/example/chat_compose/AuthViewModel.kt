package com.example.chat_compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat_compose.data.ChatRepository
import kotlinx.coroutines.launch

class AuthViewModel(
    private val repo: ChatRepository = ChatRepository()
) : ViewModel() {

    var isLoading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun isLoggedIn(): Boolean = repo.currentUserId() != null

    fun register(email: String, pass: String, name: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            error = null
            val res = repo.register(email, pass, name)
            isLoading = false

            if (res.isSuccess) {
                // best-effort: không để lỗi online status phá flow
                runCatching { repo.updateOnlineStatus(true) }


                onSuccess()
            } else {
                error = res.exceptionOrNull()?.message
            }
        }
    }

    fun login(email: String, pass: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            error = null

            val res = repo.login(email, pass)

            isLoading = false

            if (res.isSuccess) {
                // update online status chỉ khi login OK, và không được làm crash luồng
                runCatching {
                    repo.updateOnlineStatus(true)
                }
                onSuccess()
            } else {
                error = res.exceptionOrNull()?.message
            }
        }
    }
    fun resetPassword(email: String, onDone: (Boolean) -> Unit) {
        isLoading = true
        error = null
        repo.resetPassword(email) { result ->
            isLoading = false
            result.onSuccess {
                onDone(true)
            }.onFailure { e ->
                error = e.message ?: "Gửi email đặt lại mật khẩu thất bại"
                onDone(false)
            }
        }
    }
    fun logout() {
        viewModelScope.launch {
            // best-effort: logout vẫn phải chạy kể cả updateOnlineStatus lỗi
            runCatching { repo.updateOnlineStatus(false) }
            repo.logout()
        }
    }
}
