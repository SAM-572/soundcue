package com.soundcue.babycare

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.soundcue.babycare.presentation.MainViewModel
import com.soundcue.babycare.ui.screens.BabyDashboardScreen
import com.soundcue.babycare.ui.screens.GestureSpeakScreen
import com.soundcue.babycare.ui.screens.HomeScreen
import com.soundcue.babycare.ui.screens.ProfileScreen
import com.soundcue.babycare.ui.screens.ReportScreen
import com.soundcue.babycare.ui.theme.SoundCueTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startListening()
    }

    fun requestMicAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startListening()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()

        setContent {
            SoundCueTheme {
                val nav = rememberNavController()
                val state by viewModel.state.collectAsState()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            aiState = state.ai,
                            lang = state.outputLang,
                            onToggleLang = viewModel::toggleLanguage,
                            onBabyCareClick = { nav.navigate("baby") },
                            onGestureSpeakClick = { nav.navigate("gesture") },
                            onProfileClick = { nav.navigate("profile") },
                            onReportClick = { nav.navigate("report") }
                        )
                    }
                    composable("baby") {
                        BabyDashboardScreen(
                            viewModel = viewModel,
                            onBack = { nav.popBackStack() },
                            onRequestMic = { requestMicAndStart() }
                        )
                    }
                    composable("gesture") {
                        GestureSpeakScreen(
                            viewModel = viewModel,
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable("profile") {
                        ProfileScreen(
                            profileStore = viewModel.profileStore,
                            lang = state.outputLang,
                            onBack = { nav.popBackStack() }
                        )
                    }
                    composable("report") {
                        ReportScreen(
                            eventRepo = viewModel.eventRepo,
                            profileStore = viewModel.profileStore,
                            lang = state.outputLang,
                            onBack = { nav.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
