package com.example.chat_compose

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat_compose.data.AudioRecorder
import com.example.chat_compose.data.ChatRepository
import com.example.chat_compose.model.ChatUser
import com.example.chat_compose.model.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import com.example.chat_compose.data.GeminiAiRepository

class ChatViewModel(
    private val repo: ChatRepository = ChatRepository()
) : ViewModel() {

    // ============= 1. FRIEND LIST =============
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
    var isEphemeralMode by mutableStateOf(false) // Mặc định tắt
        private set
    fun toggleEphemeralMode() {
        isEphemeralMode = !isEphemeralMode
    }
    // ================== 2. CHAT STATE ==================
    var messages by mutableStateOf<List<Message>>(emptyList())
        private set

    var partnerUser by mutableStateOf<ChatUser?>(null)
        private set

    var replyingTo by mutableStateOf<Message?>(null)
        private set

    private var msgJob: Job? = null
    private var partnerJob: Job? = null

    suspend fun updateOnlineStatus(isOnline: Boolean) {
        repo.updateOnlineStatus(isOnline)
    }

    fun listenPartner(uid: String) {
        partnerJob?.cancel()
        partnerJob = viewModelScope.launch {
            repo.listenUser(uid).collect { user ->
                partnerUser = user
                maybeGenerateSmartRepliesIfNeeded(uid)
            }
        }
    }

    // ================== 3. SMART REPLY (Gemini) ==================
    val smartSuggestions = mutableStateListOf<String>()

    var isSmartReplyLoading by mutableStateOf(false)
        private set

    private var smartReplyJob: Job? = null
    private var lastSuggestedForMsgId: String? = null

    private fun maybeGenerateSmartRepliesIfNeeded(partnerId: String) {
        val myId = repo.currentUserId() ?: return
        val last = messages.lastOrNull() ?: return

        // chỉ gợi ý nếu tin nhắn cuối là của đối phương
        if (last.fromId == myId) return

        // tránh gọi lại cho cùng 1 message
        if (lastSuggestedForMsgId == last.id) return

        // bỏ qua ảnh/voice
        if (last.text.isBlank() || last.imageBytes != null || last.audioUrl != null) {
            lastSuggestedForMsgId = last.id
            smartSuggestions.clear()
            return
        }

        smartReplyJob?.cancel()
        smartReplyJob = viewModelScope.launch {
            delay(350)
            isSmartReplyLoading = true

            val suggestions = repo.generateSmartReplies(
                partnerId = partnerId,
                partnerName = partnerUser?.displayName,
                recentMessages = messages.takeLast(20)
            )

            smartSuggestions.clear()
            smartSuggestions.addAll(suggestions)
            lastSuggestedForMsgId = last.id
            isSmartReplyLoading = false
        }
    }

    fun listenMessages(partnerId: String) {
        msgJob?.cancel()
        msgJob = viewModelScope.launch {
            repo.listenMessages(partnerId).collect { list ->
                messages = list
                if (messages.isNotEmpty()) {
                    maybeGenerateSmartRepliesIfNeeded(partnerId)
                } else {
                    smartSuggestions.clear()
                }
            }
        }
    }

    // ================== 4. SEND & REPLY ==================
    fun startReply(msg: Message) { replyingTo = msg }
    fun cancelReply() { replyingTo = null }

    fun sendMessage(partnerId: String, text: String) {
        if (text.isBlank()) return
        val reply = replyingTo
        val sendingMode = isEphemeralMode

        viewModelScope.launch {
            if (partnerId == ChatRepository.AI_BOT_ID) {
                repo.sendAiMessage(text = text.trim(), replyingTo = reply)
            } else {
                repo.sendMessage(partnerId, text.trim(), replyingTo = reply, isEphemeral = sendingMode)
            }
        }
        replyingTo = null
    }

    fun sendImageMessage(toId: String, imageBytes: ByteArray) {
        val reply = replyingTo
        viewModelScope.launch {
            repo.sendImageMessage(partnerId = toId, bytes = imageBytes, text = "", replyingTo = reply)
            replyingTo = null
        }
    }
    fun deleteMessage(partnerId: String, msgId: String) {
        viewModelScope.launch {
            repo.deleteMessage(partnerId, msgId)
        }
    }

    fun toggleReaction(partnerId: String, messageId: String, emoji: String) {
        viewModelScope.launch {
            repo.toggleReaction(partnerId = partnerId, msgId = messageId, emoji = emoji)
        }
    }

    // ================== 5. ATTACHMENTS (MULTI IMAGE + VOICE) ==================
    val pendingImages = mutableStateListOf<ByteArray>()
    var pendingVoiceFile by mutableStateOf<File?>(null)
        private set

    fun addPendingImages(list: List<ByteArray>) {
        pendingImages.addAll(list)
    }

    fun removePendingImageAt(index: Int) {
        if (index in pendingImages.indices) pendingImages.removeAt(index)
    }

    fun clearPendingImages() {
        pendingImages.clear()
    }

    fun attachVoiceFile(file: File) {
        // xoá file cũ nếu có để tránh rác
        try { pendingVoiceFile?.delete() } catch (_: Exception) {}
        pendingVoiceFile = file
    }

    fun clearPendingVoice() {
        try { pendingVoiceFile?.delete() } catch (_: Exception) {}
        pendingVoiceFile = null
    }

    fun clearPendingAll() {
        clearPendingImages()
        clearPendingVoice()
    }

    /**
     * Gửi bundle: text (nếu có) + voice (nếu có) + nhiều ảnh (nếu có).
     * (Mỗi item sẽ là 1 message riêng; không đổi schema Firestore.)
     */
    fun sendBundle(partnerId: String, text: String) {
        val t = text.trim()
        val hasAnything = t.isNotBlank() || pendingVoiceFile != null || pendingImages.isNotEmpty()
        if (!hasAnything || partnerId.isBlank()) return

        val reply = replyingTo

        viewModelScope.launch {
            var usedReply = false
            fun takeReplyOnce(): Message? {
                return if (!usedReply) {
                    usedReply = true
                    reply
                } else null
            }

            // 1) text
            if (t.isNotBlank()) {
                val r = takeReplyOnce()
                if (partnerId == ChatRepository.AI_BOT_ID) {
                    repo.sendAiMessage(text = t, replyingTo = r)
                } else {
                    repo.sendMessage(partnerId, t, replyingTo = r)
                }
            }

            // 2) voice (đính kèm)
            pendingVoiceFile?.let { f ->
                val r = takeReplyOnce()
                // repo.sendVoiceMessage không nhận replyingTo, nên reply chỉ áp dụng cho phần text/ảnh
                // (Nếu bạn muốn reply cho voice thì phải mở rộng schema message)
                repo.sendVoiceMessage(partnerId, f)
            }

            // 3) nhiều ảnh
            if (pendingImages.isNotEmpty()) {
                pendingImages.forEachIndexed { idx, bytes ->
                    val r = if (idx == 0) takeReplyOnce() else null
                    repo.sendImageMessage(partnerId = partnerId, bytes = bytes, text = "", replyingTo = r)
                }
            }

            // clear state
            replyingTo = null
            smartSuggestions.clear()
            isSmartReplyLoading = false
            clearPendingAll()
        }
    }

    // ================== 6. AUDIO RECORD ==================
    var isRecording by mutableStateOf(false)
        private set

    private var audioRecorder: AudioRecorder? = null

    fun initRecorder(context: Context) {
        if (audioRecorder == null) {
            audioRecorder = AudioRecorder(context)
        }
    }

    fun startRecording() {
        isRecording = true
        audioRecorder?.startRecording()
    }

    /**
     * Dừng ghi âm và GẮN vào pending (không gửi ngay).
     * Người dùng sẽ bấm Send để gửi chung với ảnh/text.
     */
    fun stopAndAttachRecording() {
        isRecording = false
        val file = audioRecorder?.stopRecording()
        if (file != null) {
            attachVoiceFile(file)
        }
    }

    // (Giữ lại để không vỡ chỗ nào khác nếu còn gọi)
    fun stopAndSendRecording(partnerId: String) {
        isRecording = false
        val file = audioRecorder?.stopRecording()
        if (file != null && partnerId.isNotBlank()) {
            viewModelScope.launch {
                repo.sendVoiceMessage(partnerId, file)
            }
        }
    }

    // ================== 7. TRANSLATE ==================
    val translatedMessages = mutableStateMapOf<String, String>()

    fun translateMessage(message: Message) {
        viewModelScope.launch {
            translatedMessages[message.id] = "Đang dịch..."
            val result = repo.translateText(message.text)
            translatedMessages[message.id] = result
        }
    }

    // ================== 8. TYPING ==================
    var isPartnerTyping by mutableStateOf(false)
        private set

    private var typingJob: Job? = null

    fun observeTyping(partnerId: String) {
        viewModelScope.launch {
            repo.listenPartnerTyping(partnerId).collect {
                isPartnerTyping = it
            }
        }
    }

    fun onInputTextChanged(partnerId: String, newText: String) {
        if (newText.isNotBlank() && typingJob == null) {
            viewModelScope.launch { repo.setTyping(partnerId, true) }
        }

        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(2000)
            repo.setTyping(partnerId, false)
            typingJob = null
        }
    }

    fun markRead(partnerId: String) {
        viewModelScope.launch {
            repo.markMessagesAsRead(partnerId)
        }
    }

    var currentSentiment by mutableStateOf("NEUTRAL")
        private set
    private val aiRepo = GeminiAiRepository()
    fun analyzeLastMessageSentiment() {
        viewModelScope.launch {
            // Lấy tin nhắn cuối cùng
            val lastMsg = messages.lastOrNull() ?: return@launch

            // Chỉ phân tích nếu là tin nhắn của ĐỐI PHƯƠNG (Partner)
            // Vì mình muốn giao diện phản ứng theo cảm xúc của họ
            val myId = repo.currentUserId()
            if (lastMsg.fromId != myId && lastMsg.text.isNotBlank()) {

                // Gọi AI (Giả sử bạn đã khởi tạo aiRepo trong ViewModel)
                val sentiment = aiRepo.analyzeSentiment(lastMsg.text)

                // Cập nhật UI
                if (sentiment in listOf("HAPPY", "SAD", "ANGRY", "LOVE", "NEUTRAL")) {
                    currentSentiment = sentiment
                }
            }
        }
    }
}
