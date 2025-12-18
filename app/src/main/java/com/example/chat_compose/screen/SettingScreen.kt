package com.example.chat_compose.screen

import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.chat_compose.model.ChatUser
import com.example.chat_compose.model.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val vm: SettingsViewModel = viewModel()
    val user by vm.currentUser.collectAsState()

    val birthMillis by vm.birthDateMillis.collectAsState()
    val gender by vm.gender.collectAsState()

    val isUpdating by vm.isUpdatingAvatar.collectAsState()
    val isSaving by vm.isSavingProfile.collectAsState()
    val isChangingPass by vm.isChangingPassword.collectAsState()

    val error by vm.error.collectAsState()
    val success by vm.success.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // ===== MÀU NỀN =====
    val pageBg = Color.Black
    val graySurface = Color(0xFF2A2A2A)   // xám cho các chỗ trắng (topbar + card)
    val avatarGray = Color(0xFFBDBDBD)   // xám cho nền avatar (thay trắng)

    // hiển thị snackbar
    LaunchedEffect(error, success) {
        val msg = success ?: error
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            vm.clearMessages()
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { vm.updateAvatar(it) }
    }

    val dateFmt = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val birthText = birthMillis?.let { dateFmt.format(Date(it)) } ?: ""

    // Dialog states
    var showBirthDialog by remember { mutableStateOf(false) }
    var showGenderDialog by remember { mutableStateOf(false) }
    var showChangePassDialog by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = pageBg,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt tài khoản") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                // đổi nền topbar từ trắng -> xám
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = graySurface,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBg)
                .padding(padding)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== Header (avatar + email) =====
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                // đổi nền card (chỗ sáng/trắng) -> xám
                colors = CardDefaults.cardColors(containerColor = graySurface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        UserAvatarBig(
                            user = user,
                            avatarBg = avatarGray, // đổi nền avatar từ trắng -> xám
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                        )
                        IconButton(
                            onClick = { if (!isUpdating) pickImageLauncher.launch("image/*") },
                            enabled = !isUpdating
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = "Change avatar", tint = Color.White)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = user?.displayName ?: user?.email?.substringBefore("@") ?: "Người dùng",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = user?.email ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCCCCCC)
                    )
                }
            }

            // ===== Hồ sơ cá nhân =====
            SettingsSection(
                title = "Hồ sơ cá nhân",
                cardBg = graySurface
            ) {
                SettingsItem(
                    icon = Icons.Default.CalendarMonth,
                    iconBg = Color(0xFFFF4D8D),
                    title = "Ngày sinh",
                    value = if (birthText.isBlank()) "Chưa đặt" else birthText,
                    onClick = { showBirthDialog = true }
                )

                SettingsDivider()

                SettingsItem(
                    icon = Icons.Default.Person,
                    iconBg = Color(0xFF7C4DFF),
                    title = "Giới tính",
                    value = gender.ifBlank { "Khác" },
                    onClick = { showGenderDialog = true }
                )
            }

            // ===== Tài khoản =====
            SettingsSection(
                title = "Tài khoản",
                cardBg = graySurface
            ) {
                SettingsItem(
                    icon = Icons.Default.Lock,
                    iconBg = Color(0xFF22C55E),
                    title = "Đổi mật khẩu",
                    value = "",
                    onClick = { showChangePassDialog = true }
                )

                SettingsDivider()

                SettingsItem(
                    icon = Icons.Default.Logout,
                    iconBg = Color(0xFFEF4444),
                    title = "Đăng xuất",
                    value = "",
                    titleColor = Color(0xFFEF4444),
                    onClick = { showLogoutConfirm = true }
                )
            }

            // Loading line
            if (isUpdating || isSaving || isChangingPass) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // ===== Dialog: Ngày sinh (nhập được + chọn được) =====
    if (showBirthDialog) {
        BirthDateDialog(
            initialText = birthText,
            onDismiss = { showBirthDialog = false },
            onPickDate = {
                val cal = Calendar.getInstance()
                birthMillis?.let { cal.timeInMillis = it }
                DatePickerDialog(
                    context,
                    { _, y, m, d ->
                        val c = Calendar.getInstance()
                        c.set(Calendar.YEAR, y)
                        c.set(Calendar.MONTH, m)
                        c.set(Calendar.DAY_OF_MONTH, d)
                        c.set(Calendar.HOUR_OF_DAY, 0)
                        c.set(Calendar.MINUTE, 0)
                        c.set(Calendar.SECOND, 0)
                        c.set(Calendar.MILLISECOND, 0)
                        vm.setBirthDate(c.timeInMillis)
                        vm.saveProfile()
                        showBirthDialog = false
                    },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH)
                ).show()
            },
            onSubmitText = { ddMMyyyy ->
                val millis = parseDdMmYyyyToMillis(ddMMyyyy)
                if (millis == null) {
                    vm.clearMessages()
                    return@BirthDateDialog
                } else {
                    vm.setBirthDate(millis)
                    vm.saveProfile()
                    showBirthDialog = false
                }
            }
        )
    }

    // ===== Dialog: Giới tính =====
    if (showGenderDialog) {
        GenderDialog(
            current = gender.ifBlank { "Khác" },
            onDismiss = { showGenderDialog = false },
            onSelect = { g ->
                vm.setGender(g)
                vm.saveProfile()
                showGenderDialog = false
            }
        )
    }

    // ===== Dialog: Đổi mật khẩu =====
    if (showChangePassDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePassDialog = false },
            isLoading = isChangingPass,
            onSubmit = { currentPass, newPass ->
                vm.changePassword(currentPass, newPass)
                showChangePassDialog = false
            }
        )
    }

    // ===== Confirm logout =====
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Đăng xuất") },
            text = { Text("Bạn có chắc muốn đăng xuất không?") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) { Text("Đăng xuất") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("Hủy") }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    cardBg: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF777777),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg) // nền xám
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    title: String,
    value: String,
    onClick: () -> Unit,
    titleColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = titleColor, fontWeight = FontWeight.Medium)
        }

        if (value.isNotBlank()) {
            Text(value, color = Color(0xFFCCCCCC))
            Spacer(Modifier.width(6.dp))
        }

        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFFCCCCCC))
    }
}

