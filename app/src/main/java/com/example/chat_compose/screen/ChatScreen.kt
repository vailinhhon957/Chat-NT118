package com.example.chat_compose.screen

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Paint as AndroidPaint
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.chat_compose.ChatViewModel
import com.example.chat_compose.R
import com.example.chat_compose.data.ChatRepository
import com.example.chat_compose.model.ChatUser
import com.example.chat_compose.model.Message
import com.example.chat_compose.model.MessageStatus
import com.example.chat_compose.push.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

// --- THEME ---
object ChatTheme {
    val MyBubbleGradient = Brush.linearGradient(listOf(Color(0xFF6A11CB), Color(0xFF2575FC)))
    val PartnerBubbleColor = Color(0xFF2D2D3A)
    val EphemeralBubbleColor = Color(0xFF4A148C) // Màu tím đậm cho tin nhắn tự hủy
    val BackgroundGradient = Brush.verticalGradient(
        listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
    )
    val AccentColor = Color(0xFF00C6FF)
    val WarningColor = Color(0xFFFFD700)
    val TextColor = Color.White

    // [MỚI] Các Gradient cảm xúc (Empathy UI)
    val NeutralGradient = Brush.verticalGradient(listOf(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364)))
    val LoveGradient = Brush.verticalGradient(listOf(Color(0xFF4A00E0), Color(0xFF8E2DE2)))   // Tím mộng mơ
    val HappyGradient = Brush.verticalGradient(listOf(Color(0xFFF2994A), Color(0xFFF2C94C)))  // Vàng cam vui vẻ
    val SadGradient = Brush.verticalGradient(listOf(Color(0xFF141E30), Color(0xFF243B55)))    // Xanh đen u buồn (mưa)
    val AngryGradient = Brush.verticalGradient(listOf(Color(0xFFEB3349), Color(0xFFF45C43)))  // Đỏ rực giận dữ
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    partnerId: String,
    onBack: () -> Unit,
    onStartCall: (String, String, String) -> Unit
) {
    val isBotChat = partnerId == ChatRepository.AI_BOT_ID

    val messages = viewModel.messages
    val partner = viewModel.partnerUser
    val replyingTo = viewModel.replyingTo
    val myId = FirebaseAuth.getInstance().currentUser?.uid
    val isPartnerTyping = viewModel.isPartnerTyping

    // [MỚI] Lấy cảm xúc hiện tại từ ViewModel
    val currentSentiment = viewModel.currentSentiment
    val isEphemeralMode = viewModel.isEphemeralMode
    val smartSuggestions = viewModel.smartSuggestions
    val isSmartReplyLoading = viewModel.isSmartReplyLoading

    var text by remember { mutableStateOf("") }
    var reactionTargetId by remember { mutableStateOf<String?>(null) }

    val particles = remember { mutableStateListOf<Particle>() }
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // [MỚI] Logic chọn màu nền dựa trên cảm xúc
    val backgroundBrush = when (currentSentiment) {
        "LOVE" -> ChatTheme.LoveGradient
        "HAPPY" -> ChatTheme.HappyGradient
        "SAD" -> ChatTheme.SadGradient
        "ANGRY" -> ChatTheme.AngryGradient
        else -> ChatTheme.NeutralGradient
    }

    LaunchedEffect(Unit) {
        viewModel.initRecorder(context)
        viewModel.observeTyping(partnerId)
    }

    LaunchedEffect(messages.size, isPartnerTyping) {
        if (messages.isEmpty()) return@LaunchedEffect
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return@LaunchedEffect

        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .filter { it > 0 }
            .first()

        delay(30)
        listState.scrollToItem(0)

        val hasUnreadIncoming = messages.any { m ->
            m.fromId == partnerId && m.toId == myUid && m.status != MessageStatus.READ
        }
        if (hasUnreadIncoming) {
            viewModel.markRead(partnerId)
        }

        val lastMsg = messages.last()

        // Hiệu ứng hạt vẫn giữ cho cả 2 bên (để đẹp mắt)
        if (lastMsg.text.contains("love", true) || lastMsg.text.contains("yêu", true)) {
            triggerParticles(particles, "❤️")
        } else if (lastMsg.text.contains("haha", true) || lastMsg.text.contains("kkk", true)) {
            triggerParticles(particles, "😂")
        } else if (lastMsg.text.contains("sad", true) || lastMsg.text.contains("buồn", true)) {
            triggerParticles(particles, "😢")
        }

        // [QUAN TRỌNG] Logic giữ màu nền theo cảm xúc đối phương
        // Chỉ gọi AI phân tích khi tin nhắn cuối cùng là của PARTNER.
        // Nếu là tin nhắn của MÌNH -> Không gọi AI -> ViewModel giữ nguyên trạng thái cũ -> Màu nền KHÔNG đổi.
        if (lastMsg.fromId == partnerId) {
            viewModel.analyzeLastMessageSentiment()
        }
    }

    // [MỚI] Trigger hiệu ứng hạt khi cảm xúc thay đổi
    LaunchedEffect(currentSentiment) {
        when (currentSentiment) {
            "LOVE" -> triggerParticles(particles, "❤️")
            "HAPPY" -> triggerParticles(particles, "😂")
            "SAD" -> triggerParticles(particles, "💧")
            "ANGRY" -> triggerParticles(particles, "🔥")
        }
    }

    val recordPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) Toast.makeText(context, "Đã cấp quyền! Giữ nút Mic để ghi âm.", Toast.LENGTH_SHORT).show()
            else Toast.makeText(context, "Cần quyền Micro để ghi âm!", Toast.LENGTH_SHORT).show()
        }

    val pickImagesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
            val listBytes = uris.mapNotNull { uri ->
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (listBytes.isNotEmpty()) viewModel.addPendingImages(listBytes)
        }

    DisposableEffect(partnerId) {
        NotificationHelper.currentChatPartnerId = partnerId
        onDispose { NotificationHelper.currentChatPartnerId = null }
    }

    val reactionEmojis = remember { listOf("👍", "❤️", "😂", "😮", "😢", "😡") }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush) // Màu nền sẽ được giữ nguyên (Sticky) cho đến khi Partner đổi cảm xúc
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    reactionTargetId = null
                }
        )

        ParticleSystem(particles)

        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {

            ModernTopBar(
                isBotChat = isBotChat,
                partnerName = if (isBotChat) "HuyAn AI" else (partner?.displayName ?: "Chat"),
                partnerAvatar = partner?.avatarBytes,
                isTyping = isPartnerTyping,
                isOnline = if (isBotChat) true else (partner?.isOnline == true),
                onBack = onBack,
                onCallAudio = { onStartCall(partnerId, partner?.displayName ?: "", "audio") },
                onCallVideo = { onStartCall(partnerId, partner?.displayName ?: "", "video") }
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                reverseLayout = true
            ) {
                if (isPartnerTyping) {
                    item { TypingIndicator(isBotChat = isBotChat, partner = partner) }
                }

                items(items = messages.asReversed(), key = { it.id }) { msg ->
                    AnimatedMessageItem(
                        msg = msg,
                        isMe = msg.fromId == myId,
                        isBotChat = isBotChat,
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
            }

            AnimatedVisibility(
                visible = isSmartReplyLoading || smartSuggestions.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isSmartReplyLoading && smartSuggestions.isEmpty()) {
                        item {
                            SuggestionChip(
                                onClick = {},
                                label = { Text("Đang gợi ý...", color = Color.White.copy(alpha = 0.8f)) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = Color(0xFF2D2D3A).copy(alpha = 0.9f)
                                ),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                                shape = CircleShape
                            )
                        }
                    }

                    items(items = smartSuggestions, key = { it }) { suggestion ->
                        SuggestionChip(
                            onClick = {
                                viewModel.sendMessage(partnerId, suggestion)
                                smartSuggestions.clear()
                            },
                            label = { Text(text = suggestion, color = ChatTheme.AccentColor, fontWeight = FontWeight.Medium) },
                            colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color(0xFF2D2D3A).copy(alpha = 0.9f)),
                            border = BorderStroke(1.dp, ChatTheme.AccentColor.copy(alpha = 0.5f)),
                            shape = CircleShape
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = replyingTo != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                ReplyPreviewBar(replyingTo, onCancel = { viewModel.cancelReply() })
            }

            if (viewModel.pendingImages.isNotEmpty() || viewModel.pendingVoiceFile != null) {
                AttachmentPreviewBar(
                    images = viewModel.pendingImages,
                    hasVoice = viewModel.pendingVoiceFile != null,
                    onRemoveImage = { idx -> viewModel.removePendingImageAt(idx) },
                    onClearVoice = { viewModel.clearPendingVoice() }
                )
            }

            val hasAttachments = viewModel.pendingImages.isNotEmpty() || viewModel.pendingVoiceFile != null

            ModernInputBar(
                text = text,
                onTextChange = { text = it; viewModel.onInputTextChanged(partnerId, it) },
                hasAttachments = hasAttachments,
                isEphemeral = isEphemeralMode,
                onToggleEphemeral = { viewModel.toggleEphemeralMode() },
                onSend = {
                    viewModel.sendBundle(partnerId, text)
                    text = ""
                },
                onImagePick = { pickImagesLauncher.launch("image/*") },
                isRecording = viewModel.isRecording,
                onStartRecord = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startRecording()
                    } else {
                        recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopRecord = { viewModel.stopAndAttachRecording() }
            )
        }
    }
}

