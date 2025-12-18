package com.example.chat_compose.screen

import android.Manifest
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chat_compose.ChatViewModel
import com.example.chat_compose.model.MessageStatus
import com.example.chat_compose.push.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    partnerId: String,
    onBack: () -> Unit,
    onStartCall: (String, String, String) -> Unit // (partnerId, partnerName, callType)
) {
    val messages = viewModel.messages
    val partner = viewModel.partnerUser
    val replyingTo = viewModel.replyingTo
    val myId = FirebaseAuth.getInstance().currentUser?.uid

    // Lấy trạng thái đối phương đang gõ
    val isPartnerTyping = viewModel.isPartnerTyping

    var text by remember { mutableStateOf("") }
    var reactionTargetId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 1. Khởi tạo & Lắng nghe
    LaunchedEffect(Unit) {
        viewModel.initRecorder(context)
        viewModel.observeTyping(partnerId)
    }

    // 2. Tự động cuộn và đánh dấu đã đọc
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            viewModel.markRead(partnerId)
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Permission Launchers
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) Toast.makeText(context, "Đã cấp quyền! Giữ nút Mic để nói.", Toast.LENGTH_SHORT).show()
    }
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) viewModel.sendImageMessage(partnerId, bytes)
        }
    }

    // Chặn thông báo khi đang mở màn hình chat
    DisposableEffect(partnerId) {
        NotificationHelper.currentChatPartnerId = partnerId
        onDispose { NotificationHelper.currentChatPartnerId = null }
    }

    val reactionEmojis = remember { listOf("👍", "❤️", "😂", "😮", "😢", "😡") }

    Box(modifier = Modifier.fillMaxSize()) {
        // BACKGROUND
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF2B32B2), Color(0xFF1488CC))))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { reactionTargetId = null }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // ===== TOP BAR =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.2f))
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                AvatarBubble(partner?.displayName, partner?.email, partner?.avatarBytes, 40.dp)
                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = partner?.displayName ?: "Chat", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    val isOnline = partner?.isOnline == true
                    Text(
                        text = if (isPartnerTyping) "Đang soạn tin..." else if (isOnline) "Đang hoạt động" else "Không hoạt động",
                        color = if (isPartnerTyping) Color.Yellow else if (isOnline) Color(0xFF4CAF50) else Color.White.copy(0.7f),
                        fontSize = 12.sp,
                        fontStyle = if(isPartnerTyping) FontStyle.Italic else FontStyle.Normal
                    )
                }

                // NÚT GỌI ĐIỆN & VIDEO (Đã chỉnh chuẩn tham số)
                val callName = partner?.displayName ?: "Người dùng"
                IconButton(onClick = { onStartCall(partnerId, callName, "audio") }) {
                    Icon(Icons.Filled.Call, "Audio Call", tint = Color.White)
                }
                IconButton(onClick = { onStartCall(partnerId, callName, "video") }) {
                    Icon(Icons.Filled.Videocam, "Video Call", tint = Color.White)
                }
            }

            // ===== MESSAGE LIST =====
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageItem(
                        msg = msg,
                        isMe = msg.fromId == myId,
                        partner = partner,
                        viewModel = viewModel,
                        partnerId = partnerId,
                        onReply = { viewModel.startReply(msg) },
                        onReaction = { emoji -> viewModel.toggleReaction(partnerId, msg.id, emoji) },
                        reactionTargetId = reactionTargetId,
                        setReactionTargetId = { reactionTargetId = it },
                        reactionEmojis = reactionEmojis
                    )
                }

                if (isPartnerTyping) {
                    item { TypingIndicator(partner) }
                }
            }

            // ===== REPLY PREVIEW =====
            AnimatedVisibility(visible = replyingTo != null) {
                Surface(color = Color(0xFF2D2D2D), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Đang trả lời...", color = Color.Gray, fontSize = 12.sp)
                            Text(replyingTo?.text ?: "", color = Color.White, maxLines = 1, fontSize = 14.sp)
                        }
                        IconButton(onClick = { viewModel.cancelReply() }) { Icon(Icons.Filled.Close, "Cancel", tint = Color.Gray) }
                    }
                }
            }

            // ===== INPUT BAR =====
            InputBar(
                text = text,
                onTextChange = {
                    text = it
                    viewModel.onInputTextChanged(partnerId, it)
                },
                onSend = {
                    viewModel.sendMessage(partnerId, text)
                    text = ""
                    scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
                },
                onImagePick = { pickImageLauncher.launch("image/*") },
                isRecording = viewModel.isRecording,
                onStartRecord = {
                    val perm = Manifest.permission.RECORD_AUDIO
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        viewModel.startRecording()
                    } else {
                        recordPermissionLauncher.launch(perm)
                    }
                },
                onStopRecord = { viewModel.stopAndSendRecording(partnerId) }
            )
        }
    }
}

