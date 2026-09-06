package com.electrodig.voidmusic.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.electrodig.voidmusic.detection.color.DetectionConfig
import com.electrodig.voidmusic.detection.color.DrumZone
import com.electrodig.voidmusic.detection.color.HsvPreset
import com.electrodig.voidmusic.persistence.PerformanceLevel
import com.electrodig.voidmusic.persistence.CalibrationPoint
import com.electrodig.voidmusic.persistence.CURRENT_KIT_SELECTION_VERSION
import com.electrodig.voidmusic.persistence.Settings
import com.electrodig.voidmusic.persistence.SettingsRepository
import com.electrodig.voidmusic.performance.RuntimePerformancePolicy
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
    private val performancePolicy = RuntimePerformancePolicy(app)

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    /** The only subscription to DataStore settings, including loading/error state. */
    val settingsState: StateFlow<SettingsLoadState> = repo.settings
        .map<Settings, SettingsLoadState>(SettingsLoadState::Ready)
        .catch { error -> emit(SettingsLoadState.Error(Settings.DEFAULT, error)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsLoadState.Loading)

    /** Persisted settings exposed for existing UI callers. */
    val settings: StateFlow<Settings> = settingsState
        .map(SettingsLoadState::settingsOrDefault)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT)

    /** Becomes true after DataStore has produced either a snapshot or an error. */
    val settingsLoaded: StateFlow<Boolean> = settingsState
        .map { it !is SettingsLoadState.Loading }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Effective tier after temporary power/thermal constraints; never persisted. */
    val runtimePerformanceLevel: StateFlow<PerformanceLevel> = combine(
        settings,
        performancePolicy.constraints
    ) { saved, constraints -> constraints.effectiveLevel(saved.performanceLevel) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            performancePolicy.constraints.value.effectiveLevel(Settings.DEFAULT.performanceLevel)
        )
    /** One atomic state prevents choosing a route from StateFlow's temporary default. */
    val onboardingState: StateFlow<OnboardingState> = repo.onboardingCompleted
        .map { completed -> OnboardingState(isLoaded = true, completed = completed) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OnboardingState())

    init {
        viewModelScope.launch {
            settings.collect { saved ->
                val mode = StudioMode.entries.firstOrNull { it.name == saved.lastMode } ?: StudioMode.TAP
                _uiState.update { it.copy(mode = mode) }
            }
        }
    }

    override fun onCleared() {
        performancePolicy.close()
        super.onCleared()
    }

    /** Latest drum zones detected by the segmentation pipeline (PRD F4.5). */
    private val _zones = MutableStateFlow<List<DrumZone>>(emptyList())
    val zones: StateFlow<List<DrumZone>> = _zones.asStateFlow()

    /** Zone ids that flashed on a recent trigger, for the overlay highlight (F2). */
    private val _flashedZoneIds = MutableStateFlow<Set<Int>>(emptySet())
    val flashedZoneIds: StateFlow<Set<Int>> = _flashedZoneIds.asStateFlow()

    fun setMode(mode: StudioMode) {
        _uiState.update { it.copy(mode = mode) }
        repoUpdate { it.copy(lastMode = mode.name) }
    }

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

    fun setActiveKit(id: String) {
        repoUpdate {
            it.copy(
                activeKitId = id,
                kitSelectionVersion = CURRENT_KIT_SELECTION_VERSION
            )
        }
    }

    fun setHitVelocityThreshold(value: Float) {
        repoUpdate { it.copy(hitVelocityThreshold = value.coerceIn(0.2f, 2.0f)) }
    }

    fun setHitCooldownMs(value: Long) {
        repoUpdate { it.copy(hitCooldownMs = value.coerceIn(50L, 500L)) }
    }

    fun setSmoothing(minCutoff: Float, beta: Float) {
        repoUpdate {
            it.copy(
                smoothingMinCutoff = minCutoff.coerceIn(1.5f, 4.0f),
                smoothingBeta = beta.coerceIn(0.02f, 0.1f)
            )
        }
    }

    fun saveSequence(bpm: Int, grid: List<List<Boolean>>) {
        val safeGrid = List(4) { row -> List(16) { step -> grid.getOrNull(row)?.getOrNull(step) ?: false } }
        repoUpdate { it.copy(lastBpm = bpm.coerceIn(40, 220), sequenceGrid = safeGrid) }
    }

    fun setCalibration(corners: List<com.electrodig.voidmusic.detection.grid.GridScanner.GridPoint>) {
        if (corners.size != 4) return
        val snapshot = corners.map { CalibrationPoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
        repoUpdate { it.copy(calibration = snapshot) }
    }

    fun resetSettings() {
        viewModelScope.launch { repo.reset() }
    }

    fun completeOnboarding() {
        viewModelScope.launch { repo.markOnboardingCompleted() }
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

data class OnboardingState(
    val isLoaded: Boolean = false,
    val completed: Boolean = false
)

sealed interface SettingsLoadState {
    data object Loading : SettingsLoadState
    data class Ready(val settings: Settings) : SettingsLoadState
    data class Error(val fallback: Settings, val cause: Throwable) : SettingsLoadState

    fun settingsOrDefault(): Settings = when (this) {
        Loading -> Settings.DEFAULT
        is Ready -> settings
        is Error -> fallback
    }
}
