package com.voxbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.voxbridge.ui.components.ProblemPanel
import com.voxbridge.ui.components.ProgressPanel
import com.voxbridge.ui.theme.Trust
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxbridge.BuildConfig
import com.voxbridge.runtime.AndroidModelPaths
import com.voxbridge.runtime.ModelProfile
import com.voxbridge.ui.theme.VoxBridgeTheme

/**
 * Hosts the push-to-talk screen. Startup (install → readiness gate → native-runner build) moved
 * INTO [PushToTalkViewModel] (Round-3 review HIGH): the VM is created through
 * [PushToTalkViewModel.factory] inside `viewModels`, so it lives in the ViewModelStore — the
 * framework retains it (and the ~GB native runner set it built) across configuration changes
 * and calls onCleared() when the task truly ends. A directly constructed ViewModel would be in
 * no store: onCleared() would never run and the native sessions would leak on every rotation.
 *
 * Model delivery (both paths handled by the VM via ModelInstaller):
 *  - **Bundled (turnkey, Colab-built APK)**: `assets/models/…` carries the full set; on first
 *    launch it unpacks to the external files dir on a WORKER thread — hundreds of MB, never the
 *    main thread; the UI shows a waiting state meanwhile.
 *  - **adb push (alternative)**: the set is already complete under `getExternalFilesDir(null)/models`
 *    and the install step is a no-op.
 *
 * Failure contract: ANY startup exception renders [Setup.Problem] naming the problem/missing
 * files instead of crashing (a launch-time crash would look like "the app is broken" when it is
 * only the model set).
 *
 * STATUS: wiring is real and matches every port contract; all runners are fully implemented
 * (WhisperAsr, static QNN MT (EnViT5/VinAI), SileroVad v4/v5, PiperTts). Device-side
 * verification (QNN EP on a Snapdragon, sherpa JNI, SQLCipher open) happens via the Colab build +
 * first device run — see colab/README.md.
 */
class MainActivity : ComponentActivity() {

    private var micGranted by mutableStateOf(false)
    private var micAskedOnce = false
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
    }

    /** Store-owned VM (see class KDoc). Constructed on main — ViewModelStore is a plain
     *  HashMap and must only be touched from main; the heavy build it kicks off runs off-main. */
    private val vm: PushToTalkViewModel by viewModels { PushToTalkViewModel.factory(application) }

    // One Haptics for the Activity lifetime — the co-primary trust output channel (doc 14 §6).
    private val haptics by lazy { Haptics(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.voxbridge.audio.DemoFeed.init(getExternalFilesDir(null))
        window.statusBarColor = android.graphics.Color.rgb(247, 249, 250)
        window.navigationBarColor = android.graphics.Color.rgb(247, 249, 250)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        // Dev model sync needs the Android-resolved external-files root before any runtime
        // permission UI can cover this Activity. This is path discovery only: no model file is
        // opened and no native runner is initialized here.
        if (!BuildConfig.USE_FAKE_PIPELINE) {
            val profile = ModelProfile.fromId(BuildConfig.MODEL_PROFILE)
            Log.i("ModelRepository", "MODEL_ROOT=${AndroidModelPaths.from(this, profile).root.absolutePath}")
        }
        micGranted = hasMicPermission()
        if (!micGranted) { micAskedOnce = true; requestMic.launch(Manifest.permission.RECORD_AUDIO) }

        setContent {
            VoxBridgeTheme {
                Surface(color = Trust.Background) {
                    val s by vm.state.collectAsStateWithLifecycle()
                    when (val setup = s.setup) {
                        is Setup.Problem -> StartupScreen {
                            ProblemPanel(
                                "Chưa mở được bộ dịch",
                                "Máy chưa nạp được mô hình dịch. Bấm thử lại; nếu vẫn lỗi, mở lại ứng dụng.\n\nChi tiết: ${setup.message}",
                                "Thử lại",
                                vm::retrySetup,
                            )
                        }
                        Setup.Ready -> MainScreen(vm, haptics = haptics, micGranted = micGranted, onRequestMic = ::requestMicAgain)
                        Setup.Preparing -> StartupScreen {
                            ProgressPanel("Đang chuẩn bị bộ dịch trên máy", s.setupMessage)
                            Text(
                                "Lần đầu mở có thể mất khoảng một phút.",
                                fontSize = 17.sp, lineHeight = 24.sp, color = Trust.SecondaryText,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The worker may have granted the mic in system Settings; pick that up without a restart.
        if (!micGranted) micGranted = hasMicPermission()
    }

    private fun hasMicPermission(): Boolean = BuildConfig.USE_FAKE_PIPELINE ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Ask again while Android still allows it; after "don't ask again" open this app's settings. */
    private fun requestMicAgain() {
        if (micAskedOnce && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } else {
            micAskedOnce = true
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun StartupScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Trust.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("VoxBridge", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Trust.Ink)
        content()
    }
}
