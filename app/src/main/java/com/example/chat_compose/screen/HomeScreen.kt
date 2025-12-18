package com.example.chat_compose.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.chat_compose.model.ChatUser
import com.example.chat_compose.push.PushActions
import com.example.chat_compose.push.RequestNotificationPermissionOnce
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    friends: List<ChatUser>,
    onOpenChat: (String) -> Unit,
    onLogout: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val currentUser = FirebaseAuth.getInstance().currentUser

    // Xin quyền notification Android 13+
    RequestNotificationPermissionOnce()

    val username = remember(currentUser) {
        currentUser?.displayName
            ?: currentUser?.email?.substringBefore("@")
            ?: "username"
    }

    var searchText by remember { mutableStateOf("") }

    val filteredFriends = remember(searchText, friends) {
        if (searchText.isBlank()) friends
        else {
            friends.filter { user ->
                val name = user.displayName.ifBlank { user.email.substringBefore("@") }
                name.contains(searchText, ignoreCase = true) ||
                        user.email.contains(searchText, ignoreCase = true)
            }
        }
    }

    // ===== NEW: unread badge per user =====
    val unreadCounts = remember { mutableStateMapOf<String, Int>() }

    // ===== NEW: snackbar for incoming message while app is open =====
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // ===== NEW: dynamic BroadcastReceiver (không cần exported receiver) =====
    val context = androidx.compose.ui.platform.LocalContext.current
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != PushActions.ACTION_NEW_MESSAGE) return

                val fromId = i.getStringExtra(PushActions.EXTRA_FROM_ID).orEmpty()
                val fromName = i.getStringExtra(PushActions.EXTRA_FROM_NAME).orEmpty()
                val text = i.getStringExtra(PushActions.EXTRA_TEXT).orEmpty()

                if (fromId.isBlank()) return

                // tăng badge
                unreadCounts[fromId] = (unreadCounts[fromId] ?: 0) + 1

                // show snackbar (có nút mở)
                scope.launch {
                    val title = if (fromName.isBlank()) "Tin nhắn mới" else "Tin nhắn mới từ $fromName"
                    val res = snackbarHostState.showSnackbar(
                        message = if (text.isBlank()) title else "$title: $text",
                        actionLabel = "Mở",
                        withDismissAction = true,
                        duration = SnackbarDuration.Short
                    )
                    if (res == SnackbarResult.ActionPerformed) {
                        // clear badge và mở chat
                        unreadCounts.remove(fromId)
                        onOpenChat(fromId)
                    }
                }
            }
        }

        val filter = IntentFilter(PushActions.ACTION_NEW_MESSAGE)

        // API 33+ nên dùng ContextCompat.registerReceiver với NOT_EXPORTED
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = username,
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Cài đặt tài khoản",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = onLogout) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "Đăng xuất",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF000000),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF000000),
        contentColor = Color.White
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF000000))
                .padding(horizontal = 12.dp)
        ) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                placeholder = { Text("Tìm kiếm", color = Color.LightGray) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.LightGray)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color(0xFF1F2933),
                    unfocusedContainerColor = Color(0xFF1F2933),
                    cursorColor = Color.White,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                singleLine = true
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Đoạn chat",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredFriends, key = { it.uid }) { user ->
                    val unread = unreadCounts[user.uid] ?: 0

                    ChatRow(
                        user = user,
                        unreadCount = unread,
                        onClick = {
                            // clear badge khi mở chat
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
private fun ChatRow(
    user: ChatUser,
    unreadCount: Int,
    onClick: () -> Unit
) {
    val displayNameToShow = remember(user.displayName, user.email) {
        if (user.displayName.isNotBlank() && !user.displayName.contains("@")) user.displayName
        else user.email.substringBefore("@")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val bytes = user.avatarBytes

        if (bytes != null) {
            val bitmap = remember(bytes) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }

            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                )
            } else {
                DefaultInitialAvatar(displayNameToShow)
            }
        } else {
            DefaultInitialAvatar(displayNameToShow)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayNameToShow,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // ===== NEW: badge =====
                if (unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Color(0xFFEF4444))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            val statusText = remember(user.isOnline, user.lastSeen) {
                when {
                    user.isOnline -> "Đang hoạt động"
                    user.lastSeen != null -> {
                        val diffMin = ((Date().time - user.lastSeen.time) / 60000L).coerceAtLeast(1)
                        if (diffMin < 60) "Hoạt động $diffMin phút trước"
                        else "Hoạt động ${diffMin / 60} giờ trước"
                    }
                    else -> "Không hoạt động"
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (user.isOnline) Color(0xFF22C55E) else Color.Gray)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = statusText,
                    color = Color.Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DefaultInitialAvatar(displayName: String) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color(0xFF2563EB)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayName.firstOrNull()?.uppercase() ?: "U",
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}