// === WIDGET 1: TYPING INDICATOR ===
@Composable
fun TypingIndicator(partner: com.example.chat_compose.model.ChatUser?) {
    Row(verticalAlignment = Alignment.Bottom) {
        AvatarBubble(partner?.displayName, partner?.email, partner?.avatarBytes, 28.dp)
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.background(Color(0xFF3A3B3C), RoundedCornerShape(18.dp)).padding(12.dp, 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val dotSize = 6.dp
            @Composable
            fun BouncingDot(delay: Int) {
                val infiniteTransition = rememberInfiniteTransition(label = "dot")
                val dy by infiniteTransition.animateFloat(
                    initialValue = 0f, targetValue = -10f,
                    animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = delay, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "dy"
                )
                Box(modifier = Modifier.offset(y = dy.dp).size(dotSize).background(Color.White.copy(0.7f), CircleShape))
            }
            BouncingDot(0); BouncingDot(300); BouncingDot(600)
        }
    }
}

// === WIDGET 2: MESSAGE ITEM ===
@Composable
fun MessageItem(
    msg: com.example.chat_compose.model.Message,
    isMe: Boolean,
    partner: com.example.chat_compose.model.ChatUser?,
    viewModel: ChatViewModel,
    partnerId: String,
    onReply: () -> Unit,
    onReaction: (String) -> Unit,
    reactionTargetId: String?,
    setReactionTargetId: (String?) -> Unit,
    reactionEmojis: List<String>
) {
    val density = LocalDensity.current
    val maxDragPx = with(density) { 60.dp.toPx() }
    var dragOffset by remember { mutableStateOf(0f) }

    // Kiểm tra xem đây có phải là tin nhắn hệ thống (Call Log) không
    val isCallLog = msg.text.startsWith("📞") || msg.text.startsWith("📹")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isMe) {
            AvatarBubble(partner?.displayName, partner?.email, partner?.avatarBytes, 28.dp)
            Spacer(Modifier.width(8.dp))
        }

        Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
            if (msg.replyPreview != null) {
                Text(text = "↩ ${msg.replyPreview}", color = Color.White.copy(0.7f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
            }

            Surface(
                color = if (isMe) Color(0xFF0084FF) else Color(0xFF3A3B3C),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .offset { IntOffset(dragOffset.roundToInt(), 0) }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { _, delta -> dragOffset = (dragOffset + delta).coerceIn(-maxDragPx, maxDragPx) },
                            onDragEnd = { if (abs(dragOffset) > maxDragPx * 0.5f) onReply(); dragOffset = 0f },
                            onDragCancel = { dragOffset = 0f }
                        )
                    }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { setReactionTargetId(if (reactionTargetId == msg.id) null else msg.id) }
                    )
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Ảnh
                    if (msg.imageBytes != null) {
                        val bitmap = remember(msg.imageBytes) { BitmapFactory.decodeByteArray(msg.imageBytes, 0, msg.imageBytes.size) }
                        if (bitmap != null) Image(bitmap.asImageBitmap(), null, modifier = Modifier.widthIn(max=240.dp).heightIn(max=300.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    }

                    // Audio
                    if (msg.audioUrl != null) AudioBubble(msg.audioUrl, isMe)

                    // Text (Có xử lý style cho Call Log)
                    if (msg.text.isNotBlank()) {
                        Text(
                            text = msg.text,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = if (isCallLog) FontWeight.Bold else FontWeight.Normal // In đậm nếu là log cuộc gọi
                        )
                    }

                    // Bản dịch
                    val translated = viewModel.translatedMessages[msg.id]
                    if (translated != null) {
                        HorizontalDivider(color = Color.White.copy(0.3f), modifier = Modifier.padding(vertical = 4.dp))
                        Text(text = translated, color = Color(0xFFFFD700), fontStyle = FontStyle.Italic, fontSize = 14.sp)
                    }
                }
            }

            // Trạng thái đã gửi/đã xem
            if (isMe) {
                Row(modifier = Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    val statusText = if (msg.status == MessageStatus.READ) "Đã xem" else "Đã gửi"
                    val statusIcon = if (msg.status == MessageStatus.READ) Icons.Rounded.DoneAll else Icons.Rounded.Check
                    val statusColor = if (msg.status == MessageStatus.READ) Color.White.copy(0.9f) else Color.White.copy(0.5f)

                    Text(text = statusText, color = Color.White.copy(0.5f), fontSize = 10.sp)
                    Spacer(Modifier.width(2.dp))
                    Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(12.dp))
                }
            }

            // Reaction Picker
            if (reactionTargetId == msg.id) ReactionPicker(reactionEmojis) { emoji -> onReaction(emoji); setReactionTargetId(null) }

            // Nút Dịch (chỉ hiện cho tin nhắn text của đối phương, không phải call log)
            if (!isMe && msg.text.isNotBlank() && msg.audioUrl == null && !isCallLog) {
                Text(text = "Dịch", color = Color.White.copy(0.5f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp).clickable { viewModel.translateMessage(msg) })
            }
        }
    }
}

