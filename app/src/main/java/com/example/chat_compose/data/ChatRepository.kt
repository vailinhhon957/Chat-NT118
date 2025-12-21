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
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
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
import java.util.Date

class ChatRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {

    companion object {
        const val AI_BOT_ID = "AI_BOT"
        const val AI_BOT_NAME = "HuyAn AI"
        const val AI_BOT_EMAIL = "bot@huyan.ai"

        // ==== GROUP ====
        const val GROUP_ID_PREFIX = "group_"
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

    private fun isGroupId(id: String): Boolean = id.startsWith(GROUP_ID_PREFIX)

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

        // Group profile (tái sử dụng ChatUser để hiển thị nhóm)
        if (isGroupId(uid)) {
            return getGroupAsChatUser(uid)
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

        if (isGroupId(uid)) {
            val reg = db.collection("groups").document(uid)
                .addSnapshotListener { snap, _ ->
                    if (snap == null || !snap.exists()) {
                        trySend(null)
                        return@addSnapshotListener
                    }
                    val name = snap.getString("name") ?: "Nhóm"
                    val lastPreview = snap.getString("lastMessage") ?: ""
                    val updatedAt = snap.getTimestamp("lastUpdatedAt")?.toDate()
                    trySend(
                        ChatUser(
                            uid = uid,
                            email = lastPreview,
                            displayName = name,
                            isOnline = false,
                            lastSeen = updatedAt,
                            avatarBytes = null
                        )
                    )
                }
            awaitClose { reg.remove() }
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

    /**
     * Trả về danh sách "cuộc trò chuyện" để Home hiển thị:
     * - Bot
     * - User (trừ mình)
     * - Group mà mình là member
     *
     * Giữ tên hàm listenFriends để không làm vỡ code cũ.
     */
    fun listenFriends(myUid: String): Flow<List<ChatUser>> = callbackFlow {
        val bot = ChatUser(
            uid = AI_BOT_ID,
            email = AI_BOT_EMAIL,
            displayName = AI_BOT_NAME,
            isOnline = true,
            lastSeen = null,
            avatarBytes = null
        )

        var latestUsers: List<ChatUser> = emptyList()
        var latestGroups: List<ChatUser> = emptyList()

        fun emit() {
            // Bot luôn đứng đầu
            val merged = buildList {
                add(bot)
                addAll(latestGroups)
                addAll(latestUsers.filter { it.uid != AI_BOT_ID })
            }
            trySend(merged)
        }

        val usersReg = db.collection("users")
            .addSnapshotListener { snap, _ ->
                if (snap == null) {
                    latestUsers = emptyList()
                    emit()
                    return@addSnapshotListener
                }

                latestUsers = snap.documents
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

                emit()
            }

        val groupsReg = db.collection("groups")
            .whereArrayContains("members", myUid)
            .addSnapshotListener { snap, _ ->
                if (snap == null) {
                    latestGroups = emptyList()
                    emit()
                    return@addSnapshotListener
                }

                latestGroups = snap.documents.map { doc ->
                    val name = doc.getString("name") ?: "Nhóm"
                    val lastPreview = doc.getString("lastMessage") ?: ""
                    val updatedAt = doc.getTimestamp("lastUpdatedAt")?.toDate()

                    ChatUser(
                        uid = doc.id,
                        email = lastPreview,          // dùng email làm "preview" cho Home
                        displayName = name,
                        isOnline = false,
                        lastSeen = updatedAt,
                        avatarBytes = null
                    )
                }.sortedByDescending { it.lastSeen?.time ?: 0L }

                emit()
            }

        awaitClose {
            usersReg.remove()
            groupsReg.remove()
        }
    }

    // ================= GROUP APIs =================

    /**
     * Tạo nhóm:
     * groups/{groupId}:
     *  - name: String
     *  - members: [uid]
     *  - createdAt, lastUpdatedAt
     *  - lastMessage: String (preview)
     */
    suspend fun createGroup(name: String, memberIds: List<String>): Result<String> {
        val myUid = currentUserId() ?: return Result.failure(Exception("Chưa đăng nhập"))
        val cleanName = name.trim()
        if (cleanName.isBlank()) return Result.failure(Exception("Tên nhóm trống"))

        val members = (memberIds + myUid).distinct()
        val doc = db.collection("groups").document()
        val groupId = GROUP_ID_PREFIX + doc.id

        return try {
            val data = mapOf(
                "id" to groupId,
                "name" to cleanName,
                "members" to members,
                "createdAt" to FieldValue.serverTimestamp(),
                "lastUpdatedAt" to FieldValue.serverTimestamp(),
                "lastMessage" to ""
            )
            // Lưu đúng document id = groupId
            db.collection("groups").document(groupId).set(data).await()
            Result.success(groupId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getGroupAsChatUser(groupId: String): ChatUser? {
        return try {
            val doc = db.collection("groups").document(groupId).get().await()
            if (!doc.exists()) return null
            val name = doc.getString("name") ?: "Nhóm"
            val lastPreview = doc.getString("lastMessage") ?: ""
            val updatedAt = doc.getTimestamp("lastUpdatedAt")?.toDate()
            ChatUser(
                uid = groupId,
                email = lastPreview,
                displayName = name,
                isOnline = false,
                lastSeen = updatedAt,
                avatarBytes = null
            )
        } catch (_: Exception) {
            null
        }
    }

    // ================= CHAT LOGIC =================
    private fun chatId(myUid: String, partnerId: String): String {
        return if (myUid < partnerId) "${myUid}_$partnerId" else "${partnerId}_$myUid"
    }

    fun getMessagesFlow(partnerId: String): Flow<List<Message>> = listenMessages(partnerId)

    // 1. TYPING
    suspend fun setTyping(partnerId: String, isTyping: Boolean) {
        val myUid = currentUserId() ?: return

        // typing group: groups/{gid} (optional)
        if (isGroupId(partnerId)) {
            val data = mapOf<String, Any>("typing_$myUid" to isTyping)
            runCatching {
                db.collection("groups").document(partnerId)
                    .set(data, SetOptions.merge())
                    .await()
            }
            return
        }

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

        if (isGroupId(partnerId)) {
            val reg = db.collection("groups").document(partnerId)
                .addSnapshotListener { snap, _ ->
                    // partnerId là group, nên chỉ trả về typing tổng quát:
                    // nếu bất kỳ typing_* nào true thì true.
                    val anyTyping = snap?.data
                        ?.filterKeys { it.startsWith("typing_") }
                        ?.values
                        ?.any { it == true } == true
                    trySend(anyTyping)
                }
            awaitClose { reg.remove() }
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

        // Group: mark read theo user -> dùng field per-user (đơn giản: bỏ qua nếu bạn chưa cần)
        if (isGroupId(partnerId)) {
            // Nếu bạn muốn “đã đọc” trong group: cần schema readBy/lastReadAt theo user.
            return
        }

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

    /**
     * sendMessage:
     * - 1-1: chats/{chatId}/messages
     * - group: groups/{groupId}/messages
     */
    suspend fun sendMessage(
        partnerId: String,
        text: String,
        replyingTo: Message? = null,
        isEphemeral: Boolean = false
    ) {
        val myUid = currentUserId() ?: return
        val cleanText = text.trim()
        if (cleanText.isBlank()) return

        val expiryTime = if (isEphemeral) System.currentTimeMillis() + (10 * 60 * 1000) else null

        if (isGroupId(partnerId)) {
            val msgDoc = db.collection("groups").document(partnerId).collection("messages").document()
            val data = hashMapOf<String, Any?>(
                "id" to msgDoc.id,
                "fromId" to myUid,
                "toId" to partnerId, // groupId
                "text" to cleanText,
                "createdAt" to FieldValue.serverTimestamp(),
                "replyToId" to replyingTo?.id,
                "replyPreview" to replyingTo?.text,
                "imageBase64" to null,
                "audioUrl" to null,
                "reactions" to emptyMap<String, String>(),
                "status" to "SENT",
                "expiryTime" to expiryTime
            )
            msgDoc.set(data).await()

            // cập nhật preview cho Home
            db.collection("groups").document(partnerId)
                .set(
                    mapOf(
                        "lastMessage" to cleanText,
                        "lastUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()
            return
        }

        val cid = chatId(myUid, partnerId)
        val msgDoc = db.collection("chats").document(cid).collection("messages").document()
        val data = hashMapOf<String, Any?>(
            "id" to msgDoc.id,
            "fromId" to myUid,
            "toId" to partnerId,
            "text" to cleanText,
            "createdAt" to FieldValue.serverTimestamp(),
            "replyToId" to replyingTo?.id,
            "replyPreview" to replyingTo?.text,
            "imageBase64" to null,
            "audioUrl" to null,
            "reactions" to emptyMap<String, String>(),
            "status" to "SENT",
            "expiryTime" to expiryTime
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
        val imgB64 = Base64.encodeToString(bytes, Base64.DEFAULT)

        if (isGroupId(partnerId)) {
            val msgDoc = db.collection("groups").document(partnerId).collection("messages").document()
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

            db.collection("groups").document(partnerId)
                .set(
                    mapOf(
                        "lastMessage" to if (text.isBlank()) "[Ảnh]" else text,
                        "lastUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()
            return
        }

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

        val audioRef = storageRef.child("chat_audio/${System.currentTimeMillis()}_${audioFile.name}")
        val uri = Uri.fromFile(audioFile)
        audioRef.putFile(uri).await()
        val downloadUrl = audioRef.downloadUrl.await().toString()

        if (isGroupId(partnerId)) {
            val msgDoc = db.collection("groups").document(partnerId).collection("messages").document()
            val data = hashMapOf<String, Any?>(
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

            db.collection("groups").document(partnerId)
                .set(
                    mapOf(
                        "lastMessage" to "[Voice]",
                        "lastUpdatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
                .await()
            return
        }

        val cid = chatId(myUid, partnerId)
        val msgDoc = db.collection("chats").document(cid).collection("messages").document()

        val data = hashMapOf<String, Any?>(
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

        val expiryTime = doc.getLong("expiryTime")
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
            status = status,
            expiryTime = expiryTime
        )
    }

    fun listenMessages(partnerId: String): Flow<List<Message>> = callbackFlow {
        val myUid = currentUserId() ?: run {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        // ===== GROUP =====
        if (isGroupId(partnerId)) {
            val reg = db.collection("groups").document(partnerId)
                .collection("messages")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .addSnapshotListener { snap, _ ->
                    if (snap == null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }

                    val currentTime = System.currentTimeMillis()
                    val valid = mutableListOf<Message>()

                    for (doc in snap.documents) {
                        val msg = docToMessage(doc)
                        if (msg.expiryTime != null && currentTime > msg.expiryTime) {
                            db.collection("groups").document(partnerId)
                                .collection("messages").document(msg.id)
                                .delete()
                                .addOnFailureListener { e ->
                                    Log.e("ChatRepo", "Lỗi xóa tin hết hạn (group): ${e.message}")
                                }
                        } else {
                            valid.add(msg)
                        }
                    }

                    trySend(valid)
                }

            awaitClose { reg.remove() }
            return@callbackFlow
        }

        // ===== 1-1 =====
        val cid = chatId(myUid, partnerId)

        val reg = db.collection("chats").document(cid)
            .collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, _ ->
                if (snap == null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val currentTime = System.currentTimeMillis()
                val validMessages = mutableListOf<Message>()
                for (doc in snap.documents) {
                    val msg = docToMessage(doc)

                    if (msg.expiryTime != null && currentTime > msg.expiryTime) {
                        db.collection("chats").document(cid)
                            .collection("messages").document(msg.id)
                            .delete()
                            .addOnFailureListener { e ->
                                Log.e("ChatRepo", "Lỗi xóa tin hết hạn: ${e.message}")
                            }
                    } else {
                        validMessages.add(msg)
                    }
                }

                trySend(validMessages)
            }

        awaitClose { reg.remove() }
    }

    suspend fun toggleReaction(partnerId: String, msgId: String, emoji: String) {
        val myUid = currentUserId() ?: return

        val (colRef, docRef) =
            if (isGroupId(partnerId)) {
                val ref = db.collection("groups").document(partnerId).collection("messages").document(msgId)
                "groups" to ref
            } else {
                val cid = chatId(myUid, partnerId)
                val ref = db.collection("chats").document(cid).collection("messages").document(msgId)
                "chats" to ref
            }

        db.runTransaction { tr ->
            val snap = tr.get(docRef)
            val reactions = (snap.get("reactions") as? Map<String, String>)
                ?.toMutableMap()
                ?: mutableMapOf()

            if (reactions[myUid] == emoji) reactions.remove(myUid) else reactions[myUid] = emoji
            tr.update(docRef, "reactions", reactions)
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

    suspend fun deleteMessage(partnerId: String, msgId: String) {
        val myUid = currentUserId() ?: return

        try {
            if (isGroupId(partnerId)) {
                db.collection("groups").document(partnerId)
                    .collection("messages").document(msgId)
                    .delete()
                    .await()
                return
            }

            val cid = chatId(myUid, partnerId)
            db.collection("chats").document(cid)
                .collection("messages").document(msgId)
                .delete()
                .await()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun loginWithGoogle(idToken: String): Result<FirebaseUser?> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            Result.success(authResult.user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createFirestoreUserIfNeed(uid: String, email: String?, name: String?) {
        val docRef = db.collection("users").document(uid)
        val snapshot = docRef.get().await()

        if (!snapshot.exists()) {
            val userMap = hashMapOf(
                "uid" to uid,
                "displayName" to (name ?: "No Name"),
                "email" to (email ?: ""),
                "avatarBase64" to null,
                "isOnline" to true,
                "lastSeen" to FieldValue.serverTimestamp(),
                "fcmToken" to null
            )
            docRef.set(userMap).await()
            runCatching { syncFcmToken() }
        }
    }
    suspend fun generateSmartReplies(
        partnerId: String,
        partnerName: String?,
        recentMessages: List<Message>
    ): List<String> {
        if (recentMessages.isEmpty()) return emptyList()

        // Lấy 10 tin gần nhất (đủ ngữ cảnh, tránh dài)
        val ctx = recentMessages.takeLast(10).joinToString("\n") { m ->
            val who = if (m.fromId == currentUserId()) "Me" else (partnerName?.takeIf { it.isNotBlank() } ?: "Partner")
            val text = m.text.replace("\n", " ").trim()
            "$who: $text"
        }.take(1800)

        val last = recentMessages.lastOrNull()?.text?.trim().orEmpty()
        if (last.isBlank()) return emptyList()

        val prompt = """
Bạn là trợ lý gợi ý trả lời tin nhắn cho app chat.
Nhiệm vụ: Đưa ra 3 đến 5 câu trả lời ngắn (mỗi câu <= 10 từ) phù hợp NGỮ CẢNH và lịch sự.
- Chỉ trả về danh sách câu trả lời, MỖI CÂU 1 DÒNG.
- Không đánh số, không gạch đầu dòng, không giải thích.
- Tránh nội dung nhạy cảm, xúc phạm, riêng tư.
- Nếu câu hỏi cần hỏi lại: đưa 1 câu hỏi làm rõ.

Ngữ cảnh (mới nhất ở cuối):
$ctx

Tin nhắn cần trả lời:
"$last"
""".trimIndent()

        val raw = aiRepo.generatePlain(prompt)
        // Parse: mỗi dòng là 1 suggestion
        val lines = raw.lines()
            .map { it.trim().trimStart('-', '•', '*', '1', '2', '3', '4', '5', '.', ')').trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val suggestions = lines
            .filter { it.length in 1..80 }
            .take(5)

        return if (suggestions.isNotEmpty()) suggestions else listOf("Dạ", "OK", "Bạn nói rõ hơn giúp mình nhé")
    }
    suspend fun translateText(
        text: String,
        targetLang: String = "vi"
    ): String {
        val clean = text.trim()
        if (clean.isBlank()) return clean

        val prompt = """
Hãy dịch đoạn sau sang ngôn ngữ mục tiêu: $targetLang.
- Chỉ trả về BẢN DỊCH.
- Không giải thích, không thêm ký tự lạ.
- Giữ nguyên emoji, số, tên riêng, link.

Văn bản:
"$clean"
""".trimIndent()

        val out = aiRepo.generatePlain(prompt)
        // Nếu model trả về kèm dấu ngoặc/quote thì làm sạch nhẹ
        return out.trim().trim('"').trim()
    }

}
