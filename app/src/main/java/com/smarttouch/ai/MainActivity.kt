package com.smarttouch.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smarttouch.ai.accessibility.DebugSnapshot
import com.smarttouch.ai.accessibility.TouchAccessibilityService
import com.smarttouch.ai.data.SettingsRepository
import com.smarttouch.ai.data.TouchSettings
import com.smarttouch.ai.detection.DetectionMode
import com.smarttouch.ai.detection.Sensitivity
import com.smarttouch.ai.detection.TouchIntervalCalculator
import com.smarttouch.ai.state.ServiceState
import kotlinx.coroutines.launch

enum class Screen { HOME, TOUCH_SETTINGS, VIDEO_DETECTION, ADVANCED, DEBUG }

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_APPLY_SETUP = "com.smarttouch.ai.action.APPLY_SETUP"
        const val EXTRA_SETUP_NAME = "setup_name"
    }

    private lateinit var settingsRepository: SettingsRepository

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* re-checked on next recomposition */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(applicationContext)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleShortcutIntent(intent)

        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                val settings by settingsRepository.settingsFlow.collectAsState(initial = TouchSettings())
                var refreshTick by remember { mutableStateOf(0) }

                LaunchedEffect(settings.savedSetups) {
                    SetupShortcuts.sync(applicationContext, settings.savedSetups)
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(400)
                        refreshTick++
                    }
                }

                val debugSnapshot = remember(refreshTick) {
                    TouchAccessibilityService.instance?.debugSnapshot?.value ?: DebugSnapshot()
                }
                val serviceRunning = remember(refreshTick) { TouchAccessibilityService.instance != null }

                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            settings = settings,
                            debugSnapshot = debugSnapshot,
                            serviceRunning = serviceRunning,
                            isAccessibilityEnabled = isAccessibilityServiceEnabled(),
                            isOverlayGranted = Settings.canDrawOverlays(this@MainActivity),
                            onEnableAccessibility = { openAccessibilitySettings() },
                            onEnableOverlay = { openOverlaySettings() },
                            onStart = { TouchAccessibilityService.instance?.startEngine() },
                            onStop = { TouchAccessibilityService.instance?.stopEngine() },
                            onNavigate = { screen = it }
                        )
                        Screen.TOUCH_SETTINGS -> TouchSettingsScreen(
                            settings = settings,
                            onBack = { screen = Screen.HOME },
                            onShowOverlay = { callServiceOrWarn { it.showFloatingControl() } },
                            onHideOverlay = { callServiceOrWarn { it.hideFloatingControl() } },
                            onUpdate = { update -> lifecycleScope.launch { update(settingsRepository) } },
                            onTestTouch = { callServiceOrWarn { it.testSingleTap() } },
                            onGrowDot = { callServiceOrWarn { it.growTouchDot() } },
                            onShrinkDot = { callServiceOrWarn { it.shrinkTouchDot() } }
                        )
                        Screen.VIDEO_DETECTION -> VideoDetectionScreen(
                            settings = settings,
                            onBack = { screen = Screen.HOME },
                            onUpdate = { update -> lifecycleScope.launch { update(settingsRepository) } },
                            onShowDetectionPoint = { callServiceOrWarn { it.showDetectionPoint() } },
                            onHideDetectionPoint = { callServiceOrWarn { it.hideDetectionPoint() } }
                        )
                        Screen.ADVANCED -> AdvancedScreen(
                            settings = settings,
                            onBack = { screen = Screen.HOME },
                            onUpdate = { update -> lifecycleScope.launch { update(settingsRepository) } },
                            onOpenDebug = { screen = Screen.DEBUG },
                            onOpenBatterySettings = { openBatteryOptimizationSettings() },
                            onShowLiveOverlay = { TouchAccessibilityService.instance?.showLiveMotionOverlay() },
                            onHideLiveOverlay = { TouchAccessibilityService.instance?.hideLiveMotionOverlay() }
                        )
                        Screen.DEBUG -> DebugScreen(
                            snapshot = debugSnapshot,
                            onBack = { screen = Screen.ADVANCED }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ACTION_APPLY_SETUP) {
            val name = intent.getStringExtra(EXTRA_SETUP_NAME) ?: return
            lifecycleScope.launch {
                if (settingsRepository.applySetup(name)) {
                    TouchAccessibilityService.instance?.startEngine()
                }
            }
            return
        }
        val mapped = when (action) {
            "com.smarttouch.ai.action.START" -> TouchAccessibilityService.ACTION_START
            "com.smarttouch.ai.action.STOP" -> TouchAccessibilityService.ACTION_STOP
            "com.smarttouch.ai.action.PAUSE" -> TouchAccessibilityService.ACTION_PAUSE
            "com.smarttouch.ai.action.RESUME" -> TouchAccessibilityService.ACTION_RESUME
            else -> return
        }
        TouchAccessibilityService.instance?.handleAction(mapped)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${TouchAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponentName, ignoreCase = true)) return true
        }
        return false
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun openBatteryOptimizationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Confirms the accessibility service is actually connected before forwarding an
     * action to it - if it isn't, tells the user clearly instead of silently doing
     * nothing (which is what a plain `instance?.foo()` would do). */
    private fun callServiceOrWarn(action: (TouchAccessibilityService) -> Unit) {
        val service = TouchAccessibilityService.instance
        if (service == null) {
            Toast.makeText(
                this,
                getString(R.string.toast_service_not_connected),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        action(service)
    }
}

// ---------------- HOME ----------------

@Composable
private fun HomeScreen(
    settings: TouchSettings,
    debugSnapshot: DebugSnapshot,
    serviceRunning: Boolean,
    isAccessibilityEnabled: Boolean,
    isOverlayGranted: Boolean,
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNavigate: (Screen) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(stringResource(R.string.app_name), color = Color.White, fontSize = 24.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.home_tagline),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp
        )

        Spacer(Modifier.height(20.dp))

        if (!isAccessibilityEnabled) {
            PermissionCard(
                title = stringResource(R.string.perm_accessibility_title),
                description = stringResource(R.string.perm_accessibility_desc),
                actionLabel = stringResource(R.string.perm_enable),
                onClick = onEnableAccessibility
            )
            Spacer(Modifier.height(10.dp))
        } else if (!isOverlayGranted) {
            PermissionCard(
                title = stringResource(R.string.perm_overlay_title),
                description = stringResource(R.string.perm_overlay_desc),
                actionLabel = stringResource(R.string.perm_enable),
                onClick = onEnableOverlay
            )
            Spacer(Modifier.height(10.dp))
        }

        StatusCard(
            state = debugSnapshot.state,
            videoActive = debugSnapshot.videoActive,
            serviceRunning = serviceRunning
        )

        Spacer(Modifier.height(16.dp))

        InfoRow(stringResource(R.string.label_touch_position), if (settings.hasTouchPosition) "${settings.touchX.toInt()}, ${settings.touchY.toInt()}" else stringResource(R.string.value_not_set))
        InfoRow(stringResource(R.string.label_interval), stringResource(R.string.value_ms, TouchIntervalCalculator.resolve(settings.intervalMs, settings.customIntervalMs)))
        InfoRow(stringResource(R.string.label_sensitivity), sensitivityLabel(settings.sensitivity))
        InfoRow(stringResource(R.string.label_clean_screen_mode), if (settings.cleanScreenMode) stringResource(R.string.on) else stringResource(R.string.off))

        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton(
                label = stringResource(R.string.btn_start),
                color = Color(0xFF2ECC71),
                enabled = isAccessibilityEnabled && settings.hasTouchPosition,
                onClick = onStart,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                label = stringResource(R.string.btn_stop),
                color = Color(0xFFE74C3C),
                enabled = true,
                onClick = onStop,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(28.dp))

        NavRow(stringResource(R.string.nav_touch_settings)) { onNavigate(Screen.TOUCH_SETTINGS) }
        NavRow(stringResource(R.string.nav_video_detection)) { onNavigate(Screen.VIDEO_DETECTION) }
        NavRow(stringResource(R.string.nav_advanced)) { onNavigate(Screen.ADVANCED) }
    }
}

