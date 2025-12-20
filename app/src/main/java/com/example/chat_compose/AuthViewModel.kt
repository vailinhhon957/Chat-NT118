package com.example.chat_compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat_compose.data.CallRepository
import com.example.chat_compose.data.ChatRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthViewModel(
    private val repo: ChatRepository = ChatRepository()
) : ViewModel() {

    private val callRepo = CallRepository()

    var isLoading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    fun isLoggedIn(): Boolean = repo.currentUserId() != null

    // --- 1. ĐĂNG KÝ (EMAIL/PASS) ---
    fun register(email: String, pass: String, name: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            error = null
            val res = repo.register(email, pass, name)

            if (res.isSuccess) {
                // Đăng ký xong tự động login -> Xử lý hậu kỳ (Check ban, online...)
                val uid = repo.currentUserId()
                if (uid != null) {
                    processAfterLogin(uid, onSuccess)
                } else {
                    isLoading = false
                    onSuccess()
                }
            } else {
                isLoading = false
                error = res.exceptionOrNull()?.message
            }
        }
    }

    // --- 2. ĐĂNG NHẬP (EMAIL/PASS) ---
    fun login(email: String, pass: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            error = null

            val res = repo.login(email, pass)

            if (res.isSuccess) {
                val uid = repo.currentUserId()
                if (uid != null) {
                    processAfterLogin(uid, onSuccess)
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

    // --- 3. ĐĂNG NHẬP GOOGLE (MỚI THÊM) ---
    fun loginWithGoogle(idToken: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            isLoading = true
            error = null

            // Gọi Repo để xác thực với Firebase bằng idToken
            val res = repo.loginWithGoogle(idToken)

            if (res.isSuccess) {
                val uid = repo.currentUserId()
                if (uid != null) {
                    // Kiểm tra xem user này đã có thông tin trong Firestore chưa (nếu mới tạo lần đầu)
                    // Hàm này bạn cần đảm bảo có trong Repo, hoặc xử lý tạo user mặc định ở đây
                    repo.createFirestoreUserIfNeed(uid, res.getOrNull()?.email, res.getOrNull()?.displayName)

                    // Sau đó xử lý check ban như bình thường
                    processAfterLogin(uid, onSuccess)
                } else {
                    isLoading = false
                    error = "Lỗi xác thực Google: Không có UID"
                }
            } else {
                isLoading = false
                error = res.exceptionOrNull()?.message ?: "Đăng nhập Google thất bại"
            }
        }
    }

    // --- 4. HÀM DÙNG CHUNG: XỬ LÝ SAU KHI LOGIN THÀNH CÔNG ---
    // (Gồm: Check Ban -> Update Online -> Success)
    private suspend fun processAfterLogin(uid: String, onSuccess: () -> Unit) {
        // A. Kiểm tra khóa tài khoản
        val banDate = callRepo.checkBanStatus(uid)

        if (banDate != null) {
            // === BỊ KHÓA ===
            performLogout(uid) // Logout ngay để xóa token
            isLoading = false
            error = "Tài khoản bị khóa đến $banDate do vi phạm chính sách."
        } else {
            // === SẠCH ===
            try {
                repo.updateOnlineStatus(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isLoading = false
            onSuccess()
        }
    }

    fun resetPassword(email: String, onDone: (Boolean) -> Unit) {
        isLoading = true
        error = null
        repo.resetPassword(email) { result ->
            isLoading = false
            result.onSuccess { onDone(true) }
                .onFailure { e ->
                    error = e.message ?: "Gửi email thất bại"
                    onDone(false)
                }
        }
    }

    // --- 5. LOGOUT (GIỮ NGUYÊN LOGIC CŨ CỦA BẠN - RẤT TỐT) ---
    fun logout() {
        viewModelScope.launch {
            val uid = repo.currentUserId()
            if (uid != null) {
                performLogout(uid)
            } else {
                repo.logout()
            }
        }
    }

    private suspend fun performLogout(uid: String) {
        try {
            runCatching { repo.updateOnlineStatus(false) }

            // Xóa FCM Token để chặn thông báo
            FirebaseFirestore.getInstance().collection("users")
                .document(uid)
                .update("fcmToken", "")
                .await()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            repo.logout()
        }
    }
}