// === WIDGETS PHỤ TRỢ (InputBar, AudioBubble, AvatarBubble...) GIỮ NGUYÊN ===
@Composable
fun InputBar(text: String, onTextChange: (String) -> Unit, onSend: () -> Unit, onImagePick: () -> Unit, isRecording: Boolean, onStartRecord: () -> Unit, onStopRecord: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(initialValue = 1f, targetValue = if (isRecording) 1.2f else 1f, animationSpec = infiniteRepeatable(animation = tween(500), repeatMode = RepeatMode.Reverse), label = "scale")

    Row(modifier = Modifier.fillMaxWidth().background(Color.Black).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onImagePick) { Icon(Icons.Default.Image, "Image", tint = Color(0xFF0084FF), modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.width(8.dp))
        Row(modifier = Modifier.weight(1f).background(Color(0xFF3A3B3C), RoundedCornerShape(24.dp)).padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isRecording) {
                Icon(Icons.Rounded.Mic, null, tint = Color.Red, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Đang ghi âm...", color = Color.Red, fontSize = 14.sp)
            } else {
                androidx.compose.foundation.text.BasicTextField(value = text, onValueChange = onTextChange, textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 16.sp), modifier = Modifier.weight(1f), decorationBox = { inner -> if (text.isEmpty()) Text("Nhập tin nhắn...", color = Color.Gray, fontSize = 16.sp); inner() })
            }
        }
        Spacer(Modifier.width(8.dp))
        if (text.isNotBlank()) {
            IconButton(onClick = onSend, modifier = Modifier.size(40.dp).background(Color(0xFF0084FF), CircleShape)) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White, modifier = Modifier.size(20.dp)) }
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).scale(scale).background(if (isRecording) Color.Red else Color(0xFF3A3B3C), CircleShape).pointerInput(Unit) { detectTapGestures(onPress = { try { onStartRecord(); awaitRelease() } finally { onStopRecord() } }) }) { Icon(Icons.Rounded.Mic, "Voice", tint = Color.White, modifier = Modifier.size(24.dp)) }
        }
    }
}

@Composable
fun AudioBubble(url: String, isMe: Boolean) {
    var isPlaying by remember { mutableStateOf(false) }
    val mediaPlayer = remember { MediaPlayer() }
    DisposableEffect(url) {
        try { mediaPlayer.setDataSource(url); mediaPlayer.prepareAsync(); mediaPlayer.setOnCompletionListener { isPlaying = false } } catch (e: Exception) {}
        onDispose { try { if (mediaPlayer.isPlaying) mediaPlayer.stop(); mediaPlayer.release() } catch (e: Exception) {} }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (isPlaying) { mediaPlayer.pause(); isPlaying = false } else { mediaPlayer.start(); isPlaying = true } }, modifier = Modifier.size(32.dp).background(Color.White.copy(0.2f), CircleShape)) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(24.dp)) { repeat(10) { Box(modifier = Modifier.padding(horizontal = 1.dp).width(2.dp).height((10..20).random().dp).background(Color.White.copy(0.7f), CircleShape)) } }
    }
}

@Composable
fun ReactionPicker(emojis: List<String>, onPick: (String) -> Unit) {
    Row(modifier = Modifier.padding(top=4.dp).background(Color.Black.copy(0.6f), RoundedCornerShape(20.dp)).padding(6.dp)) { emojis.forEach { Text(text = it, modifier = Modifier.padding(horizontal=4.dp).clickable { onPick(it) }, fontSize = 20.sp) } }
}

@Composable
fun AvatarBubble(initialsName: String?, email: String?, avatarBytes: ByteArray?, size: Dp) {
    val initials = initialsName?.firstOrNull()?.uppercase() ?: email?.firstOrNull()?.uppercase() ?: "U"
    Box(modifier = Modifier.size(size).clip(CircleShape).background(Color.Gray), contentAlignment = Alignment.Center) {
        if (avatarBytes != null) { val bmp = remember(avatarBytes) { BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.size) }; Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) } else { Text(initials, color = Color.White, fontWeight = FontWeight.Bold) }
    }
}