@Composable
private fun StatusCard(state: ServiceState, videoActive: Boolean, serviceRunning: Boolean) {
    val color = when {
        !serviceRunning -> Color(0xFF555555)
        state == ServiceState.TOUCHING -> Color(0xFF2ECC71)
        state == ServiceState.VIDEO_ACTIVE -> Color(0xFF3498DB)
        state == ServiceState.PAUSED -> Color(0xFFF1C40F)
        state == ServiceState.ERROR -> Color(0xFFE74C3C)
        else -> Color(0xFF9B6BFF)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(16.dp)
    ) {
        Text(if (serviceRunning) state.name.replace('_', ' ') else stringResource(R.string.status_not_running), color = color, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            if (videoActive) stringResource(R.string.status_video_active) else stringResource(R.string.status_video_inactive),
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp
        )
    }
}

@Composable
private fun PermissionCard(title: String, description: String, actionLabel: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF3A2A14))
            .padding(16.dp)
    ) {
        Text(title, color = Color(0xFFFFC56B), fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        Text(description, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFFFC56B))
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(actionLabel, color = Color.Black, fontSize = 13.sp)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun ActionButton(label: String, color: Color, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) color else color.copy(alpha = 0.3f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .clickable { onClick() }
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
            Text("\u203A", color = Color.White.copy(alpha = 0.4f), fontSize = 16.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------------- TOUCH SETTINGS ----------------

@Composable
private fun TouchSettingsScreen(
    settings: TouchSettings,
    onBack: () -> Unit,
    onShowOverlay: () -> Unit,
    onHideOverlay: () -> Unit,
    onUpdate: (suspend (SettingsRepository) -> Unit) -> Unit,
    onTestTouch: () -> Unit,
    onGrowDot: () -> Unit,
    onShrinkDot: () -> Unit
) {
    ScreenScaffold(title = stringResource(R.string.nav_touch_settings), onBack = onBack) {
        Text(stringResource(R.string.ts_drag_hint), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton(stringResource(R.string.ts_show_point), onShowOverlay)
            SmallButton(stringResource(R.string.ts_hide_point), onHideOverlay)
            SmallButton(stringResource(R.string.ts_test_tap), onTestTouch)
        }

        SectionLabel(stringResource(R.string.ts_dot_size))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallButton(stringResource(R.string.ts_smaller), onShrinkDot)
            Text(stringResource(R.string.ts_dp, settings.dotSizeDp.toInt()), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            SmallButton(stringResource(R.string.ts_bigger), onGrowDot)
        }
        Text(
            stringResource(R.string.ts_dot_size_tip),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp
        )

        SectionLabel(stringResource(R.string.ts_nudge_position))
        Text(
            stringResource(R.string.ts_nudge_desc),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        val nudgePx = with(LocalDensity.current) { 20.dp.toPx() }
        val metrics = LocalContext.current.resources.displayMetrics
        // touchX/touchY use -1f as a "not set yet" sentinel. Nudging a single
        // axis off -1 (e.g. only Y for ▲/▼) left the other axis stuck at -1
        // forever, so hasTouchPosition never became true and Start refused to
        // run. Start from the screen center instead whenever it's not set yet.
        fun baseX() = if (settings.hasTouchPosition) settings.touchX else metrics.widthPixels / 2f
        fun baseY() = if (settings.hasTouchPosition) settings.touchY else metrics.heightPixels / 2f
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SmallButton("▲", onClick = {
                onUpdate { it.updateTouchPosition(baseX(), baseY() - nudgePx) }
            })
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                SmallButton("◄", onClick = {
                    onUpdate { it.updateTouchPosition(baseX() - nudgePx, baseY()) }
                })
                SmallButton("►", onClick = {
                    onUpdate { it.updateTouchPosition(baseX() + nudgePx, baseY()) }
                })
            }
            Spacer(Modifier.height(6.dp))
            SmallButton("▼", onClick = {
                onUpdate { it.updateTouchPosition(baseX(), baseY() + nudgePx) }
            })
        }

        SectionLabel(stringResource(R.string.label_interval))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TouchIntervalCalculator.presetsMs.forEach { preset ->
                ChoiceChip(
                    label = stringResource(R.string.ts_ms, preset.toInt()),
                    selected = settings.intervalMs == preset,
                    onClick = { onUpdate { it.updateInterval(preset) } }
                )
            }
            ChoiceChip(
                label = stringResource(R.string.ts_custom),
                selected = settings.intervalMs == TouchIntervalCalculator.CUSTOM_SENTINEL,
                onClick = { onUpdate { it.updateInterval(TouchIntervalCalculator.CUSTOM_SENTINEL) } }
            )
        }
        if (settings.intervalMs == TouchIntervalCalculator.CUSTOM_SENTINEL) {
            LabeledSlider(
                label = stringResource(R.string.ts_custom_interval, settings.customIntervalMs.toInt()),
                value = settings.customIntervalMs.toFloat(),
                range = 100f..10000f,
                onChange = { v -> onUpdate { it.updateCustomInterval(v.toLong()) } }
            )
        }

        SectionLabel(stringResource(R.string.ts_touch_duration))
        LabeledSlider(
            label = stringResource(R.string.ts_ms, settings.touchDurationMs.toInt()),
            value = settings.touchDurationMs.toFloat(),
            range = 10f..500f,
            onChange = { v -> onUpdate { it.updateTouchDuration(v.toLong()) } }
        )

        SectionLabel(stringResource(R.string.ts_overlay_dot))
        LabeledSlider(
            label = stringResource(R.string.ts_opacity, (settings.overlayOpacity * 100).toInt()),
            value = settings.overlayOpacity,
            range = 0.15f..1f,
            onChange = { v -> onUpdate { it.updateOverlayOpacity(v) } }
        )
        ToggleRow(stringResource(R.string.ts_lock_position), settings.overlayLocked) { v -> onUpdate { it.updateOverlayLocked(v) } }
        Text(
            stringResource(R.string.ts_lock_desc),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp
        )

        SectionLabel(stringResource(R.string.saved_setups_title))
        Text(
            stringResource(R.string.saved_setups_desc),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        var setupName by remember { mutableStateOf("") }
        OutlinedTextField(
            value = setupName,
            onValueChange = { setupName = it },
            placeholder = { Text(stringResource(R.string.saved_setups_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        SmallButton(
            label = stringResource(R.string.saved_setups_save),
            onClick = {
                if (setupName.isNotBlank() && settings.hasTouchPosition) {
                    onUpdate { it.saveSetup(setupName, settings.touchX, settings.touchY) }
                    setupName = ""
                }
            }
        )

        Spacer(Modifier.height(12.dp))
        if (settings.savedSetups.isEmpty()) {
            Text(
                stringResource(R.string.saved_setups_empty),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp
            )
        } else {
            settings.savedSetups.forEach { setup ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(setup.name, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallButton(stringResource(R.string.saved_setups_apply), onClick = {
                            onUpdate { it.applySetup(setup.name) }
                        })
                        SmallButton(stringResource(R.string.saved_setups_delete), onClick = {
                            onUpdate { it.deleteSetup(setup.name) }
                        }, danger = true)
                    }
                }
            }
        }
    }
}

// ---------------- VIDEO DETECTION ----------------

@Composable
private fun VideoDetectionScreen(
    settings: TouchSettings,
    onBack: () -> Unit,
    onUpdate: (suspend (SettingsRepository) -> Unit) -> Unit,
    onShowDetectionPoint: () -> Unit,
    onHideDetectionPoint: () -> Unit
) {
    ScreenScaffold(title = stringResource(R.string.nav_video_detection), onBack = onBack) {
        ToggleRow(stringResource(R.string.vd_enable_smart), settings.videoDetectionEnabled) { v ->
            onUpdate { it.updateVideoDetectionEnabled(v) }
        }
        Text(
            stringResource(R.string.vd_off_desc),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )

        SectionLabel(stringResource(R.string.vd_detection_method))
        Text(
            stringResource(R.string.vd_detection_method_desc),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DetectionMode.entries.forEach { mode ->
                ChoiceChip(
                    label = detectionModeLabel(mode),
                    selected = settings.detectionMode == mode,
                    onClick = { onUpdate { it.updateDetectionMode(mode) } }
                )
            }
        }

        SectionLabel(stringResource(R.string.vd_detection_point))
        Text(
            stringResource(R.string.vd_detection_point_desc),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton(stringResource(R.string.vd_show_detection_point), onShowDetectionPoint)
            SmallButton(stringResource(R.string.vd_hide), onHideDetectionPoint)
        }
        LabeledSlider(
            label = stringResource(R.string.vd_detection_area_size, settings.detectionRegionSizeDp.toInt()),
            value = settings.detectionRegionSizeDp,
            range = 60f..600f,
            onChange = { v -> onUpdate { it.updateDetectionRegionSize(v) } }
        )

        SectionLabel(stringResource(R.string.label_sensitivity))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sensitivity.entries.forEach { s ->
                ChoiceChip(
                    label = sensitivityLabel(s),
                    selected = settings.sensitivity == s,
                    onClick = { onUpdate { it.updateSensitivity(s) } }
                )
            }
        }
        if (settings.sensitivity == Sensitivity.CUSTOM) {
            LabeledSlider(
                label = stringResource(R.string.vd_custom_threshold, "%.3f".format(settings.customThreshold)),
                value = settings.customThreshold,
                range = 0.005f..0.15f,
                onChange = { v -> onUpdate { it.updateCustomThreshold(v) } }
            )
        }

        SectionLabel(stringResource(R.string.vd_detection_interval))
        LabeledSlider(
            label = stringResource(R.string.vd_detection_interval_desc, settings.detectionIntervalMs.toInt()),
            value = settings.detectionIntervalMs.toFloat(),
            range = 200f..2000f,
            onChange = { v -> onUpdate { it.updateDetectionInterval(v.toLong()) } }
        )

        SectionLabel(stringResource(R.string.vd_confirmation_time))
        LabeledSlider(
            label = stringResource(R.string.vd_confirmation_time_desc, settings.confirmationTimeMs.toInt()),
            value = settings.confirmationTimeMs.toFloat(),
            range = 200f..3000f,
            onChange = { v -> onUpdate { it.updateConfirmationTime(v.toLong()) } }
        )

        SectionLabel(stringResource(R.string.vd_no_motion_timeout))
        LabeledSlider(
            label = stringResource(R.string.vd_no_motion_timeout_desc, settings.noMotionTimeoutMs.toInt()),
            value = settings.noMotionTimeoutMs.toFloat(),
            range = 300f..5000f,
            onChange = { v -> onUpdate { it.updateNoMotionTimeout(v.toLong()) } }
        )

        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.vd_note),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp
        )
    }
}

// ---------------- ADVANCED ----------------

@Composable
private fun AdvancedScreen(
    settings: TouchSettings,
    onBack: () -> Unit,
    onUpdate: (suspend (SettingsRepository) -> Unit) -> Unit,
    onOpenDebug: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onShowLiveOverlay: () -> Unit,
    onHideLiveOverlay: () -> Unit
) {
    ScreenScaffold(title = stringResource(R.string.nav_advanced), onBack = onBack) {
        val context = LocalContext.current
        var currentLang by remember { mutableStateOf(LocaleHelper.getLanguage(context)) }
        SectionLabel(stringResource(R.string.language_title))
        Text(
            stringResource(R.string.language_desc),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(stringResource(R.string.language_system), selected = currentLang == null, onClick = {
                LocaleHelper.setLanguage(context, null)
                currentLang = null
                (context as? android.app.Activity)?.recreate()
            })
            ChoiceChip("العربية", selected = currentLang == "ar", onClick = {
                LocaleHelper.setLanguage(context, "ar")
                currentLang = "ar"
                (context as? android.app.Activity)?.recreate()
            })
            ChoiceChip("English", selected = currentLang == "en", onClick = {
                LocaleHelper.setLanguage(context, "en")
                currentLang = "en"
                (context as? android.app.Activity)?.recreate()
            })
        }

        ToggleRow(stringResource(R.string.adv_start_on_boot), settings.startOnBoot) { v -> onUpdate { it.updateStartOnBoot(v) } }
        Text(
            stringResource(R.string.adv_start_on_boot_desc),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp
        )

        SectionLabel(stringResource(R.string.adv_live_overlay))
        Text(
            stringResource(R.string.adv_live_overlay_desc),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallButton(stringResource(R.string.adv_show_overlay), onShowLiveOverlay)
            SmallButton(stringResource(R.string.adv_hide_overlay), onHideLiveOverlay)
        }

        SectionLabel(stringResource(R.string.adv_battery))
        SmallButton(stringResource(R.string.adv_battery_settings), onOpenBatterySettings)

        SectionLabel(stringResource(R.string.adv_hide_notification))
        Text(
            stringResource(R.string.adv_hide_notification_desc),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
        ToggleRow(stringResource(R.string.adv_hide_notification), !settings.showNotification) { hide ->
            onUpdate { it.updateShowNotification(!hide) }
        }

        SectionLabel(stringResource(R.string.adv_debug))
        ToggleRow(stringResource(R.string.adv_debug_mode), settings.debugMode) { v -> onUpdate { it.updateDebugMode(v) } }
        if (settings.debugMode) {
            SmallButton(stringResource(R.string.adv_open_debug), onOpenDebug)
        }

        SectionLabel(stringResource(R.string.adv_reset))
        SmallButton(label = stringResource(R.string.adv_reset_all), onClick = { onUpdate { it.resetAll() } }, danger = true)
    }
}

// ---------------- DEBUG ----------------

@Composable
private fun DebugScreen(snapshot: DebugSnapshot, onBack: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.screen_title_debug), onBack = onBack) {
        InfoRow(stringResource(R.string.dbg_state), snapshot.state.name)
        InfoRow(stringResource(R.string.dbg_video_active), snapshot.videoActive.toString())
        InfoRow(stringResource(R.string.dbg_motion_score), "%.4f".format(snapshot.motionScore))
        InfoRow(stringResource(R.string.dbg_threshold), "%.4f".format(snapshot.threshold))
        InfoRow(stringResource(R.string.label_touch_position), "${snapshot.touchX.toInt()}, ${snapshot.touchY.toInt()}")
        InfoRow(stringResource(R.string.dbg_sampling_interval), stringResource(R.string.ts_ms, snapshot.samplingIntervalMs.toInt()))
        InfoRow(stringResource(R.string.dbg_last_event), snapshot.lastEvent)
    }
}

@Composable
private fun detectionModeLabel(mode: DetectionMode): String = when (mode) {
    DetectionMode.AUDIO -> stringResource(R.string.detection_mode_audio)
    DetectionMode.VISUAL -> stringResource(R.string.detection_mode_visual)
    DetectionMode.EITHER -> stringResource(R.string.detection_mode_either)
}

@Composable
private fun sensitivityLabel(s: Sensitivity): String = when (s) {
    Sensitivity.LOW -> stringResource(R.string.sensitivity_low)
    Sensitivity.MEDIUM -> stringResource(R.string.sensitivity_medium)
    Sensitivity.HIGH -> stringResource(R.string.sensitivity_high)
    Sensitivity.CUSTOM -> stringResource(R.string.sensitivity_custom)
}

// ---------------- Shared small components ----------------

@Composable
private fun ScreenScaffold(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "\u2190",
                color = Color.White,
                fontSize = 20.sp,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp)
            )
            Text(title, color = Color.White, fontSize = 20.sp)
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Color.White.copy(alpha = 0.35f),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!value) }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (value) Color(0xFF9B6BFF) else Color.White.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(if (value) stringResource(R.string.on) else stringResource(R.string.off), color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0xFF9B6BFF) else Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit, danger: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (danger) Color(0xFF5A1E1E) else Color.White.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (danger) Color(0xFFFF8A8A) else Color.White, fontSize = 12.sp)
    }
}
