package com.example.chat_compose.screen

import android.app.Activity
import android.util.Log
import android.util.Patterns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chat_compose.AuthViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException

// --- MÃ MÀU ---
private val DarkBackground = Color(0xFF0F111E)
private val InputBackground = Color(0xFF1F2232)
private val PrimaryPurple = Color(0xFF7F56D9)
private val TextWhite = Color(0xFFFFFFFF)
private val TextGrey = Color(0xFF8E93A3)

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onGoRegister: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var showPass by rememberSaveable { mutableStateOf(false) }
    // Đã xóa biến rememberMe

    val isLoading = authViewModel.isLoading
    val vmError = authViewModel.error
    var localError by remember { mutableStateOf<String?>(null) }
    val errorToShow = localError ?: vmError

    // --- LOGIC GOOGLE SIGN IN ---
    val context = LocalContext.current
    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken("YOUR_WEB_CLIENT_ID_FROM_FIREBASE_CONSOLE")
            .build()
    }

    val googleSignInClient = remember { GoogleSignIn.getClient(context, gso) }

    val googleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken != null) {
                    Log.d("GoogleLogin", "Token: $idToken")
                    authViewModel.loginWithGoogle(idToken) { onLoginSuccess() }
                } else {
                    localError = "Không lấy được Google Token"
                }
            } catch (e: ApiException) {
                localError = "Đăng nhập Google thất bại: ${e.message}"
            }
        }
    }
    // ---------------------------

    fun isValidEmail(s: String) = Patterns.EMAIL_ADDRESS.matcher(s.trim()).matches()

    val bgBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF2D2548), DarkBackground, DarkBackground)
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgBrush)
            .padding(horizontal = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            // LOGO
            Icon(
                imageVector = Icons.Rounded.ChatBubble,
                contentDescription = "Logo",
                tint = PrimaryPurple,
                modifier = Modifier.size(60.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "HUYAN CHAT",
                color = TextWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(30.dp))

            // HEADING TIẾNG VIỆT
            Text(
                text = "Đăng Nhập Tài Khoản",
                color = TextWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Truy cập tài khoản để quản lý cài đặt\nvà khám phá các tính năng.",
                color = TextGrey,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            // INPUTS
            DarkInputField(
                label = "Email",
                value = email,
                onValueChange = { email = it; localError = null },
                placeholder = "nguyenvan.a@example.com",
                keyboardType = KeyboardType.Email
            )

            Spacer(modifier = Modifier.height(16.dp))

            DarkInputField(
                label = "Mật khẩu",
                value = pass,
                onValueChange = { pass = it; localError = null },
                placeholder = "••••••••",
                isPassword = true,
                isPasswordVisible = showPass,
                onTogglePassword = { showPass = !showPass },
                imeAction = ImeAction.Done
            )

            if (errorToShow != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = errorToShow, color = Color.Red, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // BUTTON LOGIN
            Button(
                onClick = {
                    if (email.isBlank() || pass.isBlank()) {
                        localError = "Vui lòng nhập đầy đủ thông tin"
                        return@Button
                    }
                    if (!isValidEmail(email)) {
                        localError = "Email không đúng định dạng"
                        return@Button
                    }
                    authViewModel.login(email.trim(), pass) { onLoginSuccess() }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
                shape = RoundedCornerShape(30.dp),
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                } else {
                    Text("Đăng Nhập", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // QUÊN MẬT KHẨU (Đã xóa Remember Me, chỉ giữ Quên mật khẩu căn phải)
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                TextButton(onClick = { /* Xử lý quên mật khẩu */ }) {
                    Text("Quên mật khẩu?", color = PrimaryPurple, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            // OR DIVIDER
            Row(verticalAlignment = Alignment.CenterVertically) {
                Divider(modifier = Modifier.weight(1f), color = Color(0xFF2A2E3E))
                Text(
                    text = "Hoặc",
                    color = TextGrey,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    fontSize = 14.sp
                )
                Divider(modifier = Modifier.weight(1f), color = Color(0xFF2A2E3E))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // GOOGLE BUTTON
            SocialLoginButton(text = "Đăng nhập bằng Google", iconRes = "G") {
                localError = null
                googleLauncher.launch(googleSignInClient.signInIntent)
            }

            Spacer(modifier = Modifier.height(40.dp))

            // FOOTER
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Chưa có tài khoản? ", color = TextGrey)
                Text(
                    text = "Đăng ký ngay",
                    color = PrimaryPurple,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onGoRegister() }
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

// --- CÁC COMPONENT DÙNG CHUNG (Giữ nguyên logic, chỉ là copy lại cho đủ file) ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    isPasswordVisible: Boolean = false,
    onTogglePassword: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = TextWhite,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = InputBackground,
                unfocusedContainerColor = InputBackground,
                disabledContainerColor = InputBackground,
                cursorColor = PrimaryPurple,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite
            ),
            singleLine = true,
            visualTransformation = if (isPassword && !isPasswordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = onTogglePassword) {
                        Icon(
                            imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = TextGrey
                        )
                    }
                }
            } else null
        )
    }
}

@Composable
fun SocialLoginButton(text: String, iconRes: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = InputBackground),
        shape = RoundedCornerShape(30.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2E3E))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = iconRes,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (iconRes == "G") Color(0xFFDB4437) else Color.White
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = text, color = TextWhite, fontSize = 15.sp)
        }
    }
}