// ======================== HELPER COMPONENTS ========================

@Composable
fun AnimatedMessageItem(
    msg: Message, isMe: Boolean, isBotChat: Boolean, partner: ChatUser?, viewModel: ChatViewModel, partnerId: String,
    onReply: () -> Unit, onReaction: (String) -> Unit, reactionTargetId: String?, setReactionTargetId: (String?) -> Unit, reactionEmojis: List<String>
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(visible = visible, enter = slideInHorizontally(initialOffsetX = { if (isMe) it else -it }) + fadeIn()) {
        MessageItemModern(msg, isMe, isBotChat, partner, viewModel, partnerId, onReply, onReaction, reactionTargetId, setReactionTargetId, reactionEmojis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItemModern(
    msg: Message, isMe: Boolean, isBotChat: Boolean, partner: ChatUser?, viewModel: ChatViewModel, partnerId: String,
    onReply: () -> Unit, onReaction: (String) -> Unit, reactionTargetId: String?, setReactionTargetId: (String?) -> Unit, reactionEmojis: List<String>
) {
    val density = LocalDensity.current
    val maxDragPx = with(density) { 60.dp.toPx() }
    var dragOffset by remember { mutableStateOf(0f) }

    val isCallLog = msg.text.startsWith("📞") || msg.text.startsWith("📹")

    val bubbleBrush = when {
        msg.expiryTime != null -> SolidColor(ChatTheme.EphemeralBubbleColor)
        isMe && !isCallLog -> ChatTheme.MyBubbleGradient
        isCallLog -> Brush.linearGradient(listOf(Color.DarkGray, Color.Gray))
        else -> SolidColor(ChatTheme.PartnerBubbleColor)
    }

    val bubbleShape = if (isMe) RoundedCornerShape(18.dp, 18.dp, 18.dp, 2.dp) else RoundedCornerShape(18.dp, 18.dp, 2.dp, 18.dp)

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(dragOffset.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, delta -> dragOffset = (dragOffset + delta).coerceIn(-maxDragPx, maxDragPx) },
                        onDragEnd = { if (abs(dragOffset) > maxDragPx * 0.5f) onReply(); dragOffset = 0f },
                        onDragCancel = { dragOffset = 0f }
                    )
                },
            horizontalArrangement = if (isMe) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            if (!isMe) {
                BotOrUserAvatar(isBotChat, partner, 28.dp)
                Spacer(Modifier.width(8.dp))
            }

            Column(horizontalAlignment = if (isMe) Alignment.End else Alignment.Start) {
                if (msg.replyPreview != null) {
                    Text(text = "↳ ${msg.replyPreview}", color = Color.White.copy(0.6f), fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp, start = 4.dp))
                }

                Box {
                    Box(
                        modifier = Modifier
                            .background(brush = bubbleBrush, shape = bubbleShape)
                            .shadow(4.dp, bubbleShape)
                            .combinedClickable(onClick = {}, onLongClick = { setReactionTargetId(if (reactionTargetId == msg.id) null else msg.id) })
                            .padding(12.dp)
                    ) {
                        Column {
                            if (msg.expiryTime != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                    Icon(Icons.Rounded.HourglassBottom, contentDescription = null, tint = ChatTheme.WarningColor, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Tự hủy sau 10p", fontSize = 10.sp, color = ChatTheme.WarningColor, fontStyle = FontStyle.Italic)
                                }
                            }

                            if (msg.imageBytes != null) {
                                val bitmap = remember(msg.imageBytes) { BitmapFactory.decodeByteArray(msg.imageBytes, 0, msg.imageBytes.size) }
                                Image(bitmap = bitmap.asImageBitmap(), null, modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                                Spacer(Modifier.height(8.dp))
                            }

                            if (msg.audioUrl != null) AudioBubble(msg.audioUrl, isMe)

                            if (msg.text.isNotBlank()) {
                                Text(text = msg.text, color = Color.White, fontSize = 16.sp, fontWeight = if (isCallLog) FontWeight.Bold else FontWeight.Normal)
                            }

                            val translated = viewModel.translatedMessages[msg.id]
                            if (translated != null) {
                                HorizontalDivider(color = Color.White.copy(0.2f), modifier = Modifier.padding(vertical = 6.dp))
                                Text(translated, color = ChatTheme.WarningColor, fontStyle = FontStyle.Italic, fontSize = 14.sp)
                            }
                        }
                    }
                    if (msg.reactions.isNotEmpty()) {
                        Row(modifier = Modifier.align(if (isMe) Alignment.BottomStart else Alignment.BottomEnd).offset(y = 12.dp).background(Color.Black.copy(0.6f), CircleShape).border(1.dp, Color.White.copy(0.2f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            msg.reactions.values.distinct().take(3).forEach { Text(it, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 1.dp)) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    if (isMe) {
                        val statusIcon = if (msg.status == MessageStatus.READ) Icons.Rounded.DoneAll else Icons.Rounded.Check
                        val statusColor = if (msg.status == MessageStatus.READ) ChatTheme.AccentColor else Color.White.copy(0.4f)
                        Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(14.dp))
                    } else if (msg.text.isNotBlank() && !isCallLog) {
                        Text("Dịch", color = ChatTheme.AccentColor, fontSize = 11.sp, modifier = Modifier.clickable { viewModel.translateMessage(msg) })
                    }
                }
            }
        }
        if (dragOffset != 0f) Icon(Icons.Rounded.Reply, null, tint = Color.White, modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).alpha(abs(dragOffset) / maxDragPx))
        if (reactionTargetId == msg.id) PopupReactionPicker(emojis = reactionEmojis, onPick = { emoji -> onReaction(emoji); setReactionTargetId(null) }, modifier = Modifier.align(if (isMe) Alignment.TopEnd else Alignment.TopStart).offset(y = (-40).dp))
    }
}

@Composable
fun ModernTopBar(
    isBotChat: Boolean,
    partnerName: String,
    partnerAvatar: ByteArray?,
    isTyping: Boolean,
    isOnline: Boolean,
    onBack: () -> Unit,
    onCallAudio: () -> Unit,
    onCallVideo: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(Color.White.copy(0.1f), RoundedCornerShape(20.dp)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Spacer(Modifier.width(8.dp))
        Box {
            BotOrUserAvatar(isBotChat, null, 40.dp)
            if (!isBotChat) AvatarBubble(null, null, partnerAvatar, 40.dp)
            if (isOnline) Box(modifier = Modifier.size(12.dp).background(Color(0xFF4CAF50), CircleShape).border(2.dp, Color.Black, CircleShape).align(Alignment.BottomEnd))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(partnerName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(if (isTyping) "Đang soạn tin..." else if (isOnline) "Đang hoạt động" else "Offline", color = if (isTyping) ChatTheme.AccentColor else Color.White.copy(0.6f), fontSize = 12.sp)
        }
        if (!isBotChat) { IconButton(onClick = onCallAudio) { Icon(Icons.Rounded.Call, null, tint = ChatTheme.AccentColor) }; IconButton(onClick = onCallVideo) { Icon(Icons.Rounded.Videocam, null, tint = ChatTheme.AccentColor) } }
    }
}

@Composable
fun BotOrUserAvatar(isBotChat: Boolean, partner: ChatUser?, size: Dp) {
    if (isBotChat) {
        Image(painterResource(R.drawable.ic_ai_bot), null, modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        AvatarBubble(partner?.displayName, partner?.email, partner?.avatarBytes, size)
    }
}

@Composable
fun AttachmentPreviewBar(images: List<ByteArray>, hasVoice: Boolean, onRemoveImage: (Int) -> Unit, onClearVoice: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).background(Color(0xFF1E1E28), RoundedCornerShape(16.dp)).padding(10.dp)) {
        if (images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(images.size) { idx ->
                    val b = images[idx]; val bmp = remember(b) { BitmapFactory.decodeByteArray(b, 0, b.size) }
                    Box { Image(bitmap = bmp.asImageBitmap(), null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop); Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).background(Color.Black.copy(0.6f), CircleShape).clickable { onRemoveImage(idx) }.padding(2.dp)) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (hasVoice) { Row(modifier = Modifier.fillMaxWidth().background(Color.Black.copy(0.25f), RoundedCornerShape(12.dp)).padding(10.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Mic, null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text("Ghi âm đính kèm", color = Color.White, modifier = Modifier.weight(1f)); Text("Xoá", color = ChatTheme.AccentColor, modifier = Modifier.clickable { onClearVoice() }) } }
    }
}

@Composable
fun ReplyPreviewBar(replyingTo: Message?, onCancel: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E28)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(4.dp).height(32.dp).background(ChatTheme.AccentColor, CircleShape)); Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { Text("Đang trả lời...", color = ChatTheme.AccentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold); Text(replyingTo?.text ?: "Tệp đính kèm", color = Color.Gray, maxLines = 1, fontSize = 13.sp) }
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, null, tint = Color.Gray) }
    }
}

