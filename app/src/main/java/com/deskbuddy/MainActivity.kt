package com.deskbuddy

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deskbuddy.ui.DeskuApp
import com.deskbuddy.ui.Night
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * The kiosk: one full-screen activity that keeps the screen on. Pick Desk Buddy as the home app
 * (it declares HOME) and it comes back on boot and on the home button; "Pin to screen" in
 * settings uses Android's screen pinning to keep it in front.
 */
class MainActivity : ComponentActivity() {

    private val vm: CompanionViewModel by viewModels()

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reportPermissions() }

    private var debugReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Night.Bg, surface = Night.Raised, primary = Night.Accent)) {
                DeskuApp(vm, onPin = ::pin)
            }
        }

        // Dim the backlight when nothing has happened for a while; any touch or voice wakes it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.ui.map { it.ambient }.distinctUntilChanged().collect { ambient ->
                    window.attributes = window.attributes.apply {
                        screenBrightness = if (ambient) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
        }

        val missing = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) reportPermissions() else askPermissions.launch(missing.toTypedArray())

        if (BuildConfig.DEBUG) registerDebugHooks()
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        vm.touch()
    }

    override fun onDestroy() {
        debugReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun reportPermissions() {
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        vm.onPermissions(mic = granted(Manifest.permission.RECORD_AUDIO), camera = granted(Manifest.permission.CAMERA))
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Android screen pinning: shows its own confirmation, and is undone with back + overview. */
    private fun pin() {
        runCatching { startLockTask() }.onFailure { Log.w("DeskBuddy", "screen pinning unavailable", it) }
    }

    /**
     * Debug builds only: drive the app from adb so the demo can be exercised on an emulator
     * without a microphone or camera. See README "Testing without a mic".
     */
    private fun registerDebugHooks() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_SAY -> intent.getStringExtra("text")?.let(vm::debugSay)
                    ACTION_PHOTO -> intent.getStringExtra("name")?.let { vm.debugPhoto(File(getExternalFilesDir(null), it)) }
                    ACTION_WAKE -> vm.onWakePhrase()
                    ACTION_TIMER -> vm.debugTimer(intent.getIntExtra("seconds", 10), intent.getStringExtra("task") ?: "test")
                    ACTION_NEW -> vm.newConversation()
                    ACTION_SCREEN -> vm.debugScreen(
                        intent.getStringExtra("mode") ?: "speaking",
                        intent.getStringExtra("text").orEmpty(),
                        intent.getStringExtra("card"),
                        intent.getStringExtra("asked"),
                        intent.getStringExtra("photo")?.let { File(getExternalFilesDir(null), it) },
                    )
                }
            }
        }
        val filter = IntentFilter().apply { listOf(ACTION_SAY, ACTION_PHOTO, ACTION_WAKE, ACTION_TIMER, ACTION_NEW, ACTION_SCREEN).forEach(::addAction) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        debugReceiver = receiver
    }

    companion object {
        const val ACTION_SAY = "com.deskbuddy.debug.SAY"
        const val ACTION_PHOTO = "com.deskbuddy.debug.PHOTO"
        const val ACTION_WAKE = "com.deskbuddy.debug.WAKE"
        const val ACTION_TIMER = "com.deskbuddy.debug.TIMER"
        const val ACTION_NEW = "com.deskbuddy.debug.NEW_CONVERSATION"
        const val ACTION_SCREEN = "com.deskbuddy.debug.SCREEN"
    }
}
