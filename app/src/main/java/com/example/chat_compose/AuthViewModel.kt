package com.example.chat_compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat_compose.data.CallRepository // Import CallRepository để check ban
import com.example.chat_compose.data.ChatRepository
import kotlinx.coroutines.launch

class AuthViewModel(
    private val repo: ChatRepository = ChatRepository()
) : ViewModel() {

    // Thêm CallRepository để dùng hàm checkBanStatus
    private val callRepo = CallRepository()

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

            // Register xong thường chưa bị ban, nhưng an toàn thì vẫn check sau này
            if (res.isSuccess) {
                runCatching { repo.updateOnlineStatus(true) }
                isLoading = false
                onSuccess()
            } else {
                isLoading = false
                error = res.exceptionOrNull()?.message
            }
        }
    }

    // === CHỈNH SỬA CHÍNH Ở HÀM LOGIN ===
    fun login(email: String, pass: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            error = null

            // 1. Đăng nhập vào Firebase Auth
            val res = repo.login(email, pass)

            if (res.isSuccess) {
                val uid = repo.currentUserId()
                if (uid != null) {
                    // 2. KIỂM TRA TRẠNG THÁI KHÓA TÀI KHOẢN
                    // Gọi hàm checkBanStatus từ CallRepository
                    val banDate = callRepo.checkBanStatus(uid)

                    if (banDate != null) {
                        // === TRƯỜNG HỢP BỊ KHÓA ===
                        // Logout ngay lập tức để không lưu session
                        repo.logout()

                        isLoading = false
                        error = "Tài khoản bị khóa đến $banDate do vi phạm chính sách."
                    } else {
                        // === TRƯỜNG HỢP SẠCH ===
                        // Update online status
                        runCatching {
                            repo.updateOnlineStatus(true)
                        }
                        isLoading = false
                        onSuccess()
                    }
                } else {
                    isLoading = false
                    error = "Không tìm thấy User ID"
                }
            } else {
                isLoading = false
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
            runCatching { repo.updateOnlineStatus(false) }
            repo.logout()
        }
    }
}