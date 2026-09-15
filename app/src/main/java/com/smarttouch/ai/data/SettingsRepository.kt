package com.smarttouch.ai.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smarttouch.ai.detection.DetectionMode
import com.smarttouch.ai.detection.Sensitivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "smart_touch_settings")

/** A named, saved touch-point position the user can jump back to later. */
data class SavedSetup(val name: String, val x: Float, val y: Float)

data class TouchSettings(
    val touchX: Float = -1f,
    val touchY: Float = -1f,
    val dotSizeDp: Float = 3f,
    val detectionX: Float = -1f,
    val detectionY: Float = -1f,
    val detectionRegionSizeDp: Float = 260f,
    val intervalMs: Long = 2000L,
    val customIntervalMs: Long = 2000L,
    val touchDurationMs: Long = 50L,
    val overlayOpacity: Float = 1f,
    val overlayLocked: Boolean = false,
    val cleanScreenMode: Boolean = false,
    val videoDetectionEnabled: Boolean = true,
    val detectionMode: DetectionMode = DetectionMode.EITHER,
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    val customThreshold: Float = 0.02f,
    val detectionIntervalMs: Long = 500L,
    val confirmationTimeMs: Long = 800L,
    val noMotionTimeoutMs: Long = 1500L,
    val startOnBoot: Boolean = false,
    val debugMode: Boolean = false,
    val preciseYoutubeNetflixDetection: Boolean = false,
    val savedSetups: List<SavedSetup> = emptyList()
) {
    val hasTouchPosition: Boolean get() = touchX >= 0f && touchY >= 0f
    val hasDetectionPosition: Boolean get() = detectionX >= 0f && detectionY >= 0f
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val TOUCH_X = floatPreferencesKey("touch_x")
        val TOUCH_Y = floatPreferencesKey("touch_y")
        val DOT_SIZE_DP = floatPreferencesKey("dot_size_dp")
        val DETECTION_X = floatPreferencesKey("detection_x")
        val DETECTION_Y = floatPreferencesKey("detection_y")
        val DETECTION_REGION_SIZE_DP = floatPreferencesKey("detection_region_size_dp")
        val INTERVAL_MS = longPreferencesKey("interval_ms")
        val CUSTOM_INTERVAL_MS = longPreferencesKey("custom_interval_ms")
        val TOUCH_DURATION_MS = longPreferencesKey("touch_duration_ms")
        val OVERLAY_OPACITY = floatPreferencesKey("overlay_opacity")
        val OVERLAY_LOCKED = booleanPreferencesKey("overlay_locked")
        val CLEAN_SCREEN = booleanPreferencesKey("clean_screen")
        val VIDEO_DETECTION_ENABLED = booleanPreferencesKey("video_detection_enabled")
        val DETECTION_MODE = stringPreferencesKey("detection_mode")
        val SENSITIVITY = stringPreferencesKey("sensitivity")
        val CUSTOM_THRESHOLD = floatPreferencesKey("custom_threshold")
        val DETECTION_INTERVAL_MS = longPreferencesKey("detection_interval_ms")
        val CONFIRMATION_TIME_MS = longPreferencesKey("confirmation_time_ms")
        val NO_MOTION_TIMEOUT_MS = longPreferencesKey("no_motion_timeout_ms")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val DEBUG_MODE = booleanPreferencesKey("debug_mode")
        val PRECISE_YT_NETFLIX_DETECTION = booleanPreferencesKey("precise_yt_netflix_detection")
        val SAVED_SETUPS = stringPreferencesKey("saved_setups")
    }

    val settingsFlow: Flow<TouchSettings> = context.dataStore.data.map { prefs ->
        TouchSettings(
            touchX = prefs[Keys.TOUCH_X] ?: -1f,
            touchY = prefs[Keys.TOUCH_Y] ?: -1f,
            dotSizeDp = prefs[Keys.DOT_SIZE_DP] ?: 3f,
            detectionX = prefs[Keys.DETECTION_X] ?: -1f,
            detectionY = prefs[Keys.DETECTION_Y] ?: -1f,
            detectionRegionSizeDp = prefs[Keys.DETECTION_REGION_SIZE_DP] ?: 260f,
            intervalMs = prefs[Keys.INTERVAL_MS] ?: 2000L,
            customIntervalMs = prefs[Keys.CUSTOM_INTERVAL_MS] ?: 2000L,
            touchDurationMs = prefs[Keys.TOUCH_DURATION_MS] ?: 50L,
            overlayOpacity = prefs[Keys.OVERLAY_OPACITY] ?: 1f,
            overlayLocked = prefs[Keys.OVERLAY_LOCKED] ?: false,
            cleanScreenMode = prefs[Keys.CLEAN_SCREEN] ?: false,
            videoDetectionEnabled = prefs[Keys.VIDEO_DETECTION_ENABLED] ?: true,
            detectionMode = runCatching {
                DetectionMode.valueOf(prefs[Keys.DETECTION_MODE] ?: DetectionMode.EITHER.name)
            }.getOrDefault(DetectionMode.EITHER),
            sensitivity = runCatching {
                Sensitivity.valueOf(prefs[Keys.SENSITIVITY] ?: Sensitivity.MEDIUM.name)
            }.getOrDefault(Sensitivity.MEDIUM),
            customThreshold = prefs[Keys.CUSTOM_THRESHOLD] ?: 0.02f,
            detectionIntervalMs = prefs[Keys.DETECTION_INTERVAL_MS] ?: 500L,
            confirmationTimeMs = prefs[Keys.CONFIRMATION_TIME_MS] ?: 800L,
            noMotionTimeoutMs = prefs[Keys.NO_MOTION_TIMEOUT_MS] ?: 1500L,
            startOnBoot = prefs[Keys.START_ON_BOOT] ?: false,
            debugMode = prefs[Keys.DEBUG_MODE] ?: false,
            preciseYoutubeNetflixDetection = prefs[Keys.PRECISE_YT_NETFLIX_DETECTION] ?: false,
            savedSetups = parseSetups(prefs[Keys.SAVED_SETUPS] ?: "")
        )
    }

    suspend fun updateTouchPosition(x: Float, y: Float) {
        context.dataStore.edit {
            it[Keys.TOUCH_X] = x
            it[Keys.TOUCH_Y] = y
        }
    }

    suspend fun updateInterval(ms: Long) = edit(Keys.INTERVAL_MS, ms)
    suspend fun updateDotSize(dp: Float) = edit(Keys.DOT_SIZE_DP, dp)
    suspend fun updateDetectionPosition(x: Float, y: Float) {
        context.dataStore.edit {
            it[Keys.DETECTION_X] = x
            it[Keys.DETECTION_Y] = y
        }
    }
    suspend fun updateDetectionRegionSize(dp: Float) = edit(Keys.DETECTION_REGION_SIZE_DP, dp)
    suspend fun updateCustomInterval(ms: Long) = edit(Keys.CUSTOM_INTERVAL_MS, ms)
    suspend fun updateTouchDuration(ms: Long) = edit(Keys.TOUCH_DURATION_MS, ms)
    suspend fun updateOverlayOpacity(value: Float) = edit(Keys.OVERLAY_OPACITY, value)
    suspend fun updateOverlayLocked(locked: Boolean) = edit(Keys.OVERLAY_LOCKED, locked)
    suspend fun updateCleanScreenMode(enabled: Boolean) = edit(Keys.CLEAN_SCREEN, enabled)
    suspend fun updateVideoDetectionEnabled(enabled: Boolean) = edit(Keys.VIDEO_DETECTION_ENABLED, enabled)
    suspend fun updateDetectionMode(mode: DetectionMode) = edit(Keys.DETECTION_MODE, mode.name)
    suspend fun updateSensitivity(sensitivity: Sensitivity) = edit(Keys.SENSITIVITY, sensitivity.name)
    suspend fun updateCustomThreshold(value: Float) = edit(Keys.CUSTOM_THRESHOLD, value)
    suspend fun updateDetectionInterval(ms: Long) = edit(Keys.DETECTION_INTERVAL_MS, ms)
    suspend fun updateConfirmationTime(ms: Long) = edit(Keys.CONFIRMATION_TIME_MS, ms)
    suspend fun updateNoMotionTimeout(ms: Long) = edit(Keys.NO_MOTION_TIMEOUT_MS, ms)
    suspend fun updateStartOnBoot(enabled: Boolean) = edit(Keys.START_ON_BOOT, enabled)
    suspend fun updateDebugMode(enabled: Boolean) = edit(Keys.DEBUG_MODE, enabled)
    suspend fun updatePreciseYoutubeNetflixDetection(enabled: Boolean) = edit(Keys.PRECISE_YT_NETFLIX_DETECTION, enabled)

    /** Saves the given position under this name, replacing any existing setup
     * with the same name. Names can't contain '|' or newlines - those are the
     * field/entry separators used in storage, so they're stripped. */
    suspend fun saveSetup(name: String, x: Float, y: Float) {
        val cleanName = name.replace("|", "").replace("\n", "").trim()
        if (cleanName.isEmpty()) return
        val current = parseSetups(context.dataStore.data.first()[Keys.SAVED_SETUPS] ?: "")
        val updated = current.filterNot { it.name == cleanName } + SavedSetup(cleanName, x, y)
        context.dataStore.edit { it[Keys.SAVED_SETUPS] = serializeSetups(updated) }
    }

    suspend fun deleteSetup(name: String) {
        val current = parseSetups(context.dataStore.data.first()[Keys.SAVED_SETUPS] ?: "")
        val updated = current.filterNot { it.name == name }
        context.dataStore.edit { it[Keys.SAVED_SETUPS] = serializeSetups(updated) }
    }

    /** Applies a saved setup's position as the current touch position. Returns
     * true if a setup with this name was found and applied. */
    suspend fun applySetup(name: String): Boolean {
        val setup = parseSetups(context.dataStore.data.first()[Keys.SAVED_SETUPS] ?: "")
            .firstOrNull { it.name == name } ?: return false
        updateTouchPosition(setup.x, setup.y)
        return true
    }

    private fun serializeSetups(setups: List<SavedSetup>): String =
        setups.joinToString("\n") { "${it.name}|${it.x}|${it.y}" }

    private fun parseSetups(raw: String): List<SavedSetup> =
        raw.split("\n")
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|")
                if (parts.size != 3) return@mapNotNull null
                val x = parts[1].toFloatOrNull() ?: return@mapNotNull null
                val y = parts[2].toFloatOrNull() ?: return@mapNotNull null
                SavedSetup(parts[0], x, y)
            }

    suspend fun resetAll() {
        context.dataStore.edit { it.clear() }
    }

    private suspend fun <T> edit(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
