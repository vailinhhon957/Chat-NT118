package com.example.chat_compose.data

import android.util.Base64
import android.util.Log
import com.example.chat_compose.data.ChatRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate
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
    private fun groupCallsCol() = db.collection("group_calls")
    // ===== CREATE OUTGOING CALL (1-1) =====
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
                try { Base64.decode(it, Base64.DEFAULT) } catch (_: Exception) { null }
            }
            SimpleUserProfile(name, bytes)
        } catch (_: Exception) {
            SimpleUserProfile("Người dùng", null)
        }
    }

    // ===== INCOMING LISTEN (1-1) =====
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

    // ===== CALL LOG: FIX để group dùng chatId = groupId =====
    suspend fun sendCallLogToChat(
        partnerId: String,
        callType: String,      // "audio" hoặc "video"
        statusMessage: String  // "Đã kết thúc", "Từ chối", ...
    ) {
        val myUid = currentUid() ?: return

        val isGroup = partnerId.startsWith(ChatRepository.GROUP_ID_PREFIX)

        // Group: chatId = groupId
        // 1-1: chatId = uid_uid
        val chatId = if (isGroup) {
            partnerId
        } else {
            if (myUid < partnerId) "${myUid}_$partnerId" else "${partnerId}_$myUid"
        }

        val msgDoc = db.collection("chats").document(chatId).collection("messages").document()

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
            "status" to "SENT"
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

                if (newCount >= 3) {
                    val thirtyDaysInMs = 30L * 24 * 60 * 60 * 1000
                    val unlockTime = System.currentTimeMillis() + thirtyDaysInMs
                    transaction.update(userRef, "lockedUntil", unlockTime)
                    return@runTransaction true
                }
                return@runTransaction false
            }.await()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun checkBanStatus(uid: String): String? {
        return try {
            val doc = db.collection("users").document(uid).get().await()
            val lockedUntil = doc.getLong("lockedUntil") ?: 0L
            val now = System.currentTimeMillis()

            if (lockedUntil > now) {
                val date = Date(lockedUntil)
                val format = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                format.format(date)
            } else null
        } catch (_: Exception) {
            null
        }
    }
    data class GroupCallSession(
        val roomId: String = "",
        val callType: String = "audio",
        val status: String = "active", // active | ended
        val startedBy: String = "",
        val startedAt: com.google.firebase.Timestamp? = null,
        val endedAt: com.google.firebase.Timestamp? = null
    )

    suspend fun startGroupCallSession(roomId: String, callType: String, starterId: String) {
        val doc = groupCallsCol().document(roomId)
        val payload = mapOf(
            "roomId" to roomId,
            "callType" to callType,
            "status" to "active",
            "startedBy" to starterId,
            "startedAt" to com.google.firebase.Timestamp.now(),
            "endedAt" to null
        )
        doc.set(payload).await()
    }

    suspend fun endGroupCallSession(roomId: String) {
        groupCallsCol().document(roomId).update(
            mapOf(
                "status" to "ended",
                "endedAt" to com.google.firebase.Timestamp.now()
            )
        ).await()
    }

    fun listenGroupCallSession(roomId: String) = kotlinx.coroutines.flow.callbackFlow<GroupCallSession?> {
        val reg = groupCallsCol().document(roomId).addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) {
                trySend(null)
                return@addSnapshotListener
            }
            val s = snap.toObject(GroupCallSession::class.java)
            trySend(s)
        }
        awaitClose { reg.remove() }
    }
}
