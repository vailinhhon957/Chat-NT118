package com.example.chat_compose.model

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat_compose.data.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repo = ChatRepository()

    // user hiện tại
    private val _currentUser = MutableStateFlow<ChatUser?>(null)
    val currentUser: StateFlow<ChatUser?> = _currentUser

    // ===== Avatar =====
    private val _isUpdatingAvatar = MutableStateFlow(false)
    val isUpdatingAvatar: StateFlow<Boolean> = _isUpdatingAvatar

    // ===== Profile extras: ngày sinh + giới tính =====
    private val _birthDateMillis = MutableStateFlow<Long?>(null)
    val birthDateMillis: StateFlow<Long?> = _birthDateMillis

    private val _gender = MutableStateFlow("Khác")
    val gender: StateFlow<String> = _gender

    private val _isSavingProfile = MutableStateFlow(false)
    val isSavingProfile: StateFlow<Boolean> = _isSavingProfile

    // ===== Change password =====
    private val _isChangingPassword = MutableStateFlow(false)
    val isChangingPassword: StateFlow<Boolean> = _isChangingPassword

    // thông báo lỗi / thành công
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _success = MutableStateFlow<String?>(null)
    val success: StateFlow<String?> = _success

    init {
        val uid = repo.currentUserId()
        if (uid != null) {
            // listen user (tên, email, avatar...)
            viewModelScope.launch {
                repo.listenUser(uid).collect { user ->
                    _currentUser.value = user
                }
            }
            // load ngày sinh + giới tính (đọc 1 lần)
            viewModelScope.launch {
                runCatching {
                    val extras = repo.getProfileExtras(uid)
                    _birthDateMillis.value = extras.birthDateMillis
                    _gender.value = extras.gender ?: "Khác"
                }.onFailure { e ->
                    // không bắt buộc phải có, nên best-effort
                    _error.value = e.message
                }
            }
        }
    }

    fun clearMessages() {
        _error.value = null
        _success.value = null
    }

    fun setBirthDate(millis: Long?) {
        _birthDateMillis.value = millis
    }

    fun setGender(value: String) {
        _gender.value = value
    }

    fun saveProfile() {
        viewModelScope.launch {
            try {
                _isSavingProfile.value = true
                _error.value = null
                _success.value = null

                repo.updateProfileExtras(
                    birthDateMillis = _birthDateMillis.value,
                    gender = _gender.value
                )

                _success.value = "Đã lưu thông tin cá nhân"
            } catch (e: Exception) {
                _error.value = e.message ?: "Lỗi lưu thông tin"
            } finally {
                _isSavingProfile.value = false
            }
        }
    }

    fun updateAvatar(uri: Uri) {
        viewModelScope.launch {
            try {
                _isUpdatingAvatar.value = true
                _error.value = null
                _success.value = null

                val ctx = getApplication<Application>().applicationContext
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes()
                } ?: throw IllegalStateException("Không đọc được ảnh")

                repo.updateAvatar(bytes)
                _success.value = "Đã cập nhật avatar"
            } catch (e: Exception) {
                _error.value = e.message ?: "Lỗi cập nhật avatar"
            } finally {
                _isUpdatingAvatar.value = false
            }
        }
    }

    fun changePassword(currentPassword: String, newPassword: String) {
        viewModelScope.launch {
            try {
                _isChangingPassword.value = true
                _error.value = null
                _success.value = null

                repo.changePassword(currentPassword, newPassword)

                _success.value = "Đổi mật khẩu thành công"
            } catch (e: Exception) {
                _error.value = e.message ?: "Đổi mật khẩu thất bại"
            } finally {
                _isChangingPassword.value = false
            }
        }
    }
}

data class ProfileExtras(
    val birthDateMillis: Long?,
    val gender: String?
)
