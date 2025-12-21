package com.example.chat_compose.model

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chat_compose.data.CallRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CallViewModel(
    private val repo: CallRepository = CallRepository()
) : ViewModel() {

    // idle | ringing | accepted | ended
    private val _callState = MutableStateFlow("idle")
    val callState: StateFlow<String> = _callState

    // Firestore call document id (cũng chính là room/signaling id)
    private val _callId = MutableStateFlow<String?>(null)
    val callId: StateFlow<String?> = _callId

    private val _partnerUid = MutableStateFlow<String?>(null)
    val partnerUid: StateFlow<String?> = _partnerUid

    private val _callType = MutableStateFlow("audio") // audio | video
    val callType: StateFlow<String> = _callType

    private var incomingJob: Job? = null
    private var callDocJob: Job? = null

    fun startIncomingListener() {
        val myUid = repo.currentUid() ?: return
        if (incomingJob?.isActive == true) return

        incomingJob = viewModelScope.launch {
            repo.listenIncomingCallsForUser(myUid).collect { incoming ->
                if (incoming == null) return@collect
                if (_callState.value != "idle") return@collect

                _callId.value = incoming.id
                _partnerUid.value = incoming.callerId
                _callType.value = if (incoming.callType == "video") "video" else "audio"
                _callState.value = "ringing"

                observeCallDoc(incoming.id)
            }
        }
    }

    // Caller: tạo call doc -> state = ringing (đợi callee accept)
    fun startOutgoingCall(partnerId: String, type: String = "audio", onCreated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                _partnerUid.value = partnerId
                _callType.value = if (type == "video") "video" else "audio"
                _callState.value = "ringing"

                val id = repo.startOutgoingCall(partnerId, _callType.value)
                _callId.value = id

                observeCallDoc(id)
                onCreated(id)
            } catch (e: Exception) {
                Log.e("CallVM", "startOutgoingCall failed", e)
                reset()
            }
        }
    }

    fun acceptIncoming() {
        val id = _callId.value ?: return
        viewModelScope.launch {
            runCatching { repo.acceptCall(id) }
            _callState.value = "accepted"
        }
    }

    fun rejectIncoming() {
        val id = _callId.value ?: return
        viewModelScope.launch {
            runCatching { repo.rejectCall(id) }
            reset()
        }
    }

    fun endCall() {
        val id = _callId.value
        viewModelScope.launch {
            if (id != null) runCatching { repo.endCall(id) }
            reset()
        }
    }

    private fun observeCallDoc(id: String) {
        callDocJob?.cancel()
        callDocJob = viewModelScope.launch {
            repo.listenCallDoc(id).collect { data ->
                val status = data["status"] as? String ?: return@collect
                _callState.value = status
                if (status == "ended") {
                    reset()
                }
            }
        }
    }

    fun reset() {
        _callState.value = "idle"
        _callId.value = null
        _partnerUid.value = null
        _callType.value = "audio"
        callDocJob?.cancel()
        callDocJob = null
    }
}
