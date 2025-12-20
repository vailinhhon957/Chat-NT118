package com.example.chat_compose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.chat_compose.data.CallRepository
import com.example.chat_compose.data.ChatRepository
import com.example.chat_compose.push.NotificationHelper
import com.example.chat_compose.screen.*
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import android.util.Log
class MainActivity : ComponentActivity() {

    private val openChatPartnerIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        openChatPartnerIdState.value =
            intent?.getStringExtra(NotificationHelper.EXTRA_OPEN_CHAT_PARTNER_ID)
        checkIncomingCallIntent(intent)
        setContent {
            ChatApp(
                openChatPartnerId = openChatPartnerIdState.value,
                onConsumeOpenChatIntent = { openChatPartnerIdState.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkIncomingCallIntent(intent)
        openChatPartnerIdState.value =
            intent.getStringExtra(NotificationHelper.EXTRA_OPEN_CHAT_PARTNER_ID)
    }
}

@Composable
fun ChatApp(
    openChatPartnerId: String?,
    onConsumeOpenChatIntent: () -> Unit
) {
    val navController = rememberNavController()

    val authViewModel: AuthViewModel = viewModel()
    val context = LocalContext.current

    val callRepository = remember { CallRepository() }
    val chatRepo = remember { ChatRepository() }
    val scope = rememberCoroutineScope()

    // Auth state -> State
    val currentUserId by produceState<String?>(initialValue = FirebaseAuth.getInstance().currentUser?.uid) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { fbAuth ->
            value = fbAuth.currentUser?.uid
        }
        auth.addAuthStateListener(listener)
        awaitDispose { auth.removeAuthStateListener(listener) }
    }

    // =======================
    // 1) Permission + Channel (Android 13+)
    // =======================
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* ignore */ }

    LaunchedEffect(Unit) {
        NotificationHelper.ensureChannel(context)

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // =======================
    // 2) Sync FCM token after login
    // =======================
    var lastTokenSyncedUid by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentUserId) {
        val uid = currentUserId
        if (uid != null && uid != lastTokenSyncedUid) {
            chatRepo.syncFcmToken()
            lastTokenSyncedUid = uid
        }
        if (uid == null) lastTokenSyncedUid = null
    }

    // =======================
    // 3) Click notification -> open chat
    // =======================
    LaunchedEffect(openChatPartnerId, currentUserId) {
        val partnerId = openChatPartnerId ?: return@LaunchedEffect

        // Nếu chưa login, đưa về Login; sau khi login xong, user bấm lại notification là ok
        if (currentUserId == null) {
            navController.navigate(Screen.Login.route) { launchSingleTop = true }
            return@LaunchedEffect
        }

        navController.navigate(Screen.Chat.createRoute(partnerId)) { launchSingleTop = true }
        onConsumeOpenChatIntent()
    }

