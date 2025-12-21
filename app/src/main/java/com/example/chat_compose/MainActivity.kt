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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

class MainActivity : ComponentActivity() {

    private val openChatPartnerIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        openChatPartnerIdState.value =
            intent?.getStringExtra(NotificationHelper.EXTRA_OPEN_CHAT_PARTNER_ID)

        setContent {
            ChatApp(
                openChatPartnerId = openChatPartnerIdState.value,
                onConsumeOpenChatIntent = { openChatPartnerIdState.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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

    // ===== Firebase session (auto login) =====
    val currentUserId by produceState<String?>(
        initialValue = FirebaseAuth.getInstance().currentUser?.uid
    ) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { fbAuth ->
            value = fbAuth.currentUser?.uid
        }
        auth.addAuthStateListener(listener)
        awaitDispose { auth.removeAuthStateListener(listener) }
    }

    // =======================
    // 1) Notification channel + permission (Android 13+)
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
                        partnerName = Uri.encode(""),
                        isCaller = false,
                        callId = incoming.id,
                        callType = incoming.callType
                    )
                ) { launchSingleTop = true }
            }
        }
    }

    // =======================
    // 5) Outgoing call: xin quyền trước, tạo call, VÀO CALLSCREEN NGAY
    // =======================
    var pendingStart by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    // (partnerId, partnerName, type)

    val avPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val req = pendingStart ?: return@rememberLauncherForActivityResult
        val type = req.third

        val needPerms = if (type == "video")
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else
            arrayOf(Manifest.permission.RECORD_AUDIO)

        val granted = needPerms.all { result[it] == true }
        if (!granted) {
            pendingStart = null
            android.widget.Toast
                .makeText(context, "Bạn chưa cấp đủ quyền để gọi ${if (type == "video") "video" else "audio"}.", android.widget.Toast.LENGTH_LONG)
                .show()
            return@rememberLauncherForActivityResult
        }

        val (pId, name, t) = req
        pendingStart = null

        scope.launch {
            val callId = runCatching { callRepository.startOutgoingCall(pId, t) }.getOrNull()
            if (callId.isNullOrBlank()) {
                android.widget.Toast.makeText(context, "Không tạo được cuộc gọi.", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }

            // ✅ VÀO CALLSCREEN NGAY (ringing)
            navController.navigate(
                Screen.Call.createRoute(
                    partnerId = pId,
                    partnerName = Uri.encode(name),
                    isCaller = true,
                    callId = callId,
                    callType = t
                )
            ) { launchSingleTop = true }
        }
    }

    fun startOutgoingNow(partnerId: String, name: String, type: String) {
        val needPerms = if (type == "video")
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else
            arrayOf(Manifest.permission.RECORD_AUDIO)

        val granted = needPerms.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

        if (!granted) {
            pendingStart = Triple(partnerId, name, type)
            avPermLauncher.launch(needPerms)
            return
        }

        scope.launch {
            val callId = runCatching { callRepository.startOutgoingCall(partnerId, type) }.getOrNull()
            if (callId.isNullOrBlank()) {
                android.widget.Toast.makeText(context, "Không tạo được cuộc gọi.", android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }

            // ✅ VÀO CALLSCREEN NGAY (ringing)
            navController.navigate(
                Screen.Call.createRoute(
                    partnerId = partnerId,
                    partnerName = Uri.encode(name),
                    isCaller = true,
                    callId = callId,
                    callType = type
                )
            ) { launchSingleTop = true }
        }
    }

    // =======================
    // NAV HOST
    // =======================
    NavHost(navController = navController, startDestination = Screen.Splash.route) {

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
                    authViewModel.logout()
                    lastIncomingCallId = null

                    navController.navigate(Screen.Login.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                        launchSingleTop = true
                    }
                },
                onOpenSettings = { navController.navigate(Screen.Settings.route) },
                onCreateGroup = { navController.navigate(Screen.CreateGroup.route) }
            )
        }

        composable(Screen.CreateGroup.route) {
            CreateGroupScreen(
                onBack = { navController.popBackStack() },
                onCreated = { groupId ->
                    navController.popBackStack()
                    navController.navigate(Screen.Chat.createRoute(groupId)) { launchSingleTop = true }
                }
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
                    // ✅ BẤM GỌI -> VÀO CALLSCREEN NGAY
                    startOutgoingNow(pId, name, type)
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
            val pId = backStackEntry.arguments?.getString("partnerId")
            val encodedName = backStackEntry.arguments?.getString("partnerName") ?: ""
            val isCaller = backStackEntry.arguments?.getBoolean("isCaller") ?: false
            val callId = backStackEntry.arguments?.getString("callId")
            val callType = backStackEntry.arguments?.getString("callType") ?: "audio"

            if (pId.isNullOrBlank() || callId.isNullOrBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Lỗi điều hướng cuộc gọi (thiếu partnerId/callId).")
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { navController.popBackStack() }) { Text("Quay lại") }
                    }
                }
                return@composable
            }

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
                authViewModel = authViewModel,
                onBack = { navController.popBackStack() },
                onLogout = {
                    navController.navigate(route = Screen.Login.route) {
                        popUpTo(route = Screen.Home.route) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
