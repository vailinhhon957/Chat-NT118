package com.example.chat_compose.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.chat_compose.R
import com.example.chat_compose.data.ChatRepository
import com.example.chat_compose.model.ChatUser
import com.example.chat_compose.push.PushActions
import com.example.chat_compose.push.RequestNotificationPermissionOnce
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

private val DarkBackground = Color(0xFF121212)
private val CardBackground = Color(0xFF1E1E24)
private val PrimaryAccent = Color(0xFF6C63FF)
private val OnlineGreen = Color(0xFF00E676)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextSecondary = Color(0xFFB0BEC5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    friends: List<ChatUser>,
    onOpenChat: (String) -> Unit,
    onLogout: () -> Unit, // giữ để không lỗi chỗ gọi
    onOpenSettings: () -> Unit = {},
    onCreateGroup: () -> Unit = {} // ✅ MỚI
) {
    val currentUser = FirebaseAuth.getInstance().currentUser
    RequestNotificationPermissionOnce()

    val username = remember(currentUser) {
        currentUser?.displayName ?: currentUser?.email?.substringBefore("@") ?: "User"
    }

    var searchText by remember { mutableStateOf("") }
    val filteredFriends = remember(searchText, friends) {
        if (searchText.isBlank()) friends
        else friends.filter {
            (it.displayName.ifBlank { it.email.substringBefore("@") })
                .contains(searchText, ignoreCase = true)
        }
    }

    val unreadCounts = remember { mutableStateMapOf<String, Int>() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Broadcast Receiver Logic
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != PushActions.ACTION_NEW_MESSAGE) return
                val fromId = i.getStringExtra(PushActions.EXTRA_FROM_ID).orEmpty()
                val fromName = i.getStringExtra(PushActions.EXTRA_FROM_NAME).orEmpty()
                val text = i.getStringExtra(PushActions.EXTRA_TEXT).orEmpty()

                if (fromId.isBlank()) return
                unreadCounts[fromId] = (unreadCounts[fromId] ?: 0) + 1

                scope.launch {
                    val res = snackbarHostState.showSnackbar(
                        message = "$fromName: $text",
                        actionLabel = "Xem",
                        duration = SnackbarDuration.Short
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        unreadCounts.remove(fromId)
                        onOpenChat(fromId)
                    }
                }
            }
        }
        val filter = IntentFilter(PushActions.ACTION_NEW_MESSAGE)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = DarkBackground,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "Xin chào,", color = TextSecondary, fontSize = 14.sp)
                    Text(text = username, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    // ✅ Nút tạo nhóm
                    IconButton(
                        onClick = onCreateGroup,
                        modifier = Modifier
                            .background(CardBackground, CircleShape)
                            .border(1.dp, Color.White.copy(0.1f), CircleShape)
                    ) {
                        Icon(Icons.Default.GroupAdd, null, tint = TextPrimary)
                    }

                    // Settings
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .background(CardBackground, CircleShape)
                            .border(1.dp, Color.White.copy(0.1f), CircleShape)
                    ) {
                        Icon(Icons.Default.Settings, null, tint = TextPrimary)
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            TextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Tìm bạn bè...", color = TextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = PrimaryAccent) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(24.dp)),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CardBackground,
                    unfocusedContainerColor = CardBackground,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = PrimaryAccent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                singleLine = true
            )

            Spacer(Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gần đây", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(PrimaryAccent.copy(0.2f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${filteredFriends.size}",
                        color = PrimaryAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(filteredFriends, key = { it.uid }) { user ->
                    val unread = unreadCounts[user.uid] ?: 0
                    ChatCardItem(
                        user = user,
                        unreadCount = unread,
                        onClick = {
                            if (unread > 0) unreadCounts.remove(user.uid)
                            onOpenChat(user.uid)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatCardItem(
    user: ChatUser,
    unreadCount: Int,
    onClick: () -> Unit
) {
    val isBot = user.uid == ChatRepository.AI_BOT_ID

    val displayNameToShow = remember(user.displayName, user.email, isBot) {
        if (isBot) "HuyAn AI"
        else if (user.displayName.isNotBlank()) user.displayName
        else user.email.substringBefore("@")
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                if (isBot) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_ai_bot),
                        contentDescription = "Bot avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(CircleShape)
                    )
                } else {
                    val bytes = user.avatarBytes
                    if (bytes != null) {
                        val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(CircleShape)
                        )
                    } else {
                        DefaultInitialAvatar(displayNameToShow)
                    }
                }

                if (user.isOnline) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(OnlineGreen, CircleShape)
                            .border(2.dp, CardBackground, CircleShape)
                            .align(Alignment.BottomEnd)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayNameToShow,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = "Now", color = TextSecondary.copy(0.7f), fontSize = 11.sp)
                }

                Spacer(Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusText = if (user.isOnline) "Đang hoạt động" else "Offline"
                    Text(
                        text = statusText,
                        color = if (user.isOnline) PrimaryAccent else TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFFFF5252), CircleShape)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultInitialAvatar(name: String) {
    val initial = name.firstOrNull()?.uppercase() ?: "U"
    val randomColor = remember(name) {
        listOf(
            Color(0xFFEF5350),
            Color(0xFF42A5F5),
            Color(0xFF66BB6A),
            Color(0xFFFFA726),
            Color(0xFFAB47BC)
        ).random()
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .background(Brush.linearGradient(listOf(randomColor, randomColor.copy(0.6f))), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(text = initial, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}
