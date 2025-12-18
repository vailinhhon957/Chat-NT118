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

    // ... (Giữ nguyên các biến StateFlow cũ)
    private val _callState = MutableStateFlow("idle")
    val callState: StateFlow<String> = _callState

    private val _callId = MutableStateFlow<String?>(null)
    val callId: StateFlow<String?> = _callId

    private val _partnerUid = MutableStateFlow<String?>(null)
    val partnerUid: StateFlow<String?> = _partnerUid

    // THÊM BIẾN NÀY ĐỂ LƯU LOẠI CUỘC GỌI
    private var currentCallType: String = "audio"

    private var incomingJob: Job? = null
    private var callDocJob: Job? = null

    fun startIncomingListener() {
        // ... (Giữ nguyên code cũ)
        val myUid = repo.currentUid() ?: return
        if (incomingJob?.isActive == true) return

        incomingJob = viewModelScope.launch {
            repo.listenIncomingCallsForUser(myUid).collect { incoming ->
                if (incoming == null) return@collect
                if (_callId.value != null && _callState.value != "idle") return@collect

                _callId.value = incoming.id
                _partnerUid.value = incoming.callerId
                _callState.value = "ringing"

                // LƯU LẠI TYPE KHI CÓ CUỘC GỌI ĐẾN
                currentCallType = incoming.callType

                observeCallDoc(incoming.id)
            }
        }
    }

    // Bên gọi
    fun startCall(partnerId: String, callType: String = "audio") { // Thêm tham số callType (mặc định audio nếu bạn chưa update UI gọi)
        viewModelScope.launch {
            try {
                _callState.value = "ringing"
                _partnerUid.value = partnerId
                currentCallType = callType // LƯU TYPE

                val id = repo.startOutgoingCall(partnerId, callType)
                _callId.value = id

                observeCallDoc(id)
            } catch (e: Exception) {
                Log.e("CALL", "startCall failed", e)
                resetCall()
            }
        }
    }

    fun acceptIncoming() {
        val id = _callId.value ?: return
        viewModelScope.launch {
            runCatching { repo.acceptCall(id) }
                .onFailure { Log.e("CALL", "acceptIncoming failed", it) }
        }
    }

    // SỬA HÀM NÀY: TỪ CHỐI
    fun rejectIncoming() {
        val id = _callId.value ?: return
        val partner = _partnerUid.value

        viewModelScope.launch {
            runCatching {
                repo.rejectCall(id)
                // Ghi log vào chat là "Từ chối"
                if (partner != null) {
                    repo.sendCallLogToChat(partner, currentCallType, "Đã từ chối")
                }
            }
                .onFailure { Log.e("CALL", "rejectIncoming failed", it) }
            resetCall()
        }
    }

    // SỬA HÀM NÀY: KẾT THÚC CUỘC GỌI
    fun hangup() {
        val id = _callId.value ?: return
        val partner = _partnerUid.value

        viewModelScope.launch {
            runCatching {
                repo.endCall(id)
                // Ghi log vào chat là "Kết thúc"
                if (partner != null) {
                    // Logic: Chỉ cần 1 bên ghi log (người bấm nút tắt) để tránh bị duplicate tin nhắn
                    repo.sendCallLogToChat(partner, currentCallType, "Kết thúc")
                }
            }
                .onFailure { Log.e("CALL", "hangup failed", it) }
            resetCall()
        }
    }

    private fun observeCallDoc(callId: String) {
        callDocJob?.cancel()
        callDocJob = viewModelScope.launch {
            repo.listenCallDoc(callId).collect { data ->
                val status = data["status"] as? String ?: return@collect
                _callState.value = status

                // Cập nhật lại callType từ document để chắc chắn
                val type = data["callType"] as? String
                if (type != null) currentCallType = type

                val myUid = repo.currentUid()
                val from = data["from"] as? String
                val to = data["to"] as? String
                if (myUid != null && from != null && to != null) {
                    _partnerUid.value = if (myUid == from) to else from
                }

                if (status == "ended") {
                    resetCall()
                }
            }
        }
    }

    private fun resetCall() {
        callDocJob?.cancel()
        callDocJob = null
        _callId.value = null
        _partnerUid.value = null
        _callState.value = "idle"
    }

    override fun onCleared() {
        incomingJob?.cancel()
        callDocJob?.cancel()
        super.onCleared()
    }
}