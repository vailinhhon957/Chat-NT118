package com.example.chat_compose.data

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.chat_compose.model.ChatUser
import com.example.chat_compose.model.Message
import com.example.chat_compose.model.MessageStatus
import com.example.chat_compose.model.ProfileExtras
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.io.File

class ChatRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    companion object {
        const val AI_BOT_ID = "AI_BOT"
        const val AI_BOT_NAME = "HuyAn AI"
        const val AI_BOT_EMAIL = "bot@huyan.ai"
    }

    private val aiRepo = GeminiAiRepository()
    private val storageRef = FirebaseStorage.getInstance().reference

    init {
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(false)
            .build()
        db.firestoreSettings = settings
    }

    fun currentUserId(): String? = auth.currentUser?.uid

    // ================= AUTH =================
    suspend fun register(email: String, password: String, name: String): Result<Unit> {
        return try {
            val res = auth.createUserWithEmailAndPassword(email, password).await()
            val uid = res.user?.uid ?: return Result.failure(Exception("UID null"))

            val user = mapOf(
                "uid" to uid,
                "email" to email,
                "displayName" to name,
                "isOnline" to true,
                "lastSeen" to FieldValue.serverTimestamp(),
                "avatarBase64" to null,
                "fcmToken" to null
            )

            db.collection("users").document(uid).set(user).await()
            runCatching { syncFcmToken() }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            auth.signInWithEmailAndPassword(email, password).await()
            runCatching { syncFcmToken() }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun logout() {
        auth.signOut()
    }

    suspend fun updateOnlineStatus(isOnline: Boolean) {
        val uid = currentUserId() ?: return
        runCatching {
            db.collection("users").document(uid)
                .update(
                    mapOf(
                        "isOnline" to isOnline,
                        "lastSeen" to FieldValue.serverTimestamp()
                    )
                ).await()
        }
    }

    suspend fun syncFcmToken(): Result<String> {
        val uid = currentUserId() ?: return Result.failure(Exception("Not logged in"))
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            db.collection("users").document(uid)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
                .await()
            Result.success(token)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ================= PASSWORD & EMAIL =================
    /**
     * Reset mật khẩu qua email (dùng callback như AuthViewModel đang gọi).
     * Ví dụ: repo.resetPassword(email) { result -> ... }
     */
    fun resetPassword(email: String, cb: (Result<Unit>) -> Unit) {
        auth.sendPasswordResetEmail(email.trim())
            .addOnSuccessListener { cb(Result.success(Unit)) }
            .addOnFailureListener { cb(Result.failure(it)) }
    }

    fun sendEmailVerification() {
        auth.currentUser?.sendEmailVerification()
    }

    fun isCurrentUserEmailVerified(): Boolean {
        return auth.currentUser?.isEmailVerified ?: true
    }

    // ================= USERS & PROFILE =================
    suspend fun getUserProfile(uid: String): ChatUser? {
        if (uid == AI_BOT_ID) {
            return ChatUser(
                uid = AI_BOT_ID,
                email = AI_BOT_EMAIL,
                displayName = AI_BOT_NAME,
                isOnline = true,
                lastSeen = null,
                avatarBytes = null
            )
        }

        return try {
            val doc = db.collection("users").document(uid).get().await()
            if (!doc.exists()) return null

            val avatarBytes = doc.getString("avatarBase64")?.let {
                runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
            }

            ChatUser(
                uid = doc.getString("uid") ?: uid,
                email = doc.getString("email") ?: "",
                displayName = doc.getString("displayName") ?: "",
                isOnline = doc.getBoolean("isOnline") == true,
                lastSeen = doc.getTimestamp("lastSeen")?.toDate(),
                avatarBytes = avatarBytes
            )
        } catch (_: Exception) {
            null
        }
    }

    fun listenUser(uid: String): Flow<ChatUser?> = callbackFlow {
        if (uid == AI_BOT_ID) {
            trySend(
                ChatUser(
                    uid = AI_BOT_ID,
                    email = AI_BOT_EMAIL,
                    displayName = AI_BOT_NAME,
                    isOnline = true,
                    lastSeen = null,
                    avatarBytes = null
                )
            )
            awaitClose { }
            return@callbackFlow
        }

        val reg = db.collection("users").document(uid)
            .addSnapshotListener { snap, _ ->
                if (snap == null || !snap.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }

                val avatarBytes = snap.getString("avatarBase64")?.let {
                    runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                }

                trySend(
                    ChatUser(
                        uid = snap.getString("uid") ?: uid,
                        email = snap.getString("email") ?: "",
                        displayName = snap.getString("displayName") ?: "",
                        isOnline = snap.getBoolean("isOnline") == true,
                        lastSeen = snap.getTimestamp("lastSeen")?.toDate(),
                        avatarBytes = avatarBytes
                    )
                )
            }

        awaitClose { reg.remove() }
    }

    fun listenFriends(myUid: String): Flow<List<ChatUser>> = callbackFlow {
        val bot = ChatUser(
            uid = AI_BOT_ID,
            email = AI_BOT_EMAIL,
            displayName = AI_BOT_NAME,
            isOnline = true,
            lastSeen = null,
            avatarBytes = null
        )

        val reg = db.collection("users")
            .addSnapshotListener { snap, _ ->
                if (snap == null) {
                    trySend(listOf(bot))
                    return@addSnapshotListener
                }

                val users = snap.documents
                    .filter { it.id != myUid }
                    .mapNotNull { doc ->
                        val avatarBytes = doc.getString("avatarBase64")?.let {
                            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                        }

                        ChatUser(
                            uid = doc.getString("uid") ?: doc.id,
                            email = doc.getString("email") ?: "",
                            displayName = doc.getString("displayName") ?: "",
                            isOnline = doc.getBoolean("isOnline") == true,
                            lastSeen = doc.getTimestamp("lastSeen")?.toDate(),
                            avatarBytes = avatarBytes
                        )
                    }

                trySend(listOf(bot) + users.filter { it.uid != AI_BOT_ID })
            }

        awaitClose { reg.remove() }
    }

    // ================= CHAT LOGIC =================
    private fun chatId(myUid: String, partnerId: String): String {
        return if (myUid < partnerId) "${myUid}_$partnerId" else "${partnerId}_$myUid"
    }

    fun getMessagesFlow(partnerId: String): Flow<List<Message>> = listenMessages(partnerId)

    // 1. TYPING
    suspend fun setTyping(partnerId: String, isTyping: Boolean) {
        val myUid = currentUserId() ?: return
        val cid = chatId(myUid, partnerId)
        val data = mapOf<String, Any>("typing_$myUid" to isTyping)
        runCatching {
            db.collection("chats").document(cid)
                .set(data, SetOptions.merge())
                .await()
        }
    }

    suspend fun setTypingStatus(partnerId: String, isTyping: Boolean) = setTyping(partnerId, isTyping)

    // 2. LISTEN TYPING
    fun listenPartnerTyping(partnerId: String): Flow<Boolean> = callbackFlow {
        val myUid = currentUserId() ?: run {
            trySend(false)
            return@callbackFlow
        }

        val cid = chatId(myUid, partnerId)
        val reg = db.collection("chats").document(cid)
            .addSnapshotListener { snap, _ ->
                trySend(snap?.getBoolean("typing_$partnerId") ?: false)
            }

        awaitClose { reg.remove() }
    }

    fun listenToPartnerTyping(partnerId: String) = listenPartnerTyping(partnerId)

    // 3. MARK READ
    suspend fun markMessagesAsRead(partnerId: String) {
        val myUid = currentUserId() ?: return
        val cid = chatId(myUid, partnerId)

        val query = db.collection("chats").document(cid)
            .collection("messages")
            .whereEqualTo("fromId", partnerId)
            .whereNotEqualTo("status", "READ")
            .get()
            .await()

        if (query.isEmpty) return

        val batch = db.batch()
        for (doc in query.documents) {
            batch.update(doc.reference, "status", "READ")
        }
        batch.commit().await()
    }

    suspend fun sendMessage(partnerId: String, text: String, replyingTo: Message? = null) {
        val myUid = currentUserId() ?: return
        val cid = chatId(myUid, partnerId)
        val msgDoc = db.collection("chats").document(cid).collection("messages").document()

        val data = hashMapOf(
            "id" to msgDoc.id,
            "fromId" to myUid,
            "toId" to partnerId,
            "text" to text,
            "createdAt" to FieldValue.serverTimestamp(),
            "replyToId" to replyingTo?.id,
            "replyPreview" to replyingTo?.text,
            "imageBase64" to null,
            "audioUrl" to null,
            "reactions" to emptyMap<String, String>(),
            "status" to "SENT"
        )

        msgDoc.set(data).await()
    }

    // --- GỬI ẢNH (HỖ TRỢ AI) ---
    suspend fun sendImageMessage(
        partnerId: String,
        bytes: ByteArray,
        text: String = "",
        replyingTo: Message? = null
    ) {
        val myUid = currentUserId() ?: return
        val cid = chatId(myUid, partnerId)
        val msgDoc = db.collection("chats").document(cid).collection("messages").document()
        val imgB64 = Base64.encodeToString(bytes, Base64.DEFAULT)

        val data = hashMapOf<String, Any?>(
            "id" to msgDoc.id,
            "fromId" to myUid,
            "toId" to partnerId,
            "text" to text,
            "createdAt" to FieldValue.serverTimestamp(),
            "replyToId" to replyingTo?.id,
            "replyPreview" to replyingTo?.text,
            "imageBase64" to imgB64,
            "audioUrl" to null,
            "reactions" to emptyMap<String, String>(),
            "status" to "SENT"
        )

        msgDoc.set(data).await()

        if (partnerId == AI_BOT_ID) {
            processAiMedia(myUid, cid, text, imageBytes = bytes, audioBytes = null)
        }
    }

    // --- GỬI VOICE (HỖ TRỢ AI) ---
    suspend fun sendVoiceMessage(partnerId: String, audioFile: File) {
        val myUid = currentUserId() ?: return
        val cid = chatId(myUid, partnerId)
        val msgDoc = db.collection("chats").document(cid).collection("messages").document()

        val audioRef = storageRef.child("chat_audio/${msgDoc.id}.mp3")
        val uri = Uri.fromFile(audioFile)
        audioRef.putFile(uri).await()
        val downloadUrl = audioRef.downloadUrl.await().toString()

        val data = hashMapOf(
            "id" to msgDoc.id,
            "fromId" to myUid,
            "toId" to partnerId,
            "text" to "",
            "audioUrl" to downloadUrl,
            "createdAt" to FieldValue.serverTimestamp(),
            "imageBase64" to null,
            "reactions" to emptyMap<String, String>(),
            "status" to "SENT"
        )

        msgDoc.set(data).await()

        if (partnerId == AI_BOT_ID) {
            runCatching {
                val audioBytes = audioFile.readBytes()
                processAiMedia(myUid, cid, "", imageBytes = null, audioBytes = audioBytes)
            }.onFailure {
                Log.e("ChatRepository", "Lỗi đọc file audio cho AI: ${it.message}")
            }
        }
    }

    // --- XỬ LÝ MEDIA CHO AI ---
    private suspend fun processAiMedia(
        myUid: String,
        cid: String,
        text: String,
        imageBytes: ByteArray?,
        audioBytes: ByteArray?
    ) {
        try {
            val snap = db.collection("chats").document(cid).collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limitToLast(10)
                .get()
                .await()

            val history = snap.documents.map { docToMessage(it) }

            val aiText = aiRepo.replyWithMedia(history, text, imageBytes, audioBytes).trim()
            if (aiText.isBlank()) return

            saveBotResponse(cid, myUid, aiText)
        } catch (e: Exception) {
            Log.e("ChatRepository", "Lỗi xử lý AI Media: ${e.message}")
        }
    }

    // --- AI TEXT ---
    suspend fun sendAiMessage(text: String, replyingTo: Message? = null) {
        val myUid = currentUserId() ?: return
        val cleanText = text.trim()
        if (cleanText.isBlank()) return

        try {
            sendMessage(AI_BOT_ID, cleanText, replyingTo)
            val cid = chatId(myUid, AI_BOT_ID)

            val snap = db.collection("chats").document(cid).collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limitToLast(20)
                .get()
                .await()

            val history = snap.documents.map { docToMessage(it) }

            val aiText = aiRepo.replyWithMedia(history, cleanText, null, null).trim()
            if (aiText.isBlank()) return

            saveBotResponse(cid, myUid, aiText)
        } catch (e: Exception) {
            Log.e("ChatRepository", "Lỗi AI Chat: ${e.message}")
        }
    }

    private suspend fun saveBotResponse(cid: String, toId: String, aiText: String) {
        val botDoc = db.collection("chats").document(cid).collection("messages").document()
        val data = hashMapOf<String, Any?>(
            "id" to botDoc.id,
            "fromId" to AI_BOT_ID,
            "toId" to toId,
            "text" to aiText,
            "createdAt" to FieldValue.serverTimestamp(),
            "replyToId" to null,
            "replyPreview" to null,
            "imageBase64" to null,
            "audioUrl" to null,
            "reactions" to emptyMap<String, String>(),
            "status" to "SENT"
        )
        botDoc.set(data).await()
    }

    // ================= HELPERS =================
    private fun docToMessage(doc: com.google.firebase.firestore.DocumentSnapshot): Message {
        val imageBase64 = doc.getString("imageBase64")
        val imageBytes = imageBase64?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        val createdAt = doc.getTimestamp("createdAt")?.toDate()

        val reactions = (doc.get("reactions") as? Map<*, *>)?.mapNotNull { (k, v) ->
            if (k is String && v is String) k to v else null
        }?.toMap() ?: emptyMap()

        val statusStr = doc.getString("status") ?: "SENT"
        val status = try { MessageStatus.valueOf(statusStr) } catch (_: Exception) { MessageStatus.SENT }

        return Message(
            id = doc.getString("id") ?: doc.id,
            fromId = doc.getString("fromId") ?: "",
            toId = doc.getString("toId") ?: "",
            text = doc.getString("text") ?: "",
            createdAt = createdAt,
            replyToId = doc.getString("replyToId"),
            replyPreview = doc.getString("replyPreview"),
            imageBytes = imageBytes,
            audioUrl = doc.getString("audioUrl"),
            reactions = reactions,
            status = status
        )
    }

    // ================= SMART REPLY =================
    suspend fun generateSmartReplies(
        partnerId: String,
        partnerName: String? = null,
        recentMessages: List<Message>
    ): List<String> {
        if (partnerId.isBlank() || partnerId == AI_BOT_ID) return emptyList()

        val history = recentMessages
            .filter { it.text.isNotBlank() && it.imageBytes == null && it.audioUrl == null }
            .takeLast(12)

        if (history.isEmpty()) return emptyList()

        return try {
            val prompt = buildString {
                append("Hãy đề xuất đúng 3 câu trả lời ngắn, tự nhiên bằng tiếng Việt cho tin nhắn cuối cùng.\n")
                append("Mỗi câu <= 12 từ. Không emoji. Không đánh số. Không bullet.\n")
                if (!partnerName.isNullOrBlank()) append("Tên người kia: ").append(partnerName).append("\n")
                append("Chỉ trả về đúng 3 dòng, mỗi dòng 1 gợi ý.")
            }

            val raw = aiRepo.replyWithMedia(history, prompt, null, null).trim()

            raw.replace("\r", "\n").lines()
                .flatMap { it.split("|", "•", ";").map { s -> s.trim() } }
                .map { it.replace(Regex("^[-•\\d\\).:]+\\s*"), "").trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(3)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun translateText(text: String): String {
        return try {
            aiRepo.replyWithMedia(emptyList(), "Dịch sang Tiếng Việt: \"$text\"", null, null).trim()
        } catch (e: Exception) {
            "Lỗi dịch: ${e.message}"
        }
    }

    fun listenMessages(partnerId: String): Flow<List<Message>> = callbackFlow {
        val myUid = currentUserId() ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val cid = chatId(myUid, partnerId)
        val reg = db.collection("chats").document(cid).collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, _ ->
                if (snap != null) {
                    trySend(snap.documents.map { docToMessage(it) })
                } else {
                    trySend(emptyList())
                }
            }

        awaitClose { reg.remove() }
    }

    suspend fun toggleReaction(partnerId: String, msgId: String, emoji: String) {
        val myUid = currentUserId() ?: return
        val cid = chatId(myUid, partnerId)
        val ref = db.collection("chats").document(cid).collection("messages").document(msgId)

        db.runTransaction { tr ->
            val snap = tr.get(ref)
            val reactions = (snap.get("reactions") as? Map<String, String>)
                ?.toMutableMap()
                ?: mutableMapOf()

            if (reactions[myUid] == emoji) reactions.remove(myUid) else reactions[myUid] = emoji
            tr.update(ref, "reactions", reactions)
        }.await()
    }

    // ================= PROFILE EXTRAS =================
    suspend fun getProfileExtras(uid: String): ProfileExtras {
        val doc = db.collection("users").document(uid).get().await()
        return ProfileExtras(doc.getLong("birthDateMillis"), doc.getString("gender"))
    }

    suspend fun updateProfileExtras(birthDateMillis: Long?, gender: String) {
        val uid = currentUserId() ?: return
        db.collection("users").document(uid)
            .update(mapOf("birthDateMillis" to birthDateMillis, "gender" to gender))
            .await()
    }

    suspend fun changePassword(oldP: String, newP: String) {
        val user = auth.currentUser ?: throw IllegalStateException("Chưa đăng nhập")
        val email = user.email ?: throw IllegalStateException("Tài khoản không có email")

        user.reauthenticate(EmailAuthProvider.getCredential(email, oldP)).await()
        user.updatePassword(newP).await()
    }
    suspend fun updateAvatar(avatarBytes: ByteArray): Result<Unit> {
        val uid = currentUserId() ?: return Result.failure(Exception("Chưa đăng nhập"))
        return try {
            val b64 = Base64.encodeToString(avatarBytes, Base64.DEFAULT)
            db.collection("users").document(uid)
                .set(
                    mapOf(
                        "avatarBase64" to b64,
                        "lastSeen" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Xoá avatar (set null) */
    suspend fun clearAvatar(): Result<Unit> {
        val uid = currentUserId() ?: return Result.failure(Exception("Chưa đăng nhập"))
        return try {
            db.collection("users").document(uid)
                .set(
                    mapOf(
                        "avatarBase64" to null,
                        "lastSeen" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