    // =======================
    // 4) Incoming call listener
    // =======================
    var lastIncomingCallId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentUserId) {
        if (currentUserId == null) {
            lastIncomingCallId = null
            return@LaunchedEffect
        }

        callRepository.listenIncomingCallsForUser(currentUserId!!).collect { incoming ->
            if (incoming == null) return@collect
            if (incoming.id == lastIncomingCallId) return@collect
            lastIncomingCallId = incoming.id

            val currentRoutePattern = navController.currentBackStackEntry?.destination?.route ?: ""
            if (!currentRoutePattern.startsWith("call")) {
                navController.navigate(
                    Screen.Call.createRoute(
                        partnerId = incoming.callerId,
                        partnerName = "",
                        isCaller = false,
                        callId = incoming.id,
                        callType = incoming.callType
                    )
                ) { launchSingleTop = true }
            }
        }
    }

    // =======================
    // NAV HOST (AUTO LOGIN LIKE MESSENGER)
    // =======================
    NavHost(navController = navController, startDestination = Screen.Splash.route) {

        // Gate: chỉ check Firebase session
        composable(Screen.Splash.route) {
            var routed by remember { mutableStateOf(false) }

            LaunchedEffect(currentUserId) {
                if (routed) return@LaunchedEffect

                routed = true
                val target = if (currentUserId != null) Screen.Home.route else Screen.Login.route
                navController.navigate(target) {
                    popUpTo(Screen.Splash.route) { inclusive = true }
                    launchSingleTop = true
                }
            }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        composable(Screen.Login.route) {
            LoginScreen(
                authViewModel = authViewModel,
                onGoRegister = { navController.navigate(Screen.Register.route) },
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.Register.route) {
            RegisterScreen(
                authViewModel = authViewModel,
                onBack = { navController.popBackStack() },
                onRegisterSuccess = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable(Screen.Home.route) {
            val chatVm: ChatViewModel = viewModel()
            LaunchedEffect(Unit) { chatVm.listenFriends() }

            HomeScreen(
                friends = chatVm.friends,
                onOpenChat = { partnerId ->
                    navController.navigate(Screen.Chat.createRoute(partnerId))
                },
                onLogout = {
                    // Messenger-like: chỉ logout khi user bấm logout
                    authViewModel.logout()
                    lastIncomingCallId = null

                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("partnerId") { type = NavType.StringType })
        ) { backStackEntry ->
            val partnerId = backStackEntry.arguments?.getString("partnerId") ?: return@composable
            val chatViewModel: ChatViewModel = viewModel()

            LaunchedEffect(partnerId) {
                chatViewModel.listenPartner(partnerId)
                chatViewModel.listenMessages(partnerId)
            }

            ChatScreen(
                viewModel = chatViewModel,
                partnerId = partnerId,
                onBack = { navController.popBackStack() },
                onStartCall = { pId, name, type ->
                    scope.launch {
                        val callId = callRepository.startOutgoingCall(pId, type)
                        navController.navigate(
                            Screen.Call.createRoute(
                                partnerId = pId,
                                partnerName = name,
                                isCaller = true,
                                callId = callId,
                                callType = type
                            )
                        ) { launchSingleTop = true }
                    }
                }
            )
        }

        composable(
            route = Screen.Call.route,
            arguments = listOf(
                navArgument("partnerId") { type = NavType.StringType },
                navArgument("partnerName") { type = NavType.StringType },
                navArgument("isCaller") { type = NavType.BoolType },
                navArgument("callId") { type = NavType.StringType },
                navArgument("callType") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val pId = backStackEntry.arguments?.getString("partnerId") ?: return@composable
            val encodedName = backStackEntry.arguments?.getString("partnerName") ?: ""
            val isCaller = backStackEntry.arguments?.getBoolean("isCaller") ?: false
            val callId = backStackEntry.arguments?.getString("callId") ?: return@composable
            val callType = backStackEntry.arguments?.getString("callType") ?: "audio"
            val name = Uri.decode(encodedName)

            CallScreen(
                partnerId = pId,
                partnerName = name,
                isCaller = isCaller,
                callId = callId,
                callType = if (callType == "video") "video" else "audio",
                onBack = { navController.popBackStack() }
            )
        }

        composable(route = Screen.Settings.route) {
            SettingScreen(
                authViewModel = authViewModel, // <--- THÊM DÒNG NÀY (truyền biến authViewModel vào)
                onBack = { navController.popBackStack() },
                onLogout = {
                    // Điều hướng về login và xoá stack (tránh bấm back quay lại home)
                    navController.navigate(route = Screen.Login.route) {
                        popUpTo(route = Screen.Home.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

    }

}
private fun checkIncomingCallIntent(intent: Intent?) {
    if (intent?.getBooleanExtra("is_incoming_call", false) == true) {
        val callId = intent.getStringExtra("call_id") ?: return
        val callerName = intent.getStringExtra("caller_name") ?: ""
        val callType = intent.getStringExtra("call_type") ?: "audio"
        val callerId = intent.getStringExtra("caller_id") ?: ""

        // Điều hướng thẳng đến màn hình CallScreen
        // Lưu ý: Bạn cần có cách truy cập NavController ở đây,
        // hoặc lưu state vào biến toàn cục để UI tự recompose.

        // Cách đơn giản nhất: Lưu vào static var hoặc ViewModel dùng chung
        // Ví dụ: GlobalCallState.incomingCall = IncomingCallData(...)
        Log.d("CALL", "Nhận cuộc gọi: $callId từ $callerName")
    }
}