@Composable fun AudioBubble(url: String, isMe: Boolean) {
    var isPlaying by remember { mutableStateOf(false) }
    val mediaPlayer = remember { MediaPlayer() }
    DisposableEffect(url) {
        try { mediaPlayer.setDataSource(url); mediaPlayer.prepareAsync(); mediaPlayer.setOnCompletionListener { isPlaying = false } } catch (_: Exception) {}
        onDispose { try { if (mediaPlayer.isPlaying) mediaPlayer.stop(); mediaPlayer.release() } catch (_: Exception) {} }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (isPlaying) { mediaPlayer.pause(); isPlaying = false } else { mediaPlayer.start(); isPlaying = true } }, modifier = Modifier.size(32.dp).background(Color.White.copy(0.2f), CircleShape)) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(24.dp)) { repeat(10) { Box(modifier = Modifier.padding(horizontal = 1.dp).width(2.dp).height((10..20).random().dp).background(Color.White.copy(0.7f), CircleShape)) } }
    }
}

@Composable fun AvatarBubble(initialsName: String?, email: String?, avatarBytes: ByteArray?, size: Dp) {
    Box(modifier = Modifier.size(size).clip(CircleShape).background(Color.Gray), contentAlignment = Alignment.Center) {
        if (avatarBytes != null) { val bmp = remember(avatarBytes) { BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.size) }; Image(bitmap = bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        else { Text(initialsName?.firstOrNull()?.uppercase() ?: email?.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold) }
    }
}

@Composable fun TypingIndicator(isBotChat: Boolean, partner: ChatUser?) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)) {
        BotOrUserAvatar(isBotChat, partner, 28.dp)
        Spacer(Modifier.width(8.dp))
        Row(modifier = Modifier.background(Color(0xFF2D2D3A), RoundedCornerShape(18.dp)).padding(12.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val dotSize = 6.dp
            @Composable fun BouncingDot(delayMs: Int) {
                val infiniteTransition = rememberInfiniteTransition(label = "")
                val dy by infiniteTransition.animateFloat(initialValue = 0f, targetValue = -10f, animationSpec = infiniteRepeatable(animation = tween(600, delayMillis = delayMs, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse), label = "")
                Box(modifier = Modifier.offset(y = dy.dp).size(dotSize).background(Color.White.copy(0.7f), CircleShape))
            }
            BouncingDot(0); BouncingDot(300); BouncingDot(600)
        }
    }
}

@Composable
fun ModernInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    hasAttachments: Boolean,
    isEphemeral: Boolean,
    onToggleEphemeral: () -> Unit,
    onSend: () -> Unit,
    onImagePick: () -> Unit,
    isRecording: Boolean,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit
) {
    val canSend = text.isNotBlank() || hasAttachments

    // State điều khiển Menu
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isEphemeral) Color(0xFF2A0033) else Color.Transparent)
            .animateContentSize()
    ) {
        // Banner hiển thị khi đang bật chế độ tự hủy
        AnimatedVisibility(visible = isEphemeral) {
            Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.Timer, null, tint = ChatTheme.WarningColor, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Tin nhắn tự hủy sau 10p", color = ChatTheme.WarningColor, fontSize = 11.sp)
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
            // [MỚI] Nút CỘNG (+) chứa menu
            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.background(Color(0xFF2D2D3A), CircleShape).size(44.dp)
                ) {
                    val rotation by animateFloatAsState(targetValue = if (showMenu) 45f else 0f, label = "rotation")
                    Icon(Icons.Rounded.Add, null, tint = ChatTheme.AccentColor, modifier = Modifier.rotate(rotation))
                }

                // Dropdown Menu màu tối
                MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))) {
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier
                            .background(Color(0xFF2D2D3A))
                            .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(16.dp))
                    ) {
                        DropdownMenuItem(
                            text = { Text("Gửi ảnh", color = Color.White) },
                            leadingIcon = { Icon(Icons.Rounded.Image, null, tint = ChatTheme.AccentColor) },
                            onClick = { showMenu = false; onImagePick() }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(if (isEphemeral) "Tắt tự hủy" else "Bật tự hủy", color = if (isEphemeral) ChatTheme.WarningColor else Color.White)
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.Timer, null, tint = if (isEphemeral) ChatTheme.WarningColor else Color.White)
                            },
                            onClick = { showMenu = false; onToggleEphemeral() }
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // Ô Nhập văn bản
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF2D2D3A), RoundedCornerShape(22.dp))
                    .border(1.dp, if (isEphemeral) ChatTheme.WarningColor else Color.White.copy(0.1f), RoundedCornerShape(22.dp)),
                contentAlignment = Alignment.CenterStart
            ) {
                if (isRecording) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Mic, null, tint = Color.Red)
                        Spacer(Modifier.width(8.dp))
                        Text("Đang ghi âm...", color = Color.Red)
                    }
                } else {
                    TextField(
                        value = text,
                        onValueChange = onTextChange,
                        placeholder = { Text(if (isEphemeral) "Nhập tin bí mật..." else "Nhập tin nhắn...", color = Color.Gray) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White
                        ),
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Nút Ghi âm
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF2D2D3A), CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = { try { onStartRecord(); awaitRelease() } finally { onStopRecord() } })
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Mic, null, tint = if (isRecording) Color.Red else Color.White, modifier = Modifier.size(22.dp))
            }

            Spacer(Modifier.width(8.dp))

            // Nút Gửi
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (canSend) ChatTheme.MyBubbleGradient else Brush.linearGradient(listOf(Color(0xFF2D2D3A), Color(0xFF2D2D3A))),
                        CircleShape
                    )
                    .then(if (canSend) Modifier.clickable { onSend() } else Modifier.alpha(0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable fun PopupReactionPicker(emojis: List<String>, onPick: (String) -> Unit, modifier: Modifier) { Row(modifier = modifier.background(Color(0xFF2D2D3A), RoundedCornerShape(24.dp)).border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(24.dp)).padding(8.dp).shadow(8.dp)) { emojis.forEach { emoji -> Text(text = emoji, fontSize = 22.sp, modifier = Modifier.padding(horizontal = 6.dp).scale(1.2f).clickable { onPick(emoji) }) } } }
@Composable fun HorizontalDivider(modifier: Modifier = Modifier, thickness: Dp = 1.dp, color: Color = Color.Gray) { Box(modifier.fillMaxWidth().height(thickness).background(color = color)) }
data class Particle(val id: Long, val emoji: String, val startX: Float, val startY: Float, var y: Float, var alpha: Float = 1f, val speed: Float, val scale: Float)
fun triggerParticles(list: MutableList<Particle>, emoji: String) { repeat(15) { list.add(Particle(System.nanoTime() + it, emoji, Random.nextInt(100, 900).toFloat(), 2000f, 2000f, 1f, Random.nextFloat() * 15f + 10f, Random.nextFloat() * 1.5f + 0.5f)) } }
@Composable fun ParticleSystem(particles: MutableList<Particle>) { LaunchedEffect(Unit) { while (true) { withFrameMillis { val iterator = particles.iterator(); while (iterator.hasNext()) { val p = iterator.next(); p.y -= p.speed; p.alpha -= 0.005f; if (p.alpha <= 0f || p.y < -100f) iterator.remove() } } } } ; Canvas(modifier = Modifier.fillMaxSize().pointerInput(Unit) {}) { particles.forEach { p -> drawIntoCanvas { canvas -> val paint = AndroidPaint().apply { textSize = 60f * p.scale; alpha = (p.alpha * 255).toInt() }; canvas.nativeCanvas.drawText(p.emoji, p.startX, p.y, paint) } } } }