package com.example.chat_compose.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chat_compose.data.ChatRepository
import com.example.chat_compose.model.ChatUser
import kotlinx.coroutines.launch

private val DarkBackground = Color(0xFF121212)
private val CardBackground = Color(0xFF1E1E24)
private val PrimaryAccent = Color(0xFF6C63FF)
private val TextPrimary = Color(0xFFEEEEEE)
private val TextSecondary = Color(0xFFB0BEC5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    repo: ChatRepository = ChatRepository(),
    onBack: () -> Unit,
    onCreated: (groupId: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val myUid = repo.currentUserId()

    var groupName by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }

    var allUsers by remember { mutableStateOf<List<ChatUser>>(emptyList()) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }

    var isCreating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Lấy danh sách user (friends list của bạn đã bao gồm bot + group; ở đây ta chỉ cần user thật, trừ mình và trừ bot)
    LaunchedEffect(Unit) {
        if (myUid == null) return@LaunchedEffect
        repo.listenFriends(myUid).collect { list ->
            allUsers = list
                .filter { it.uid != myUid }
                .filter { it.uid != ChatRepository.AI_BOT_ID }
                .filter { !it.uid.startsWith(ChatRepository.GROUP_ID_PREFIX) }
        }
    }

    val filtered = remember(search, allUsers) {
        val s = search.trim()
        if (s.isBlank()) allUsers
        else allUsers.filter {
            (it.displayName.ifBlank { it.email.substringBefore("@") })
                .contains(s, ignoreCase = true)
        }
    }

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = { Text("Tạo nhóm", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
        ) {
            Text("Tên nhóm", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it; error = null },
                placeholder = { Text("VD: Nhóm UIT", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CardBackground,
                    unfocusedContainerColor = CardBackground,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = PrimaryAccent
                )
            )

            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Tìm thành viên...", color = TextSecondary) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CardBackground,
                    unfocusedContainerColor = CardBackground,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = PrimaryAccent
                )
            )

            Spacer(Modifier.height(14.dp))

            val selectedCount = selected.values.count { it == true }
            Text(
                text = "Đã chọn: $selectedCount",
                color = TextSecondary,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(10.dp))

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                    Text("Không có người dùng", color = TextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.uid }) { u ->
                        val checked = selected[u.uid] == true
                        MemberRow(
                            user = u,
                            checked = checked,
                            onToggle = {
                                selected[u.uid] = !(selected[u.uid] == true)
                            }
                        )
                    }
                }
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = Color(0xFFFF5252), fontSize = 12.sp)
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (myUid == null) {
                        error = "Chưa đăng nhập"
                        return@Button
                    }
                    val name = groupName.trim()
                    if (name.isBlank()) {
                        error = "Vui lòng nhập tên nhóm"
                        return@Button
                    }
                    val memberIds = selected.filterValues { it }.keys.toList()
                    if (memberIds.isEmpty()) {
                        error = "Vui lòng chọn ít nhất 1 thành viên"
                        return@Button
                    }

                    isCreating = true
                    error = null

                    scope.launch {
                        val res = repo.createGroup(name, memberIds)
                        isCreating = false
                        res.onSuccess { gid -> onCreated(gid) }
                            .onFailure { e -> error = e.message ?: "Tạo nhóm thất bại" }
                    }
                },
                enabled = !isCreating,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryAccent),
                shape = RoundedCornerShape(18.dp)
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                } else {
                    Text("Tạo nhóm", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    user: ChatUser,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val name = user.displayName.ifBlank { user.email.substringBefore("@") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .clickable { onToggle() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(PrimaryAccent.copy(0.25f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "U",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (user.email.isNotBlank()) {
                Text(
                    text = user.email,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = PrimaryAccent,
                uncheckedColor = TextSecondary,
                checkmarkColor = Color.White
            )
        )
    }
}
