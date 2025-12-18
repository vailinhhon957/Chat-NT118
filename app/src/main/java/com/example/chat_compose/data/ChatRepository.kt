package com.example.chat_compose.data

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.chat_compose.model.ChatUser
import com.example.chat_compose.model.Message
import com.example.chat_compose.model.MessageStatus // Đảm bảo bạn đã có Enum này trong Message.kt
import com.example.chat_compose.model.ProfileExtras
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
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

    // Repository gọi Gemini
    private val aiRepo = GeminiAiRepository()

    // Storage Reference (Để upload ảnh, voice)
    private val storageRef = FirebaseStorage.getInstance().reference

    init {
        // Tắt offline cache để tránh lỗi SQLiteDiskIOException trên một số máy ảo
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
        val data = mapOf(
            "isOnline" to isOnline,
            "lastSeen" to FieldValue.serverTimestamp()
        )
        runCatching { db.collection("users").document(uid).update(data).await() }
    }

    // ================= FCM TOKEN =================
    suspend fun syncFcmToken(): Result<String> {
        val uid = currentUserId() ?: return Result.failure(Exception("Not logged in"))
        return try {
            val token = FirebaseMessaging.getInstance().token.await()
            Log.d("ChatRepo", "FCM Token: $token")

            val updateData = mapOf("fcmToken" to token)
            db.collection("users").document(uid)
                .set(updateData, SetOptions.merge())
                .await()

            Result.success(token)
        } catch (e: Exception) {
            Log.e("ChatRepo", "syncFcmToken error: ${e.message}")
            Result.failure(e)
        }
    }

    // ================= USERS =================
    fun listenUser(uid: String): Flow<ChatUser?> = callbackFlow {
        if (uid == AI_BOT_ID) {
            trySend(
                ChatUser(
                    uid = AI_BOT_ID,
                    displayName = AI_BOT_NAME,
                    email = AI_BOT_EMAIL,
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

                val avatarBase64 = snap.getString("avatarBase64")
                val avatarBytes = avatarBase64?.let {
                    runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                }
                val lastSeen = snap.getTimestamp("lastSeen")?.toDate()

                trySend(
                    ChatUser(
                        uid = snap.getString("uid") ?: uid,
                        email = snap.getString("email") ?: "",
                        displayName = snap.getString("displayName") ?: "",
                        isOnline = snap.getBoolean("isOnline") == true,
                        lastSeen = lastSeen,
                        avatarBytes = avatarBytes
                    )
                )
            }
        awaitClose { reg.remove() }
    }

    fun listenFriends(myUid: String): Flow<List<ChatUser>> = callbackFlow {
        val bot = ChatUser(
            uid = AI_BOT_ID,
            displayName = AI_BOT_NAME,
            email = AI_BOT_EMAIL,
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
                        val avatarBase64 = doc.getString("avatarBase64")
                        val avatarBytes = avatarBase64?.let {
                            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                        }
                        val lastSeen = doc.getTimestamp("lastSeen")?.toDate()

                        ChatUser(
                            uid = doc.getString("uid") ?: doc.id,
                            email = doc.getString("email") ?: "",
                            displayName = doc.getString("displayName") ?: "",
                            isOnline = doc.getBoolean("isOnline") == true,
                            lastSeen = lastSeen,
                            avatarBytes = avatarBytes
                        )
                    }

                val finalList = listOf(bot) + users.filter { it.uid != AI_BOT_ID }
                trySend(finalList)
            }
        awaitClose { reg.remove() }
    }

    // ================= CHAT LOGIC =================
    private fun chatId(myUid: String, partnerId: String): String {
        return if (myUid < partnerId) "${myUid}_$partnerId" else "${partnerId}_$myUid"
    }

    // 1. CẬP NHẬT TRẠNG THÁI TYPING (ĐANG GÕ)
    suspend fun setTyping(partnerId: String, isTyping: Boolean) {
        val myUid = currentUserId() ?: return
        val cid = chatId(myUid, partnerId)

        // Lưu trạng thái gõ của user hiện tại vào document chat chung
        // Ví dụ: typing_USER_A = true
        val fieldName = "typing_$myUid"
        val data = mapOf(fieldName to isTyping)

        try {
            db.collection("chats").document(cid)
                .set(data, SetOptions.merge())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 2. LẮNG NGHE ĐỐI PHƯƠNG ĐANG GÕ
    fun listenPartnerTyping(partnerId: String): Flow<Boolean> = callbackFlow {
        val myUid = currentUserId() ?: run { trySend(false); return@callbackFlow }
        val cid = chatId(myUid, partnerId)
        val targetField = "typing_$partnerId"

        val reg = db.collection("chats").document(cid)
            .addSnapshotListener { snap, _ ->
                if (snap != null && snap.exists()) {
                    val isTyping = snap.getBoolean(targetField) ?: false
                    trySend(isTyping)
                } else {
                    trySend(false)
                }
            }
        awaitClose { reg.remove() }
    }

    // 3. ĐÁNH DẤU TIN NHẮN LÀ ĐÃ XEM (READ)
    suspend fun markMessagesAsRead(partnerId: String) {
        val myUid = currentUserId() ?: return
        val cid = chatId(myUid, partnerId)

        // Tìm các tin nhắn do ĐỐI PHƯƠNG gửi (fromId == partnerId)
        // Mà trạng thái KHÁC 'READ' (chưa đọc)
        val query = db.collection("chats").document(cid)
            .collection("messages")
            .whereEqualTo("fromId", partnerId)
            .whereNotEqualTo("status", "READ") // Cần index Firestore nếu dữ liệu lớn
            .get()
            .await()

        if (query.isEmpty) return

        // Dùng Batch để update nhiều tin cùng lúc
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

        val data = hashMapOf<String, Any?>(
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
            "status" to "SENT" // Mặc định là SENT
        )
        msgDoc.set(data).await()
    }

    suspend fun sendImageMessage(partnerId: String, bytes: ByteArray, text: String = "", replyingTo: Message? = null) {
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
    }

    suspend fun sendVoiceMessage(partnerId: String, audioFile: File) {
        val myUid = currentUserId() ?: return
        val cid = chatId(myUid, partnerId)
        val msgDoc = db.collection("chats").document(cid).collection("messages").document()

        // Upload lên Firebase Storage
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
    }

    suspend fun translateText(text: String): String {
        return try {
            val prompt = "Translate the following text to Vietnamese (Tiếng Việt), only return the result: \"$text\""
            aiRepo.reply(history = emptyList(), userText = prompt)
        } catch (e: Exception) {
            "Lỗi dịch: ${e.message}"
        }
    }

    // ================= LISTEN MESSAGES =================
    fun listenMessages(partnerId: String): Flow<List<Message>> = callbackFlow {
        val myUid = currentUserId() ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val cid = chatId(myUid, partnerId)
        val reg = db.collection("chats").document(cid)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, _ ->
                if (snap == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                val list = snap.documents.mapNotNull { doc ->
                    val imageBase64 = doc.getString("imageBase64")
                    val imageBytes = imageBase64?.let {
                        runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                    }
                    val createdAt = doc.getTimestamp("createdAt")?.toDate()
                    val audioUrl = doc.getString("audioUrl")

                    val reactionsAny = doc.get("reactions")
                    val reactions = (reactionsAny as? Map<*, *>)?.mapNotNull { (k, v) ->
                        val kk = k as? String
                        val vv = v as? String
                        if (kk != null && vv != null) kk to vv else null
                    }?.toMap() ?: emptyMap()

                    // Map Status (READ/SENT)
                    val statusStr = doc.getString("status") ?: "SENT"
                    val status = try {
                        MessageStatus.valueOf(statusStr)
                    } catch (e: Exception) {
                        MessageStatus.SENT
                    }

                    Message(
                        id = doc.getString("id") ?: doc.id,
                        fromId = doc.getString("fromId") ?: "",
                        toId = doc.getString("toId") ?: "",
                        text = doc.getString("text") ?: "",
                        createdAt = createdAt,
                        replyToId = doc.getString("replyToId"),
                        replyPreview = doc.getString("replyPreview"),
                        imageBytes = imageBytes,
                        audioUrl = audioUrl,
                        reactions = reactions,
                        status = status // Gán status vào model
                    )
                }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    suspend fun toggleReaction(partnerId: String, msgId: String, emoji: String) {
        val myUid = currentUserId() ?: return
        val cid = chatId(myUid, partnerId)
        val ref = db.collection("chats").document(cid).collection("messages").document(msgId)

        db.runTransaction { tr ->
            val snap = tr.get(ref)
            val reactions = (snap.get("reactions") as? Map<String, String>)?.toMutableMap() ?: mutableMapOf()
            val current = reactions[myUid]
            if (current == emoji) reactions.remove(myUid) else reactions[myUid] = emoji
            tr.update(ref, "reactions", reactions)
        }.await()
    }

    // ================= LOCAL LISTENER =================
    fun listenIncomingMessagesToMe(myUid: String): Flow<IncomingMsgEvent> = callbackFlow {
        var firstSnapshot = true
        Log.d("ChatRepo", "Start listening msg for: $myUid")

        val query = db.collectionGroup("messages").whereEqualTo("toId", myUid)
        val reg = query.addSnapshotListener { snap, err ->
            if (err != null || snap == null) return@addSnapshotListener
            if (firstSnapshot) { firstSnapshot = false; return@addSnapshotListener }

            for (dc in snap.documentChanges) {
                if (dc.type != DocumentChange.Type.ADDED) continue
                val doc = dc.document
                val msgId = doc.getString("id") ?: doc.id
                val fromId = doc.getString("fromId") ?: ""
                val text = doc.getString("text") ?: ""
                val img = doc.getString("imageBase64")

                trySend(IncomingMsgEvent(msgId, fromId, text, img))
            }
        }
        awaitClose { reg.remove() }
    }

    suspend fun updateAvatar(bytes: ByteArray) {
        val uid = currentUserId() ?: return
        val avatarBase64 = Base64.encodeToString(bytes, Base64.DEFAULT)
        db.collection("users").document(uid).update("avatarBase64", avatarBase64).await()
    }

    private fun docToMessage(doc: com.google.firebase.firestore.DocumentSnapshot): Message {
        val imageBase64 = doc.getString("imageBase64")
        val imageBytes = imageBase64?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
        val createdAt = doc.getTimestamp("createdAt")?.toDate()
        val audioUrl = doc.getString("audioUrl")

        val reactionsAny = doc.get("reactions")
        val reactions = (reactionsAny as? Map<*, *>)?.mapNotNull { (k, v) ->
            val kk = k as? String
            val vv = v as? String
            if (kk != null && vv != null) kk to vv else null
        }?.toMap() ?: emptyMap()

        val statusStr = doc.getString("status") ?: "SENT"
        val status = try { MessageStatus.valueOf(statusStr) } catch (e: Exception) { MessageStatus.SENT }

        return Message(
            id = doc.getString("id") ?: doc.id,
            fromId = doc.getString("fromId") ?: "",
            toId = doc.getString("toId") ?: "",
            text = doc.getString("text") ?: "",
            createdAt = createdAt,
            replyToId = doc.getString("replyToId"),
            replyPreview = doc.getString("replyPreview"),
            imageBytes = imageBytes,
            audioUrl = audioUrl,
            reactions = reactions,
            status = status
        )
    }

    // ================= AI CHAT =================
    suspend fun sendAiMessage(text: String, replyingTo: Message? = null) {
        val myUid = currentUserId() ?: return
        val cleanText = text.trim()
        if (cleanText.isBlank()) return

        try {
            sendMessage(AI_BOT_ID, cleanText, replyingTo)

            val cid = chatId(myUid, AI_BOT_ID)
            val snap = db.collection("chats").document(cid)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .limitToLast(20)
                .get()
                .await()

            val history = snap.documents.map { docToMessage(it) }
            val aiText = aiRepo.reply(history = history, userText = cleanText).trim()
            if (aiText.isBlank()) return

            val botDoc = db.collection("chats").document(cid).collection("messages").document()
            val data = hashMapOf<String, Any?>(
                "id" to botDoc.id,
                "fromId" to AI_BOT_ID,
                "toId" to myUid,
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
        } catch (e: Exception) {
            Log.e("ChatRepository", "Lỗi AI Chat: ${e.message}")
        }
    }

    // ================= PROFILE EXTRAS =================
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

    suspend fun getProfileExtras(uid: String): ProfileExtras {
        val doc = db.collection("users").document(uid).get().await()
        val birth = doc.getLong("birthDateMillis")
        val gender = doc.getString("gender")
        return ProfileExtras(birth, gender)
    }

    suspend fun updateProfileExtras(birthDateMillis: Long?, gender: String) {
        val uid = currentUserId() ?: return
        val data = hashMapOf<String, Any?>(
            "birthDateMillis" to birthDateMillis,
            "gender" to gender
        )
        db.collection("users").document(uid).update(data).await()
    }

    suspend fun changePassword(currentPassword: String, newPassword: String) {
        val user = auth.currentUser ?: throw IllegalStateException("Chưa đăng nhập")
        val email = user.email ?: throw IllegalStateException("Tài khoản không có email")

        val credential = EmailAuthProvider.getCredential(email, currentPassword)
        user.reauthenticate(credential).await()
        user.updatePassword(newPassword).await()
    }
}

data class IncomingMsgEvent(
    val msgId: String,
    val fromId: String,
    val text: String,
    val imageBase64: String?
)