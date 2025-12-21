package com.example.chat_compose.screen

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.chat_compose.MainActivity
import com.example.chat_compose.call.SensitiveContentDetector
import com.example.chat_compose.call.WebRtcClient
import com.example.chat_compose.data.CallRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import kotlin.coroutines.resume

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallScreen(
    partnerId: String,
    partnerName: String,
    isCaller: Boolean,
    callId: String,
    callType: String, // "audio" | "video"
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { CallRepository() }
    val scope = rememberCoroutineScope()

    // --- UI State ---
    var realName by remember { mutableStateOf(if (partnerName.isNotBlank()) partnerName else "Đang kết nối...") }
    var avatarBytes by remember { mutableStateOf<ByteArray?>(null) }
    var status by remember { mutableStateOf("ringing") } // ringing | accepted | ended
    var startedWebRtc by remember { mutableStateOf(false) }
    var isMicOn by remember { mutableStateOf(true) }

    // IMPORTANT: chặn thoát màn hình 2 lần (nguyên nhân crash/white screen)
    var didLeaveScreen by remember { mutableStateOf(false) }

    // --- WebRTC ---
    val withVideo = callType == "video"
    val webRtc = remember(callId) { WebRtcClient(context, repo, scope) }
    val eglCtx = remember(callId, withVideo) { if (withVideo) webRtc.ensureEglContext() else null }
    var localRenderer by remember(callId) { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember(callId) { mutableStateOf<SurfaceViewRenderer?>(null) }

    // --- AI Detector ---
    val sensitiveDetector = remember { SensitiveContentDetector() }
    var aiCheckTicker by remember { mutableLongStateOf(0L) }

    // --- Load partner profile ---
    LaunchedEffect(partnerId) {
        val profile = repo.getUserProfile(partnerId)
        if (profile.displayName.isNotBlank()) realName = profile.displayName
        avatarBytes = profile.avatarBytes
    }

    // --- Helpers: end/reject/leave an toàn ---
    fun safeLeave() {
        if (didLeaveScreen) return
        didLeaveScreen = true
        runCatching { webRtc.stop() }
        onBack()
    }

    fun safeEndCall() {
        // chỉ gửi ended, KHÔNG onBack ở đây (listener sẽ gọi safeLeave đúng 1 lần)
        scope.launch { runCatching { repo.endCall(callId) } }
    }

    fun safeRejectCall() {
        // tương tự
        scope.launch { runCatching { repo.rejectCall(callId) } }
    }

    // --- Permissions ---
    var pendingAccept by remember { mutableStateOf(false) }
    val requiredPerms = remember(callType) {
        if (withVideo) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    var permsGranted by remember { mutableStateOf(false) }

    // Auto-check permissions on enter
    LaunchedEffect(Unit, callType) {
        permsGranted = requiredPerms.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permsGranted = requiredPerms.all { result[it] == true }
        if (permsGranted && pendingAccept) {
            pendingAccept = false
            scope.launch {
                repo.acceptCall(callId)
                startedWebRtc = true
                runCatching { webRtc.startCallee(callId, withVideo) }
            }
        }
    }

    // --- WebRTC renderers setup ---
    LaunchedEffect(localRenderer, remoteRenderer, withVideo) {
        if (withVideo) webRtc.setVideoRenderers(localRenderer, remoteRenderer)
    }

    // --- Listen call document (status) ---
    LaunchedEffect(callId) {
        repo.listenCallDoc(callId).collect { data ->
            val s = data["status"] as? String ?: return@collect
            status = s

            if (s == "ended") {
                // chỉ thoát 1 lần
                safeLeave()
            }
        }
    }

    // --- Caller starts when accepted ---
    LaunchedEffect(status, permsGranted) {
        if (!permsGranted) return@LaunchedEffect
        if (startedWebRtc) return@LaunchedEffect
        if (isCaller && status == "accepted") {
            startedWebRtc = true
            runCatching { webRtc.startCaller(callId, withVideo) }
        }
    }

    // --- AI check every 5s (video + accepted) ---
    if (status == "accepted" && withVideo) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(5000)
                aiCheckTicker++
            }
        }

        LaunchedEffect(aiCheckTicker) {
            if (aiCheckTicker > 0 && localRenderer != null) {
                try {
                    val bitmap = captureBitmapFromSurface(localRenderer!!)
                    if (bitmap != null) {
                        val isSensitive = sensitiveDetector.isSensitive(bitmap)
                        if (isSensitive) {
                            val isLocked = repo.reportSensitiveContent()

                            if (isLocked) {
                                Toast.makeText(
                                    context,
                                    "BẠN ĐÃ BỊ KHÓA TÀI KHOẢN 30 NGÀY DO VI PHẠM!",
                                    Toast.LENGTH_LONG
                                ).show()

                                // chỉ endCall, thoát sẽ do listener ended
                                runCatching { repo.endCall(callId) }
                                FirebaseAuth.getInstance().signOut()

                                val intent = Intent(context, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                }
                                context.startActivity(intent)
                            } else {
                                Toast.makeText(
                                    context,
                                    "CẢNH BÁO: Hình ảnh nhạy cảm! (Vi phạm 3 lần sẽ bị khóa 30 ngày)",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // --- Cleanup ---
    DisposableEffect(Unit) {
        onDispose {
            // cleanup không được gây crash nếu đã stop trước đó
            runCatching { webRtc.clearVideoRenderers() }
            runCatching { localRenderer?.release() }
            runCatching { remoteRenderer?.release() }
            localRenderer = null
            remoteRenderer = null
            runCatching { webRtc.dispose() }
        }
    }

    // ================= UI =================
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1F1C2C), Color(0xFF928DAB))))
            .systemBarsPadding()
    ) {
        // ===== VIDEO UI =====
        if (withVideo && status == "accepted") {
            Box(Modifier.fillMaxSize()) {

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            init(eglCtx, null)
                            setEnableHardwareScaler(true)
                            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                            setMirror(false)
                            remoteRenderer = this
                        }
                    }
                )

                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(width = 110.dp, height = 160.dp)
                        .border(2.dp, Color.White.copy(0.5f), RoundedCornerShape(16.dp)),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            SurfaceViewRenderer(ctx).apply {
                                init(eglCtx, null)
                                setEnableHardwareScaler(true)
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                                setMirror(true)
                                localRenderer = this
                            }
                        }
                    )
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(0.8f))
                            )
                        )
                        .padding(bottom = 32.dp, top = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!permsGranted) {
                        Text("Cần quyền Camera/Mic", color = Color.Red, modifier = Modifier.padding(bottom = 8.dp))
                        Button(onClick = { permLauncher.launch(requiredPerms) }) { Text("Cấp quyền") }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ControlButton(
                            icon = if (isMicOn) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                            color = if (isMicOn) Color.White.copy(0.2f) else Color.White,
                            iconColor = if (isMicOn) Color.White else Color.Black
                        ) {
                            isMicOn = !isMicOn
                            webRtc.toggleAudio(isMicOn)
                        }

                        ControlButton(
                            icon = Icons.Rounded.CallEnd,
                            color = Color(0xFFFF3B30),
                            size = 72.dp
                        ) {
                            // CHỈ endCall, không onBack
                            safeEndCall()
                        }
                    }
                }
            }
        } else {
            // ===== AUDIO / RINGING UI =====
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(80.dp))

                Box(contentAlignment = Alignment.Center) {
                    if (status == "ringing") PulseAnimation()
                    CallAvatar(realName, avatarBytes, 120.dp)
                }

                Spacer(Modifier.height(24.dp))
                Text(
                    text = realName,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when (status) {
                        "ringing" -> if (isCaller) "Đang gọi..." else "Cuộc gọi đến..."
                        "accepted" -> "Đang đàm thoại..."
                        else -> "Kết thúc"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(Modifier.weight(1f))

                if (!permsGranted) {
                    Button(
                        onClick = { permLauncher.launch(requiredPerms) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.2f))
                    ) { Text("Cấp quyền truy cập") }
                    Spacer(Modifier.height(20.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 60.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (status == "ringing") {
                        if (isCaller) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ControlButton(
                                    icon = Icons.Rounded.CallEnd,
                                    color = Color(0xFFFF3B30),
                                    size = 72.dp
                                ) {
                                    // CHỈ endCall, không onBack
                                    safeEndCall()
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Kết thúc", color = Color.White.copy(0.8f))
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ControlButton(
                                    icon = Icons.Rounded.CallEnd,
                                    color = Color(0xFFFF3B30),
                                    size = 72.dp
                                ) {
                                    // CHỈ reject, không onBack
                                    safeRejectCall()
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Từ chối", color = Color.White.copy(0.8f))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ControlButton(
                                    icon = if (withVideo) Icons.Rounded.Videocam else Icons.Rounded.Call,
                                    color = Color(0xFF34C759),
                                    size = 72.dp
                                ) {
                                    scope.launch {
                                        if (!permsGranted) {
                                            pendingAccept = true
                                            permLauncher.launch(requiredPerms)
                                        } else {
                                            repo.acceptCall(callId)
                                            startedWebRtc = true
                                            runCatching { webRtc.startCallee(callId, withVideo) }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text("Trả lời", color = Color.White.copy(0.8f))
                            }
                        }
                    } else if (status == "accepted") {
                        ControlButton(
                            icon = if (isMicOn) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                            color = if (isMicOn) Color.White.copy(0.2f) else Color.White,
                            iconColor = if (isMicOn) Color.White else Color.Black
                        ) {
                            isMicOn = !isMicOn
                            webRtc.toggleAudio(isMicOn)
                        }
                        ControlButton(
                            icon = Icons.Rounded.CallEnd,
                            color = Color(0xFFFF3B30),
                            size = 72.dp
                        ) {
                            // CHỈ endCall, không onBack
                            safeEndCall()
                        }
                    } else {
                        Button(onClick = { safeLeave() }) { Text("Đóng") }
                    }
                }
            }
        }
    }
}

@Composable
fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    iconColor: Color = Color.White,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

@Composable
fun CallAvatar(name: String, bytes: ByteArray?, size: androidx.compose.ui.unit.Dp) {
    val initials = name.firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Gray),
        contentAlignment = Alignment.Center
    ) {
        if (bytes != null) {
            val bmp = remember(bytes) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = initials,
                style = MaterialTheme.typography.displayMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun PulseAnimation() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Restart
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Restart
        ),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .background(Color.White.copy(alpha = alpha), CircleShape)
    )
}

suspend fun captureBitmapFromSurface(view: SurfaceViewRenderer): Bitmap? {
    return suspendCancellableCoroutine { cont ->
        try {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val listener = PixelCopy.OnPixelCopyFinishedListener { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) cont.resume(bitmap)
                else cont.resume(null)
            }
            PixelCopy.request(view, bitmap, listener, Handler(Looper.getMainLooper()))
        } catch (_: Exception) {
            cont.resume(null)
        }
    }
}
