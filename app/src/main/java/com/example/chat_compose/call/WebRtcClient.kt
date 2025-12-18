package com.example.chat_compose.call

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.example.chat_compose.data.CallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRtcClient(
    private val context: Context,
    private val repo: CallRepository,
    private val scope: CoroutineScope
) {
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null

    // audio
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    // video
    private var eglBase: EglBase? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    private var iceJob: Job? = null
    private var sdpJob: Job? = null

    fun eglContext(): EglBase.Context? = eglBase?.eglBaseContext

    /**
     * CallScreen (Compose) cần Egl context để init SurfaceViewRenderer
     * trước khi startCaller/startCallee (tức là trước khi init()).
     */
    fun ensureEglContext(): EglBase.Context {
        if (eglBase == null) {
            eglBase = EglBase.create()
        }
        return eglBase!!.eglBaseContext
    }

    fun setVideoRenderers(local: SurfaceViewRenderer?, remote: SurfaceViewRenderer?) {
        // remove old sinks (tránh crash khi renderer bị release nhưng track vẫn push frame)
        val oldLocal = localRenderer
        val oldRemote = remoteRenderer
        runCatching { localVideoTrack?.removeSink(oldLocal) }
        runCatching { remoteVideoTrack?.removeSink(oldRemote) }

        localRenderer = local
        remoteRenderer = remote

        // attach current tracks to new renderers (nếu track đã có sẵn)
        runCatching { if (local != null) localVideoTrack?.addSink(local) }
        runCatching { if (remote != null) remoteVideoTrack?.addSink(remote) }
    }

    fun clearVideoRenderers() {
        val l = localRenderer
        val r = remoteRenderer
        runCatching { localVideoTrack?.removeSink(l) }
        runCatching { remoteVideoTrack?.removeSink(r) }
        localRenderer = null
        remoteRenderer = null
    }

    fun init(withVideo: Boolean) {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )

        if (withVideo) {
            // nếu UI đã gọi ensureEglContext() trước đó thì giữ nguyên
            if (eglBase == null) eglBase = EglBase.create()
        }

        val adm = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()

        val builder = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)

        if (withVideo) {
            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase!!.eglBaseContext,
                true,
                true
            )
            val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            builder
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
        }

        factory = builder.createPeerConnectionFactory()

        // route audio
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
    }

    // === HÀM CŨ (CÓ THỂ GIỮ LẠI HOẶC KHÔNG) ===
    fun setMuted(muted: Boolean) {
        try { localAudioTrack?.setEnabled(!muted) } catch (_: Exception) {}
    }

    // === HÀM MỚI BẠN CẦN THÊM VÀO ĐÂY ===
    fun toggleAudio(shouldEnable: Boolean) {
        try {
            localAudioTrack?.setEnabled(shouldEnable)
        } catch (_: Exception) {
            Log.e("WebRtcClient", "Error toggling audio", )
        }
    }

    fun setSpeaker(enabled: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.isSpeakerphoneOn = enabled
    }

    private fun createPeerConnection(callId: String, isCaller: Boolean, withVideo: Boolean): PeerConnection {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                scope.launch(Dispatchers.IO) {
                    try {
                        if (isCaller) repo.addCallerCandidate(callId, c)
                        else repo.addCalleeCandidate(callId, c)
                    } catch (e: Exception) {
                        Log.e("WEBRTC", "addIceCandidate firestore failed", e)
                    }
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track() ?: return
                when (track) {
                    is AudioTrack -> {
                        track.setEnabled(true)
                        Log.d("WEBRTC", "Remote audio track received")
                    }
                    is VideoTrack -> {
                        track.setEnabled(true)
                        remoteVideoTrack = track
                        remoteRenderer?.let { r ->
                            try { track.addSink(r) } catch (_: Exception) {}
                        }
                        Log.d("WEBRTC", "Remote video track received")
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d("WEBRTC", "PC state = $newState")
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.d("WEBRTC", "ICE state = $newState")
            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: MediaStream?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
        }

        val p = factory!!.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Cannot create PeerConnection")

        // local audio
        audioSource = factory!!.createAudioSource(MediaConstraints())
        localAudioTrack = factory!!.createAudioTrack("ARDAMSa0", audioSource).apply { setEnabled(true) }
        p.addTrack(localAudioTrack)

        // local video (optional)
        if (withVideo) {
            videoSource = factory!!.createVideoSource(false)
            localVideoTrack = factory!!.createVideoTrack("ARDAMSv0", videoSource).apply { setEnabled(true) }

            val enumerator = Camera2Enumerator(context)
            val deviceName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()

            videoCapturer = deviceName?.let { enumerator.createCapturer(it, null) }

            if (videoCapturer != null) {
                surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
                videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
                try {
                    videoCapturer!!.startCapture(1280, 720, 30)
                } catch (e: Exception) {
                    Log.e("WEBRTC", "startCapture failed", e)
                }
            }

            // Nếu UI set renderer sau thì setVideoRenderers() sẽ attach lại sink
            localRenderer?.let { r ->
                try { localVideoTrack?.addSink(r) } catch (_: Exception) {}
            }

            p.addTrack(localVideoTrack)
        }

        return p
    }

    suspend fun startCaller(callId: String, withVideo: Boolean) {
        init(withVideo)
        pc = createPeerConnection(callId, isCaller = true, withVideo = withVideo)

        iceJob?.cancel()
        iceJob = scope.launch(Dispatchers.IO) {
            repo.listenCalleeCandidates(callId).collect { c ->
                pc?.addIceCandidate(c)
            }
        }

        val offer = pc!!.createOfferSuspend()
        pc!!.setLocalDescriptionSuspend(offer)
        repo.setOffer(callId, offer.description)

        sdpJob?.cancel()
        sdpJob = scope.launch(Dispatchers.IO) {
            repo.listenAnswerSdp(callId).collect { answerSdp ->
                val p = pc ?: return@collect
                if (p.remoteDescription != null) return@collect
                val ans = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
                p.setRemoteDescriptionSuspend(ans)
            }
        }
    }

    suspend fun startCallee(callId: String, withVideo: Boolean) {
        init(withVideo)
        pc = createPeerConnection(callId, isCaller = false, withVideo = withVideo)

        iceJob?.cancel()
        iceJob = scope.launch(Dispatchers.IO) {
            repo.listenCallerCandidates(callId).collect { c ->
                pc?.addIceCandidate(c)
            }
        }

        sdpJob?.cancel()
        sdpJob = scope.launch(Dispatchers.IO) {
            repo.listenOfferSdp(callId).collect { offerSdp ->
                val p = pc ?: return@collect
                if (p.remoteDescription != null) return@collect

                val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
                p.setRemoteDescriptionSuspend(offer)

                val answer = p.createAnswerSuspend()
                p.setLocalDescriptionSuspend(answer)
                repo.setAnswer(callId, answer.description)
            }
        }
    }

    fun stop() {
        try { sdpJob?.cancel() } catch (_: Exception) {}
        try { iceJob?.cancel() } catch (_: Exception) {}

        // detach sinks trước (đặc biệt khi UI đã/đang release SurfaceViewRenderer)
        runCatching { clearVideoRenderers() }

        try { remoteVideoTrack = null } catch (_: Exception) {}

        try { localVideoTrack?.setEnabled(false) } catch (_: Exception) {}
        try { localAudioTrack?.setEnabled(false) } catch (_: Exception) {}

        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        try { videoCapturer?.dispose() } catch (_: Exception) {}
        try { surfaceTextureHelper?.dispose() } catch (_: Exception) {}
        try { videoSource?.dispose() } catch (_: Exception) {}

        try { audioSource?.dispose() } catch (_: Exception) {}
        try { pc?.close() } catch (_: Exception) {}

        videoCapturer = null
        surfaceTextureHelper = null
        videoSource = null
        localVideoTrack = null

        localAudioTrack = null
        audioSource = null
        pc = null

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_NORMAL
        am.isSpeakerphoneOn = false
    }

    /**
     * Dispose toàn bộ tài nguyên (gọi khi rời CallScreen).
     * UI nên release SurfaceViewRenderer trước, rồi gọi dispose() để an toàn.
     */
    fun dispose() {
        runCatching { stop() }
        runCatching { eglBase?.release() }
        eglBase = null
        runCatching { factory?.dispose() }
        factory = null
    }
}

// ===== suspend helpers =====
private suspend fun PeerConnection.createOfferSuspend(): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(p0: String) = cont.resumeWithException(RuntimeException(p0))
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String) {}
        }, MediaConstraints())
    }

private suspend fun PeerConnection.createAnswerSuspend(): SessionDescription =
    suspendCancellableCoroutine { cont ->
        createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(p0: String) = cont.resumeWithException(RuntimeException(p0))
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String) {}
        }, MediaConstraints())
    }

private suspend fun PeerConnection.setLocalDescriptionSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine { cont ->
        setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(p0: String) = cont.resumeWithException(RuntimeException(p0))
            override fun onCreateSuccess(p0: SessionDescription) {}
            override fun onCreateFailure(p0: String) {}
        }, sdp)
    }

private suspend fun PeerConnection.setRemoteDescriptionSuspend(sdp: SessionDescription) =
    suspendCancellableCoroutine { cont ->
        setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(p0: String) = cont.resumeWithException(RuntimeException(p0))
            override fun onCreateSuccess(p0: SessionDescription) {}
            override fun onCreateFailure(p0: String) {}
        }, sdp)
    }