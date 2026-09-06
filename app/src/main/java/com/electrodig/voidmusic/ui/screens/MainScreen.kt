package com.electrodig.voidmusic.ui.screens

import android.Manifest
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.electrodig.voidmusic.audio.DrumEngine
import com.electrodig.voidmusic.audio.AudioBackend
import com.electrodig.voidmusic.audio.AudioRuntimePhase
import com.electrodig.voidmusic.audio.BuiltInKits
import com.electrodig.voidmusic.audio.LibraryError
import com.electrodig.voidmusic.audio.LibraryErrorCode
import com.electrodig.voidmusic.audio.LibraryResult
import com.electrodig.voidmusic.audio.Transport
import com.electrodig.voidmusic.camera.CameraPreview
import com.electrodig.voidmusic.camera.PreviewCoordinateMapper
import com.electrodig.voidmusic.camera.VisionMetrics
import com.electrodig.voidmusic.detection.color.HsvRange
import com.electrodig.voidmusic.detection.grid.GridScanner
import com.electrodig.voidmusic.performance.LivePerformanceEvent
import com.electrodig.voidmusic.performance.LivePerformanceInputs
import com.electrodig.voidmusic.performance.LivePerformancePipeline
import com.electrodig.voidmusic.persistence.PerformanceConfig
import com.electrodig.voidmusic.session.SessionViewModel
import com.electrodig.voidmusic.session.StudioMode
import com.electrodig.voidmusic.ui.components.CalibrationOverlay
import com.electrodig.voidmusic.ui.components.ColorControls
import com.electrodig.voidmusic.ui.components.DrumZoneOverlay
import com.electrodig.voidmusic.ui.components.HandOverlay
import com.electrodig.voidmusic.ui.components.HudPanel
import com.electrodig.voidmusic.ui.components.SequencerControls
import com.electrodig.voidmusic.ui.components.StepSequencerOverlay
import com.electrodig.voidmusic.ui.components.TapControlDock
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Main viewfinder screen. Gated by CAMERA permission. Once granted:
 *  - frames flow through a [FrameRouter] to both the [HandTracker] (M1) and
 *    [ColorSegmenter] (M2),
 *  - hand skeleton + drum-zone overlays render over the feed,
 *  - a calibration FAB toggles the 4-point perspective overlay (PRD F1.4),
 *  - HSV colour controls tune segmentation live (PRD F4.1).
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = viewModel(),
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val camPermission = rememberPermissionState(Manifest.permission.CAMERA)
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val granted = camPermission.status.isGranted
    val performanceActive = granted && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val settingsLoaded by viewModel.settingsLoaded.collectAsStateWithLifecycle()
    val playbackKitRevision by viewModel.playbackKitRevision.collectAsStateWithLifecycle()
    val runtimePerformanceLevel by viewModel.runtimePerformanceLevel.collectAsStateWithLifecycle()
    val perfConfig = PerformanceConfig.forLevel(runtimePerformanceLevel)
    val gridScanner = remember { GridScanner() }
    val drumEngine = remember { DrumEngine(context) }
    val transport = remember { Transport(drumEngine) }
    val view = LocalView.current

    val session by viewModel.uiState.collectAsStateWithLifecycle()
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val flashedZoneIds by viewModel.flashedZoneIds.collectAsStateWithLifecycle()
    val detectionConfig = settings.detectionConfig
    val sequence by transport.state.collectAsStateWithLifecycle()
    val audioStatus by drumEngine.status.collectAsStateWithLifecycle()
    val pipeline = remember(
        runtimePerformanceLevel,
        settings.smoothingMinCutoff,
        settings.smoothingBeta,
        settings.hitVelocityThreshold,
        settings.hitCooldownMs
    ) {
        LivePerformancePipeline(
            context = context,
            performanceConfig = perfConfig,
            smoothingMinCutoff = settings.smoothingMinCutoff,
            smoothingBeta = settings.smoothingBeta,
            hitVelocityThreshold = settings.hitVelocityThreshold,
            hitCooldownMs = settings.hitCooldownMs,
            initialInputs = LivePerformanceInputs(session.mode, detectionConfig),
            projectionProvider = gridScanner::projection,
            audioTrigger = drumEngine::trigger,
            stepToggle = transport::toggleStep
        )
    }
    val hands by pipeline.hands.collectAsStateWithLifecycle()

    var activePresetIndex by remember { mutableIntStateOf(0) }
    var calibrating by remember { mutableStateOf(false) }
    var showColorControls by remember { mutableStateOf(false) }
    var pickingColor by remember { mutableStateOf(false) }
    var sessionRestored by remember { mutableStateOf(false) }
    var visionMetrics by remember(pipeline) { mutableStateOf(VisionMetrics()) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var previewViewport by remember { mutableStateOf<PreviewViewport?>(null) }
    var appliedKitId by remember { mutableStateOf<String?>(null) }
    val hapticEnabled by rememberUpdatedState(settings.hapticEnabled)

    LaunchedEffect(pipeline, session.mode, detectionConfig) {
        pipeline.updateInputs(LivePerformanceInputs(session.mode, detectionConfig))
    }
    LaunchedEffect(pipeline) {
        pipeline.events.collect { event ->
            when (event) {
                is LivePerformanceEvent.Zones -> viewModel.setZones(event.zones)
                is LivePerformanceEvent.Fps -> viewModel.setAnalysisFps(event.value)
                is LivePerformanceEvent.Metrics -> visionMetrics = event.value
                is LivePerformanceEvent.Hit -> {
                    viewModel.flashZones(event.zoneIds)
                    if (hapticEnabled) performTapHaptic(view)
                }
                LivePerformanceEvent.StepToggle -> if (hapticEnabled) performTapHaptic(view)
                is LivePerformanceEvent.ColorPicked -> {
                    viewModel.updateDetectionConfig { config ->
                        val presets = config.presets.toMutableList()
                        if (event.presetIndex in presets.indices) {
                            presets[event.presetIndex] = presets[event.presetIndex].copy(range = event.range)
                        }
                        config.copy(presets = presets)
                    }
                }
            }
        }
    }

    // Preparation performs Room/file/decode work on KitLibrary's I/O dispatcher.
    // Backend startup also stays off the main thread because SoundPool completion is awaited.
    LaunchedEffect(
        performanceActive,
        settingsLoaded,
        settings.activeKitId,
        playbackKitRevision,
        drumEngine
    ) {
        if (!performanceActive || !settingsLoaded) {
            transport.stop()
            drumEngine.stop()
            return@LaunchedEffect
        }
        val requestedKitId = settings.activeKitId ?: BuiltInKits.DEFAULT.id
        when (val result = viewModel.prepareKit(requestedKitId)) {
            is LibraryResult.Success -> {
                val switched = withContext(Dispatchers.Default) {
                    drumEngine.setMasterVolume(settings.masterVolume)
                    drumEngine.start(result.value)
                }
                if (switched) {
                    appliedKitId = result.value.id
                } else {
                    viewModel.reportKitPlaybackFailure(
                        LibraryError(LibraryErrorCode.PLAYBACK_FAILURE)
                    )
                    Toast.makeText(
                        context,
                        "音色播放启动失败，已恢复上一套音色",
                        Toast.LENGTH_LONG
                    ).show()
                    val fallbackKitId = appliedKitId ?: BuiltInKits.DEFAULT.id
                    if (requestedKitId != fallbackKitId) viewModel.setActiveKit(fallbackKitId)
                }
            }
            is LibraryResult.Failure -> {
                viewModel.reportKitPlaybackFailure(result.error)
                val fallbackKitId = appliedKitId ?: BuiltInKits.DEFAULT.id
                if (requestedKitId != fallbackKitId) viewModel.setActiveKit(fallbackKitId)
            }
        }
    }
    // Restore only after DataStore emits. Otherwise stateIn's temporary defaults
    // could overwrite a real saved session during cold start.
    LaunchedEffect(settingsLoaded) {
        if (!settingsLoaded || sessionRestored) return@LaunchedEffect
        transport.restore(settings.lastBpm, settings.sequenceGrid)
        val corners = settings.calibration
        if (corners.size == 4) {
            gridScanner.setCalibration(corners.map { GridScanner.GridPoint(it.x, it.y) })
        } else {
            gridScanner.clearCalibration()
        }
        sessionRestored = true
    }
    LaunchedEffect(sessionRestored, sequence.bpm, sequence.grid) {
        if (sessionRestored) viewModel.saveSequence(sequence.bpm, sequence.grid)
    }
    // Apply audio settings as they change (PRD F8).
    LaunchedEffect(settings.masterVolume) { drumEngine.setMasterVolume(settings.masterVolume) }
    DisposableEffect(Unit) {
        onDispose {
            drumEngine.stop()
            transport.release()
        }
    }
    DisposableEffect(pipeline) {
        onDispose { pipeline.close() }
    }

    // Bridge hand count into HUD state.
    LaunchedEffect(hands.size, zones.size) {
        viewModel.updateDetection(
            objectCount = zones.size,
            handCount = hands.size,
            signal = if (hands.isEmpty() && zones.isEmpty()) 0f else 0.65f
        )
    }

    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    LaunchedEffect(Unit) {
        if (!camPermission.status.isGranted) camPermission.launchPermissionRequest()
    }

    val permanentlyDenied = !granted && !camPermission.status.shouldShowRationale

    val mappedHands = remember(hands, previewViewport) {
        val viewport = previewViewport
        hands.map { hand ->
            viewport?.let {
                PreviewCoordinateMapper.forFillCenter(
                    hand.imageWidth, hand.imageHeight, it.width, it.height
                )?.map(hand)
            } ?: hand
        }
    }

    // Bind the deep pipeline only while permission, foreground and surface are ready.
    DisposableEffect(performanceActive, pipeline, lifecycleOwner, previewView) {
        val pv = previewView
        if (performanceActive && pv != null) {
            pipeline.start(lifecycleOwner, pv, viewModel::setCameraReady)
        } else {
            viewModel.setCameraReady(false)
        }
        onDispose {
            pipeline.stop()
            viewModel.setCameraReady(false)
        }
    }

    // Keep the camera use cases' targetRotation in sync with the display so
    // imageInfo.rotationDegrees reflects the current orientation (needed for
    // correct bitmap rotation in FrameRouter after device rotation, since the
    // manifest uses configChanges to self-handle orientation without recreating
    // the Activity / rebinding the camera).
    DisposableEffect(pipeline, previewView) {
        val ctx = context
        val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                val pv = previewView ?: return
                val rotation = pv.display?.rotation ?: return
                pipeline.updateTargetRotation(rotation)
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displayManager.registerDisplayListener(listener, null)
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (granted) {
            CameraPreview(
                onPreviewViewReady = { pv: PreviewView ->
                    previewView = pv
                },
                onViewportSizeChanged = { width, height ->
                    val viewport = PreviewViewport(width, height)
                    previewViewport = viewport
                    pipeline.updateViewport(width, height)
                }
            )

            if (pickingColor) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(pickingColor) {
                            detectTapGestures { offset ->
                                val width = size.width.toFloat()
                                val height = size.height.toFloat()
                                if (width > 0f && height > 0f) {
                                    pipeline.requestColorPick(
                                        x = offset.x / width,
                                        y = offset.y / height,
                                        presetIndex = activePresetIndex
                                    )
                                }
                                pickingColor = false
                            }
                        }
                ) { }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 108.dp),
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = "点按取景器中的目标颜色",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge
                    )
                }
            }

            if (!calibrating) {
                if (session.mode == StudioMode.STEP) {
                    // Step-sequencer grid over the calibrated paper (F3.2).
                    val tip = mappedHands.firstOrNull()?.fingertip
                    val fingertip = if (tip != null) GridScanner.GridPoint(tip.x, tip.y) else null
                    StepSequencerOverlay(
                        projection = gridScanner.projection(),
                        sequence = sequence,
                        fingertip = fingertip,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    DrumZoneOverlay(
                        zones = zones,
                        flashedZoneIds = flashedZoneIds,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                HandOverlay(hands = mappedHands, modifier = Modifier.fillMaxSize())
            }

            // Clear the hit-flash highlight after a short beat.
            LaunchedEffect(flashedZoneIds) {
                if (flashedZoneIds.isNotEmpty()) {
                    kotlinx.coroutines.delay(FLASH_DURATION_MS)
                    viewModel.clearFlash(flashedZoneIds)
                }
            }

            HudPanel(
                mode = session.mode,
                objectCount = zones.size,
                handCount = hands.size,
                signalStrength = session.signalStrength,
                onModeSelected = viewModel::setMode,
                fps = session.analysisFps,
                metrics = visionMetrics,
                audioBackendLabel = when (audioStatus.phase) {
                    AudioRuntimePhase.RECOVERING -> "恢复中"
                    AudioRuntimePhase.FAILED -> "不可用"
                    AudioRuntimePhase.STARTING -> "启动中"
                    AudioRuntimePhase.STOPPED -> ""
                    AudioRuntimePhase.RUNNING -> when (audioStatus.backend) {
                        AudioBackend.NATIVE_OBOE -> "Oboe"
                        AudioBackend.SOUND_POOL -> "SoundPool"
                        AudioBackend.NONE -> ""
                    }
                },
                droppedTriggerCount = audioStatus.droppedTriggerCount,
                audioXRunCount = audioStatus.xRunCount,
                modifier = Modifier
                    .statusBarsPadding()
                    .align(Alignment.TopCenter)
            )

            // Calibration overlay (F1.4) replaces the live overlays while active.
            if (calibrating) {
                CalibrationOverlay(
                    initialCorners = gridScanner.calibration(),
                    onConfirm = { corners ->
                        if (gridScanner.setCalibration(corners)) {
                            viewModel.setCalibration(corners)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Compact shortcuts leave the viewfinder unobscured during performance.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = { calibrating = !calibrating },
                    containerColor = if (calibrating) {
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    }
                )
                {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = if (calibrating) "退出校准" else "开始校准"
                    )
                }
                SmallFloatingActionButton(
                    onClick = onOpenSettings,
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "打开设置")
                }
            }

            // Bottom controls: HSV in TAP mode (F4.1), transport in STEP mode (F3.4/F3.5).
            if (!calibrating) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                ) {
                    if (session.mode == StudioMode.STEP) {
                        SequencerControls(
                            bpm = sequence.bpm,
                            isPlaying = sequence.isPlaying,
                            onBpmChange = transport::setBpm,
                            onPlayToggle = { if (sequence.isPlaying) transport.stop() else transport.play() },
                            onClear = transport::clear
                        )
                    } else {
                        TapControlDock(
                            preset = detectionConfig.presets.getOrNull(activePresetIndex),
                            onOpenControls = { showColorControls = true }
                        )
                    }
                }
            }
        } else {
            PermissionGate(
                rationaleNeeded = camPermission.status.shouldShowRationale,
                onOpenSettings = {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    appSettingsLauncher.launch(intent)
                }
            )
        }
    }

    if (granted && showColorControls && !calibrating) {
        ModalBottomSheet(onDismissRequest = { showColorControls = false }) {
            ColorControls(
                presets = detectionConfig.presets,
                activeIndex = activePresetIndex,
                onActiveChange = { activePresetIndex = it },
                onPickColor = {
                    showColorControls = false
                    pickingColor = true
                },
                isPicking = false,
                onActiveRangeChange = { range: HsvRange ->
                    viewModel.updateDetectionConfig { cfg ->
                        val presets = cfg.presets.toMutableList()
                        if (activePresetIndex in presets.indices) {
                            presets[activePresetIndex] = presets[activePresetIndex].copy(range = range)
                        }
                        cfg.copy(presets = presets)
                    }
                },
                modifier = Modifier.navigationBarsPadding()
            )
        }
    }

    LaunchedEffect(permanentlyDenied) {
        if (permanentlyDenied) {
            Toast.makeText(
                context,
                "需要摄像头权限才能识别物件与追踪手部，请在系统设置中授予。",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

/** How long a triggered zone stays highlighted on the overlay. */
private const val FLASH_DURATION_MS = 120L

private data class PreviewViewport(val width: Int, val height: Int)

private fun performTapHaptic(view: android.view.View) {
    view.performHapticFeedback(
        android.view.HapticFeedbackConstants.KEYBOARD_TAP,
        android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
    )
}
