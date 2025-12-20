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

// COPY LẠI MÃ MÀU
private val DarkBackground = Color(0xFF0F111E)
private val InputBackground = Color(0xFF1F2232)
private val PrimaryPurple = Color(0xFF7F56D9)
private val TextWhite = Color(0xFFFFFFFF)
private val TextGrey = Color(0xFF8E93A3)

@Composable
fun RegisterScreen(
    authViewModel: AuthViewModel,
    onBack: () -> Unit,
    onRegisterSuccess: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var showPass by rememberSaveable { mutableStateOf(false) }

    val isLoading = authViewModel.isLoading
    val vmError = authViewModel.error
    var localError by remember { mutableStateOf<String?>(null) }
    val errorToShow = localError ?: vmError

    // --- LOGIC GOOGLE SIGN IN (COPY GIỐNG LOGIN) ---
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
                    Log.d("GoogleRegister", "Token: $idToken")
                    authViewModel.loginWithGoogle(idToken) { onRegisterSuccess() }
                } else {
                    localError = "Không lấy được Google Token"
                }
            } catch (e: ApiException) {
                localError = "Lỗi Google: ${e.message}"
            }
        }
    }
    // ---------------------------------------------

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
                contentDescription = null,
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

            // TIÊU ĐỀ TIẾNG VIỆT
            Text(
                text = "Tạo Tài Khoản Mới",
                color = TextWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tham gia cùng chúng tôi để kết nối\nvà trải nghiệm ngay hôm nay.",
                color = TextGrey,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            // INPUTS
            DarkInputField(
                label = "Tên hiển thị",
                value = name,
                onValueChange = { name = it; localError = null },
                placeholder = "Nguyễn Văn A",
                imeAction = ImeAction.Next
            )

            Spacer(modifier = Modifier.height(16.dp))

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

            Spacer(modifier = Modifier.height(30.dp))

            // BUTTON ĐĂNG KÝ
            Button(
                onClick = {
                    val n = name.trim()
                    val e = email.trim()

                    if (n.isBlank() || e.isBlank() || pass.isBlank()) {
                        localError = "Vui lòng nhập đầy đủ thông tin"
                        return@Button
                    }
                    if (!isValidEmail(e)) {
                        localError = "Email không đúng định dạng"
                        return@Button
                    }
                    if (pass.length < 6) {
                        localError = "Mật khẩu quá ngắn (tối thiểu 6 ký tự)"
                        return@Button
                    }

                    authViewModel.register(e, pass, n) { onRegisterSuccess() }
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
                    Text("Đăng Ký", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // ĐÃ XÓA PHẦN REMEMBER ME & FORGOT PASSWORD Ở ĐÂY

            Spacer(modifier = Modifier.height(30.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Divider(modifier = Modifier.weight(1f), color = Color(0xFF2A2E3E))
                Text("Hoặc", color = TextGrey, modifier = Modifier.padding(horizontal = 16.dp))
                Divider(modifier = Modifier.weight(1f), color = Color(0xFF2A2E3E))
            }

            Spacer(modifier = Modifier.height(24.dp))

            SocialLoginButton(text = "Đăng ký bằng Google", iconRes = "G") {
                localError = null
                googleLauncher.launch(googleSignInClient.signInIntent)
            }

            Spacer(modifier = Modifier.height(40.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Đã có tài khoản? ", color = TextGrey)
                Text(
                    text = "Đăng nhập",
                    color = PrimaryPurple,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onBack() }
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}