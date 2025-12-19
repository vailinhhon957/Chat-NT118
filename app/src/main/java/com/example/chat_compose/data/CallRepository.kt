package com.example.chat_compose.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
import android.util.Base64
import com.example.chat_compose.data.ChatRepository
import java.util.Date
data class IncomingCall(
    val id: String = "",
    val callerId: String = "",
    val calleeId: String = "",
    val callType: String = "audio" // "audio" | "video"
)
data class SimpleUserProfile(
    val displayName: String = "",
    val avatarBytes: ByteArray? = null
)
class CallRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    init {
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(false)
            .build()
        db.firestoreSettings = settings
    }

    fun currentUid(): String? = auth.currentUser?.uid
    private fun callsCol() = db.collection("calls")

    // ===== CREATE OUTGOING CALL =====
    // ===== CREATE OUTGOING CALL =====
    suspend fun startOutgoingCall(partnerUid: String, callType: String = "audio"): String {
        val myUid = currentUid() ?: throw IllegalStateException("Not logged in")

        val docRef = callsCol().document()
        val safeType = if (callType.equals("video", ignoreCase = true)) "video" else "audio"

        val data = hashMapOf(
            "from" to myUid,
            "to" to partnerUid,
            "status" to "ringing", // ringing | accepted | ended
            "callType" to safeType,
            "createdAt" to FieldValue.serverTimestamp()
        )
        docRef.set(data).await()
        return docRef.id
    }

    suspend fun getUserProfile(uid: String): SimpleUserProfile {
        return try {
            val doc = db.collection("users").document(uid).get().await()
            val name = doc.getString("displayName") ?: "Người dùng"
            val avatarBase64 = doc.getString("avatarBase64")
            val bytes = avatarBase64?.let {
                try { Base64.decode(it, Base64.DEFAULT) } catch (e: Exception) { null }
            }
            SimpleUserProfile(name, bytes)
        } catch (e: Exception) {
            SimpleUserProfile("Người dùng", null)
        }
    }
    // ===== INCOMING LISTEN (for MainActivity) =====
    fun listenIncomingCallsForUser(myUid: String): Flow<IncomingCall?> = callbackFlow {
        var lastSentId: String? = null

        val q = callsCol()
            .whereEqualTo("to", myUid)
            .whereEqualTo("status", "ringing")

        val reg = q.addSnapshotListener { snap, e ->
            if (e != null) {
                Log.e("CALL", "listenIncomingCallsForUser error", e)
                return@addSnapshotListener
            }

            if (snap == null || snap.isEmpty) {
                if (lastSentId != null) {
                    lastSentId = null
                    trySend(null).isSuccess
                }
                return@addSnapshotListener
            }

            val doc = snap.documents.first()
            val callId = doc.id
            val callerId = doc.getString("from") ?: ""
            val calleeId = doc.getString("to") ?: ""
            val callType = doc.getString("callType") ?: "audio"

            if (callId.isBlank() || callerId.isBlank()) return@addSnapshotListener
            if (callId == lastSentId) return@addSnapshotListener

            lastSentId = callId
            trySend(IncomingCall(callId, callerId, calleeId, callType)).isSuccess
        }

        awaitClose { reg.remove() }
    }

    // ===== CALL DOC LISTEN =====
    fun listenCallDoc(callId: String): Flow<Map<String, Any?>> = callbackFlow {
        val reg = callsCol().document(callId).addSnapshotListener { snap, e ->
            if (e != null) {
                Log.e("CALL", "listenCallDoc error", e)
                return@addSnapshotListener
            }
            val data = snap?.data ?: return@addSnapshotListener
            trySend(data).isSuccess
        }
        awaitClose { reg.remove() }
    }

    // ===== STATUS =====
    private suspend fun setStatus(callId: String, status: String) {
        callsCol().document(callId).update(
            mapOf(
                "status" to status,
                "updatedAt" to FieldValue.serverTimestamp(),
                "endedAt" to if (status == "ended") FieldValue.serverTimestamp() else null
            )
        ).await()
    }

    suspend fun acceptCall(callId: String) = setStatus(callId, "accepted")
    suspend fun endCall(callId: String) = setStatus(callId, "ended")
    suspend fun rejectCall(callId: String) = setStatus(callId, "ended")

    // ===== SDP =====
    suspend fun setOffer(callId: String, offerSdp: String) {
        callsCol().document(callId).update("offerSdp", offerSdp).await()
    }

    suspend fun setAnswer(callId: String, answerSdp: String) {
        callsCol().document(callId).update("answerSdp", answerSdp).await()
    }

    fun listenOfferSdp(callId: String): Flow<String> = callbackFlow {
        var last: String? = null
        val reg = callsCol().document(callId).addSnapshotListener { snap, e ->
            if (e != null) {
                Log.e("CALL", "listenOfferSdp error", e)
                return@addSnapshotListener
            }
            val offer = snap?.getString("offerSdp") ?: return@addSnapshotListener
            if (offer == last) return@addSnapshotListener
            last = offer
            trySend(offer).isSuccess
        }
        awaitClose { reg.remove() }
    }

    fun listenAnswerSdp(callId: String): Flow<String> = callbackFlow {
        var last: String? = null
        val reg = callsCol().document(callId).addSnapshotListener { snap, e ->
            if (e != null) {
                Log.e("CALL", "listenAnswerSdp error", e)
                return@addSnapshotListener
            }
            val ans = snap?.getString("answerSdp") ?: return@addSnapshotListener
            if (ans == last) return@addSnapshotListener
            last = ans
            trySend(ans).isSuccess
        }
        awaitClose { reg.remove() }
    }

    // ===== ICE CANDIDATES =====
    suspend fun addCallerCandidate(callId: String, c: IceCandidate) {
        val data = hashMapOf(
            "candidate" to c.sdp,
            "sdpMid" to (c.sdpMid ?: ""),
            "sdpMLineIndex" to c.sdpMLineIndex,
            "ts" to FieldValue.serverTimestamp()
        )
        callsCol().document(callId).collection("callerCandidates").add(data).await()
    }

    suspend fun addCalleeCandidate(callId: String, c: IceCandidate) {
        val data = hashMapOf(
            "candidate" to c.sdp,
            "sdpMid" to (c.sdpMid ?: ""),
            "sdpMLineIndex" to c.sdpMLineIndex,
            "ts" to FieldValue.serverTimestamp()
        )
        callsCol().document(callId).collection("calleeCandidates").add(data).await()
    }

    fun listenCallerCandidates(callId: String): Flow<IceCandidate> =
        listenCandidatesInternal(callId, "callerCandidates")

    fun listenCalleeCandidates(callId: String): Flow<IceCandidate> =
        listenCandidatesInternal(callId, "calleeCandidates")

    private fun listenCandidatesInternal(callId: String, sub: String): Flow<IceCandidate> = callbackFlow {
        val col = callsCol().document(callId).collection(sub)

        val reg = col.addSnapshotListener { snap, e ->
            if (e != null) {
                Log.e("CALL", "listenCandidates($sub) error", e)
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener

            for (dc in snap.documentChanges) {
                if (dc.type.name != "ADDED") continue
                val d = dc.document
                val candidate = d.getString("candidate") ?: continue
                val sdpMid = d.getString("sdpMid")
                val sdpMLineIndex = (d.getLong("sdpMLineIndex") ?: 0L).toInt()
                trySend(IceCandidate(sdpMid, sdpMLineIndex, candidate)).isSuccess
            }
        }
        awaitClose { reg.remove() }
    }
    suspend fun sendCallLogToChat(
        partnerId: String,
        callType: String, // "audio" hoặc "video"
        statusMessage: String // Ví dụ: "Đã kết thúc", "Từ chối"
    ) {
        val myUid = currentUid() ?: return

        // Tạo Chat ID (Logic này phải giống hệt bên ChatRepository)
        // Để đảm bảo tin nhắn chui vào đúng box chat của 2 người
        val chatId = if (myUid < partnerId) "${myUid}_$partnerId" else "${partnerId}_$myUid"

        val msgDoc = db.collection("chats").document(chatId).collection("messages").document()

        // Chọn icon tùy loại cuộc gọi
        val icon = if (callType == "video") "📹" else "📞"
        val displayType = if (callType == "video") "Video Call" else "Audio Call"

        val textContent = "$icon $displayType - $statusMessage"

        val data = hashMapOf(
            "id" to msgDoc.id,
            "fromId" to myUid,
            "toId" to partnerId,
            "text" to textContent,
            "createdAt" to FieldValue.serverTimestamp(),
            "replyToId" to null,
            "replyPreview" to null,
            "imageBase64" to null,
            "audioUrl" to null,
            "reactions" to emptyMap<String, String>(),
            "status" to "SENT" // Quan trọng để khớp với model Message mới
        )

        try {
            msgDoc.set(data).await()
        } catch (e: Exception) {
            Log.e("CallRepo", "Failed to write call log", e)
        }
    }
    suspend fun reportSensitiveContent(): Boolean {
        val myUid = currentUid() ?: return false
        val userRef = db.collection("users").document(myUid)

        return try {
            db.runTransaction { transaction ->
                val snapshot = transaction.get(userRef)

                val currentViolations = snapshot.getLong("violationCount") ?: 0
                val newCount = currentViolations + 1

                transaction.update(userRef, "violationCount", newCount)

                // Nếu vi phạm >= 3 lần -> Khóa 30 ngày
                if (newCount >= 3) {
                    // Tính 30 ngày ra mili-giây
                    val thirtyDaysInMs = 30L * 24 * 60 * 60 * 1000
                    val unlockTime = System.currentTimeMillis() + thirtyDaysInMs

                    // Lưu thời điểm được mở khóa
                    transaction.update(userRef, "lockedUntil", unlockTime)

                    // (Tùy chọn) Reset số lần vi phạm về 0 để sau 30 ngày tính lại từ đầu
                    // transaction.update(userRef, "violationCount", 0)

                    return@runTransaction true // BỊ KHÓA
                }
                return@runTransaction false // CHƯA BỊ KHÓA
            }.await()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // === 2. HÀM KIỂM TRA CẤM ĐĂNG NHẬP (Dùng ở màn hình Login) ===
    // Trả về: Null nếu không bị cấm, hoặc String chứa ngày mở khóa nếu đang bị cấm
    suspend fun checkBanStatus(uid: String): String? {
        return try {
            val doc = db.collection("users").document(uid).get().await()
            val lockedUntil = doc.getLong("lockedUntil") ?: 0L
            val now = System.currentTimeMillis()

            if (lockedUntil > now) {
                // Vẫn đang trong thời gian cấm
                val date = Date(lockedUntil)
                val format = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                return format.format(date) // Trả về ngày được mở
            } else {
                // Đã hết hạn cấm hoặc chưa từng bị cấm
                return null
            }
        } catch (e: Exception) {
            null
        }
    }
}
