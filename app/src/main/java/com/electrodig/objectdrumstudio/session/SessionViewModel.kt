package com.electrodig.objectdrumstudio.session

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.electrodig.objectdrumstudio.detection.color.DetectionConfig
import com.electrodig.objectdrumstudio.detection.color.DrumZone
import com.electrodig.objectdrumstudio.detection.color.HsvPreset
import com.electrodig.objectdrumstudio.persistence.PerformanceLevel
import com.electrodig.objectdrumstudio.persistence.Settings
import com.electrodig.objectdrumstudio.persistence.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds the live session state shared across the single-Activity Compose tree
 * (PRD §5.3). Owns mode, HUD stats, detection config, zones, and persists user
 * settings via [SettingsRepository] (PRD F8).
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    private val powerManager = app.getSystemService(PowerManager::class.java)

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    /** Persisted settings (loaded from DataStore, kept in sync on change). */
    val settings: StateFlow<Settings> = repo.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT
    )

    // Auto-switch to LOW tier when battery saver is active (PRD R6.2).
    init {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                if (intent == null) return
                val isPowerSave = powerManager?.isPowerSaveMode == true
                if (isPowerSave && settings.value.performanceLevel != PerformanceLevel.LOW) {
                    Log.i("SessionVM", "Power save mode active — switching to LOW performance tier")
                    setPerformanceLevel(PerformanceLevel.LOW)
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    // BatteryManager.EXTRA_THERMAL_STATUS added in API 29.
                    val thermalStatus = intent.getIntExtra(
                        "android.os.extra.THERMAL_STATUS",
                        0 // THERMAL_STATUS_NONE
                    )
                    if (thermalStatus >= 1 /* THERMAL_STATUS_MODERATE */
                        && settings.value.performanceLevel != PerformanceLevel.LOW) {
                        Log.w("SessionVM", "Thermal throttling (status=$thermalStatus) — switching to LOW tier")
                        setPerformanceLevel(PerformanceLevel.LOW)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        app.registerReceiver(receiver, filter)
    }

    /** Latest drum zones detected by the segmentation pipeline (PRD F4.5). */
    private val _zones = MutableStateFlow<List<DrumZone>>(emptyList())
    val zones: StateFlow<List<DrumZone>> = _zones.asStateFlow()

    /** Zone ids that flashed on a recent trigger, for the overlay highlight (F2). */
    private val _flashedZoneIds = MutableStateFlow<Set<Int>>(emptySet())
    val flashedZoneIds: StateFlow<Set<Int>> = _flashedZoneIds.asStateFlow()

    fun setMode(mode: StudioMode) { _uiState.update { it.copy(mode = mode) } }

    fun setCameraReady(ready: Boolean) { _uiState.update { it.copy(isCameraReady = ready) } }

    // ---- Persisted settings mutations (PRD F8) ----

    fun updatePresets(presets: List<HsvPreset>) {
        repoUpdate { it.copy(detectionConfig = it.detectionConfig.copy(presets = presets)) }
    }

    fun updateDetectionConfig(transform: (DetectionConfig) -> DetectionConfig) {
        repoUpdate { it.copy(detectionConfig = transform(it.detectionConfig)) }
    }

    fun setPerformanceLevel(level: PerformanceLevel) {
        repoUpdate { it.copy(performanceLevel = level) }
    }

    fun setHapticEnabled(enabled: Boolean) { repoUpdate { it.copy(hapticEnabled = enabled) } }

    fun setMasterVolume(volume: Float) {
        repoUpdate { it.copy(masterVolume = volume.coerceIn(0f, 1f)) }
    }

    fun setLastBpm(bpm: Int) { repoUpdate { it.copy(lastBpm = bpm.coerceIn(40, 220)) } }

    fun setActiveKit(index: Int) { repoUpdate { it.copy(activeKitIndex = index) } }

    fun resetSettings() {
        viewModelScope.launch { repo.reset() }
    }

    private fun repoUpdate(transform: (Settings) -> Settings) {
        viewModelScope.launch { repo.update(transform) }
    }

    // ---- Transient detection state ----

    fun setZones(zones: List<DrumZone>) {
        _zones.value = zones
        _uiState.update {
            it.copy(
                objectCount = zones.size,
                signalStrength = if (zones.isEmpty()) it.signalStrength.coerceAtMost(0.3f) else 0.7f
            )
        }
    }

    fun updateDetection(objectCount: Int, handCount: Int, signal: Float) {
        _uiState.update {
            it.copy(
                objectCount = objectCount,
                handCount = handCount,
                signalStrength = signal.coerceIn(0f, 1f)
            )
        }
    }

    fun setAnalysisFps(fps: Float) {
        _uiState.update { it.copy(analysisFps = fps) }
    }

    fun flashZones(zoneIds: Collection<Int>) {
        if (zoneIds.isEmpty()) return
        _flashedZoneIds.value = _flashedZoneIds.value + zoneIds
    }

    fun clearFlash(zoneIds: Collection<Int>) {
        if (zoneIds.isEmpty()) return
        _flashedZoneIds.value = _flashedZoneIds.value - zoneIds
    }
}