@Composable
private fun SettingsDivider() {
    Divider(
        color = Color(0xFF3A3A3A),
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 14.dp)
    )
}

@Composable
private fun BirthDateDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onPickDate: () -> Unit,
    onSubmitText: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ngày sinh") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bạn có thể nhập tay (dd/MM/yyyy) hoặc chọn từ lịch.")
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        localError = null
                    },
                    label = { Text("dd/MM/yyyy") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(
                    onClick = onPickDate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Chọn từ lịch")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = text.trim()
                if (s.isBlank()) {
                    localError = "Vui lòng nhập ngày sinh"
                    return@TextButton
                }
                if (parseDdMmYyyyToMillis(s) == null) {
                    localError = "Sai định dạng. Ví dụ: 20/07/2005"
                    return@TextButton
                }
                onSubmitText(s)
            }) { Text("Lưu") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

@Composable
private fun GenderDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val options = listOf("Nam", "Nữ", "Khác")
    var selected by remember { mutableStateOf(current.ifBlank { "Khác" }) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Giới tính") },
        text = {
            Column {
                options.forEach { opt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = opt }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (selected == opt),
                            onClick = { selected = opt }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(opt)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(selected) }) { Text("Lưu") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Hủy") }
        }
    )
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    isLoading: Boolean,
    onSubmit: (currentPassword: String, newPassword: String) -> Unit
) {
    var currentPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var newPass2 by remember { mutableStateOf("") }

    var showNew by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Đổi mật khẩu") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                OutlinedTextField(
                    value = currentPass,
                    onValueChange = { currentPass = it; localError = null },
                    label = { Text("Mật khẩu hiện tại") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = newPass,
                    onValueChange = { newPass = it; localError = null },
                    label = { Text("Mật khẩu mới") },
                    singleLine = true,
                    visualTransformation = if (showNew) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = newPass2,
                    onValueChange = { newPass2 = it; localError = null },
                    label = { Text("Nhập lại mật khẩu mới") },
                    singleLine = true,
                    visualTransformation = if (showNew) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showNew, onCheckedChange = { showNew = it })
                    Spacer(Modifier.width(6.dp))
                    Text("Hiện mật khẩu")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isLoading,
                onClick = {
                    if (currentPass.isBlank() || newPass.isBlank() || newPass2.isBlank()) {
                        localError = "Vui lòng nhập đầy đủ"
                        return@TextButton
                    }
                    if (newPass.length < 6) {
                        localError = "Mật khẩu mới phải ít nhất 6 ký tự"
                        return@TextButton
                    }
                    if (newPass != newPass2) {
                        localError = "Mật khẩu nhập lại không khớp"
                        return@TextButton
                    }
                    onSubmit(currentPass, newPass)
                }
            ) { Text("Lưu") }
        },
        dismissButton = {
            TextButton(enabled = !isLoading, onClick = onDismiss) { Text("Hủy") }
        }
    )
}

@Composable
private fun UserAvatarBig(
    user: ChatUser?,
    avatarBg: Color,
    modifier: Modifier = Modifier
) {
    val bytes = user?.avatarBytes

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(avatarBg), // đổi nền avatar từ trắng -> xám
        contentAlignment = Alignment.Center
    ) {
        if (bytes != null) {
            val bitmap = remember(bytes) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Avatar",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = user?.displayName?.firstOrNull()?.uppercase()
                        ?: user?.email?.firstOrNull()?.uppercase()
                        ?: "?",
                    color = Color.Black
                )
            }
        } else {
            Text(
                text = user?.displayName?.firstOrNull()?.uppercase()
                    ?: user?.email?.firstOrNull()?.uppercase()
                    ?: "?",
                color = Color.Black
            )
        }
    }
}

private fun parseDdMmYyyyToMillis(input: String): Long? {
    val regex = Regex("""^\d{2}/\d{2}/\d{4}$""")
    if (!regex.matches(input.trim())) return null

    return try {
        val parts = input.trim().split("/")
        val d = parts[0].toInt()
        val m = parts[1].toInt()
        val y = parts[2].toInt()

        val cal = Calendar.getInstance()
        cal.isLenient = false
        cal.set(Calendar.YEAR, y)
        cal.set(Calendar.MONTH, m - 1)
        cal.set(Calendar.DAY_OF_MONTH, d)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    } catch (_: Exception) {
        null
    }
}
