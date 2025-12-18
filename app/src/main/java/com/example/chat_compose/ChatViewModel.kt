package com.example.chat_compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat_compose.data.ChatRepository
import com.example.chat_compose.model.ChatUser
import com.example.chat_compose.model.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.example.chat_compose.data.AudioRecorder
import java.io.File
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.delay
class ChatViewModel(
    private val repo: ChatRepository = ChatRepository()
) : ViewModel() {

    // ============= DANH SÁCH BẠN BÈ (HomeScreen dùng) =============
    var friends by mutableStateOf<List<ChatUser>>(emptyList())
        private set

    private var friendsJob: Job? = null

    fun listenFriends() {
        friendsJob?.cancel()
        friendsJob = viewModelScope.launch {
            val myUid = repo.currentUserId() ?: return@launch
            repo.listenFriends(myUid).collect { list ->
                friends = list
            }
        }
    }

    // ================== STATE CHAT ==================
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set

    var partnerUser by mutableStateOf<ChatUser?>(null)
        private set

    var replyingTo by mutableStateOf<Message?>(null)
        private set

    private var msgJob: Job? = null
    private var partnerJob: Job? = null

    // ===== ONLINE STATUS (MainActivity dùng) =====
    suspend fun updateOnlineStatus(isOnline: Boolean) {
        repo.updateOnlineStatus(isOnline)
    }

    // ===== LOAD / LISTEN PARTNER =====
    fun listenPartner(uid: String) {
        partnerJob?.cancel()
        partnerJob = viewModelScope.launch {
            repo.listenUser(uid).collect { user ->
                partnerUser = user
            }
        }
    }

    // ===== LISTEN MESSAGE VỚI 1 NGƯỜI =====
    fun listenMessages(partnerId: String) {
        msgJob?.cancel()
        msgJob = viewModelScope.launch {
            repo.listenMessages(partnerId).collect { list ->
                messages = list
            }
        }
    }

    // ===== REPLY =====
    fun startReply(msg: Message) {
        replyingTo = msg
    }

    fun cancelReply() {
        replyingTo = null
    }

    // ===== GỬI TIN NHẮN (QUAN TRỌNG: Đã sửa logic AI ở đây) =====
    fun sendMessage(partnerId: String, text: String) {
        if (text.isBlank()) return
        val reply = replyingTo

        viewModelScope.launch {
            // [SỬA LẠI] Kiểm tra ID người nhận
            if (partnerId == ChatRepository.AI_BOT_ID) {
                // Nếu là Bot -> Gọi hàm AI (Gửi tin User -> Gọi Gemini -> Gửi tin Bot)
                repo.sendAiMessage(text = text.trim(), replyingTo = reply)
            } else {
                // Nếu là người thường -> Gửi bình thường
                repo.sendMessage(partnerId, text.trim(), replyingTo = reply)
            }
        }
        replyingTo = null
    }

    // ===== GỬI ẢNH (imageUrl) =====
    fun sendImageMessage(toId: String, imageBytes: ByteArray) {
        val reply = replyingTo
        viewModelScope.launch {
            repo.sendImageMessage(
                partnerId = toId,
                bytes = imageBytes,
                text = "",
                replyingTo = reply
            )
            replyingTo = null
        }
    }

    // ===== REACT EMOJI =====
    fun toggleReaction(partnerId: String, messageId: String, emoji: String) {
        viewModelScope.launch {
            repo.toggleReaction(
                partnerId = partnerId,
                msgId = messageId,
                emoji = emoji
            )
        }
    }
    var isRecording by mutableStateOf(false)
        private set

    private var audioRecorder: AudioRecorder? = null

    // Map lưu các tin nhắn đã dịch: key=msgId, value=translatedText
    // Dùng Map để UI tự update khi có bản dịch mới
    val translatedMessages = mutableStateMapOf<String, String>()

    fun initRecorder(context: android.content.Context) {
        if (audioRecorder == null) {
            audioRecorder = AudioRecorder(context)
        }
    }

    fun startRecording() {
        isRecording = true
        audioRecorder?.startRecording()
    }

    fun stopAndSendRecording(partnerId: String) {
        isRecording = false
        val file = audioRecorder?.stopRecording()
        if (file != null && partnerId.isNotBlank()) {
            viewModelScope.launch {
                repo.sendVoiceMessage(partnerId, file)
            }
        }
    }

    // Hàm gọi AI dịch
    fun translateMessage(message: Message) {
        viewModelScope.launch {
            // Hiện loading giả hoặc text tạm
            translatedMessages[message.id] = "Đang dịch..."
            val result = repo.translateText(message.text)
            translatedMessages[message.id] = result
        }
    }
    var isPartnerTyping by mutableStateOf(false)
        private set

    private var typingJob: Job? = null

    // Hàm gọi khi vào màn hình chat
    fun observeTyping(partnerId: String) {
        viewModelScope.launch {
            repo.listenPartnerTyping(partnerId).collect {
                isPartnerTyping = it
            }
        }
    }

    // Hàm gọi mỗi khi nhập liệu vào TextField
    fun onInputTextChanged(partnerId: String, newText: String) {
        // Gửi lệnh: Đang gõ
        if (typingJob == null) {
            viewModelScope.launch { repo.setTyping(partnerId, true) }
        }

        // Hủy bộ đếm cũ, tạo bộ đếm mới
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(2000) // Sau 2 giây không gõ gì -> Tắt typing
            repo.setTyping(partnerId, false)
            typingJob = null
        }
    }

    // Đánh dấu đã đọc
    fun markRead(partnerId: String) {
        viewModelScope.launch {
            repo.markMessagesAsRead(partnerId)
        }
    }
}