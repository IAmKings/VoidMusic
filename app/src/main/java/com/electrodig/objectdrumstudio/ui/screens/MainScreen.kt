package com.electrodig.objectdrumstudio.ui.screens

import android.Manifest
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.electrodig.objectdrumstudio.audio.DrumEngine
import com.electrodig.objectdrumstudio.audio.BuiltInKits
import com.electrodig.objectdrumstudio.audio.Transport
import com.electrodig.objectdrumstudio.camera.CameraModule
import com.electrodig.objectdrumstudio.camera.CameraPreview
import com.electrodig.objectdrumstudio.camera.FrameRouter
import com.electrodig.objectdrumstudio.camera.PreviewCoordinateMapper
import com.electrodig.objectdrumstudio.detection.color.ColorSegmenter
import com.electrodig.objectdrumstudio.detection.color.DrumZone
import com.electrodig.objectdrumstudio.detection.color.HsvRange
import com.electrodig.objectdrumstudio.detection.color.HsvColorPicker
import com.electrodig.objectdrumstudio.detection.color.OpenCvLoader
import com.electrodig.objectdrumstudio.detection.color.ZoneTracker
import com.electrodig.objectdrumstudio.detection.grid.GridScanner
import com.electrodig.objectdrumstudio.detection.hand.HandTracker
import com.electrodig.objectdrumstudio.detection.hand.OneEuroHandStabilizer
import com.electrodig.objectdrumstudio.detection.hit.HitArbiter
import com.electrodig.objectdrumstudio.detection.hit.HitDetector
import com.electrodig.objectdrumstudio.persistence.PerformanceConfig
import com.electrodig.objectdrumstudio.session.SessionViewModel
import com.electrodig.objectdrumstudio.session.StudioMode
import com.electrodig.objectdrumstudio.ui.components.CalibrationOverlay
import com.electrodig.objectdrumstudio.ui.components.ColorControls
import com.electrodig.objectdrumstudio.ui.components.DrumZoneOverlay
import com.electrodig.objectdrumstudio.ui.components.HandOverlay
import com.electrodig.objectdrumstudio.ui.components.HudPanel
import com.electrodig.objectdrumstudio.ui.components.SequencerControls
import com.electrodig.objectdrumstudio.ui.components.StepSequencerOverlay
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect

/**
 * Main viewfinder screen. Gated by CAMERA permission. Once granted:
 *  - frames flow through a [FrameRouter] to both the [HandTracker] (M1) and
 *    [ColorSegmenter] (M2),
 *  - hand skeleton + drum-zone overlays render over the feed,
 *  - a calibration FAB toggles the 4-point perspective overlay (PRD F1.4),
 *  - HSV colour controls tune segmentation live (PRD F4.1).
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = viewModel(),
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val camPermission = rememberPermissionState(Manifest.permission.CAMERA)

    val settings by viewModel.settings.collectAsState()
    val settingsLoaded by viewModel.settingsLoaded.collectAsState()
    val perfConfig = PerformanceConfig.forLevel(settings.performanceLevel)

    // HandTracker and segmenter are keyed by performance level so tier changes
    // take effect (recreates with new resolution / delegate / maxHands / downsample).
    val handTracker = remember(
        settings.performanceLevel,
        settings.smoothingMinCutoff,
        settings.smoothingBeta
    ) {
        HandTracker(
            context,
            maxHands = perfConfig.maxHands,
            delegate = perfConfig.mediaPipeDelegate,
            stabilizer = OneEuroHandStabilizer(
                minCutoff = settings.smoothingMinCutoff,
                beta = settings.smoothingBeta
            )
        )
    }
    val segmenter = remember(settings.performanceLevel) {
        ColorSegmenter(downsample = perfConfig.colorDownsample)
    }
    val gridScanner = remember { GridScanner() }
    val drumEngine = remember { DrumEngine(context) }
    val transport = remember { Transport(drumEngine) }
    val hitDetector = remember(settings.hitVelocityThreshold, settings.hitCooldownMs) {
        HitDetector(
            velocityThreshold = settings.hitVelocityThreshold,
            cooldownMs = settings.hitCooldownMs
        )
    }
    val hitArbiter = remember { HitArbiter() }
    val view = LocalView.current

    val hands by handTracker.hands.collectAsState()
    val session by viewModel.uiState.collectAsState()
    val zones by viewModel.zones.collectAsState()
    val flashedZoneIds by viewModel.flashedZoneIds.collectAsState()
    val detectionConfig = settings.detectionConfig
    val sequence by transport.state.collectAsState()

    var activePresetIndex by remember { mutableIntStateOf(0) }
    var calibrating by remember { mutableStateOf(false) }
    var colorPanelOpen by remember { mutableStateOf(true) }
    var pickingColor by remember { mutableStateOf(false) }
    var sessionRestored by remember { mutableStateOf(false) }
    val modeHolder = remember { AtomicReference(session.mode) }
    LaunchedEffect(session.mode) { modeHolder.set(session.mode) }
    val hapticHolder = remember { AtomicReference(settings.hapticEnabled) }
    LaunchedEffect(settings.hapticEnabled) { hapticHolder.set(settings.hapticEnabled) }
    val lastCellToggleMs = remember { HashMap<Long, Long>() }
    // Color segmentation runs every Nth frame (zones are spatially slow-moving;
    // running it every frame starves the hand path on low-FPS devices). Hit
    // detection runs every frame using the most recent cached zones.
    val zoneFrameCounter = remember { intArrayOf(0) }
    val cachedZones = remember { AtomicReference<List<DrumZone>>(emptyList()) }
    val zoneTracker = remember(settings.performanceLevel) { ZoneTracker() }
    val viewportRef = remember { AtomicReference<PreviewViewport?>(null) }
    val pickerRequestRef = remember { AtomicReference<PickerRequest?>(null) }
    val analysisEvents = remember {
        MutableSharedFlow<AnalysisEvent>(
            extraBufferCapacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
    }

    // The camera executor only emits immutable events. Compose/Android View work
    // is deliberately consumed here on the main dispatcher.
    LaunchedEffect(analysisEvents) {
        analysisEvents.collect { event ->
            when (event) {
                is AnalysisEvent.Zones -> viewModel.setZones(event.zones)
                is AnalysisEvent.Fps -> viewModel.setAnalysisFps(event.value)
                is AnalysisEvent.Hit -> {
                    viewModel.flashZones(event.zoneIds)
                    if (event.haptic) performTapHaptic(view)
                }
                is AnalysisEvent.StepToggle -> if (event.haptic) performTapHaptic(view)
                is AnalysisEvent.ColorPicked -> {
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

    // Initialise OpenCV + the landmarker + the audio engine once.
    LaunchedEffect(Unit) {
        OpenCvLoader.ensureInitialised(context)
        handTracker.setup()
        drumEngine.setKit(BuiltInKits.byIndex(settings.activeKitIndex))
        drumEngine.start()
        drumEngine.setMasterVolume(settings.masterVolume)
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
    LaunchedEffect(settings.activeKitIndex) {
        drumEngine.setKit(BuiltInKits.byIndex(settings.activeKitIndex))
    }
    DisposableEffect(Unit) {
        onDispose {
            handTracker.close()
            drumEngine.stop()
            transport.release()
        }
    }

    // Bridge hand count into HUD state.
    LaunchedEffect(hands.size) {
        viewModel.updateDetection(
            objectCount = zones.size,
            handCount = hands.size,
            signal = if (hands.isEmpty() && zones.isEmpty()) 0f else 0.65f
        )
    }

    // Atomic references prevent the camera executor from reading Compose
    // snapshots while still giving it the current configuration.
    val configHolder = remember { AtomicReference(detectionConfig) }
    LaunchedEffect(detectionConfig) { configHolder.set(detectionConfig) }

    val camera = remember(
        settings.performanceLevel,
        settings.smoothingMinCutoff,
        settings.smoothingBeta,
        settings.hitVelocityThreshold,
        settings.hitCooldownMs
    ) {
        CameraModule(
            context = context,
            targetResolution = perfConfig.cameraResolution,
            analyzer = FrameRouter(
                bitmapConsumers = listOf(
                    // Hand tracking (FrameRouter closes the proxy).
                    { bitmap, proxy ->
                        handTracker.detect(bitmap, handTracker.timestampMs(proxy.imageInfo.timestamp))
                    },
                    // Colour segmentation → zones, then hit detection → triggers.
                    segmentation@ { bitmap, proxy ->
                        val ts = handTracker.timestampMs(proxy.imageInfo.timestamp)
                        // Segmentation is expensive OpenCV work; run it every 3rd
                        // frame so it doesn't starve the hand path on low-FPS
                        // devices. Zones move slowly so a stale cache is fine.
                        zoneFrameCounter[0]++
                        val viewport = viewportRef.get()
                        val mapper = viewport?.let {
                            PreviewCoordinateMapper.forFillCenter(
                                bitmap.width, bitmap.height, it.width, it.height
                            )
                        } ?: return@segmentation
                        pickerRequestRef.getAndSet(null)?.let { request ->
                            val sourcePoint = mapper.unmap(request.x, request.y)
                            HsvColorPicker.sample(bitmap, sourcePoint.x, sourcePoint.y)?.let { range ->
                                analysisEvents.tryEmit(AnalysisEvent.ColorPicked(request.presetIndex, range))
                            }
                        }
                        if (zoneFrameCounter[0] % 3 == 0) {
                            val trackedZones = zoneTracker.update(
                                segmenter.segment(bitmap, configHolder.get()),
                                ts
                            )
                            cachedZones.set(trackedZones)
                            analysisEvents.tryEmit(AnalysisEvent.Zones(trackedZones.map(mapper::map)))
                        }
                        val zs = cachedZones.get().map(mapper::map)
                        // Read raw (un-smoothed) hands directly from the StateFlow.
                        // StateFlow.value is thread-safe. Map it into PreviewView
                        // coordinates before hit testing so it shares the zone/grid
                        // coordinate system.
                        val latestHands = handTracker.rawHands.value.map { hand ->
                            PreviewCoordinateMapper.forFillCenter(
                                hand.imageWidth, hand.imageHeight, viewport.width, viewport.height
                            )?.map(hand) ?: hand
                        }
                        val candidates = hitDetector.update(latestHands, ts)
                        if (modeHolder.get() == StudioMode.STEP) {
                            // Step mode: a downward tap toggles the cell under the fingertip (F3.3).
                            if (candidates.isNotEmpty()) {
                                val tip = latestHands.firstOrNull()?.fingertip
                                if (tip != null && gridScanner.isCalibrated()) {
                                    val cell = gridScanner.locateCell(
                                        GridScanner.GridPoint(tip.x, tip.y)
                                    )
                                    if (cell != null) {
                                        val key = cell.row.toLong() * 100 + cell.step
                                        val last = lastCellToggleMs[key] ?: 0L
                                        if (ts - last >= STEP_TOGGLE_COOLDOWN_MS) {
                                            transport.toggleStep(cell.row, cell.step)
                                            lastCellToggleMs[key] = ts
                                            analysisEvents.tryEmit(
                                                AnalysisEvent.StepToggle(hapticHolder.get())
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Tap mode: hits resolve to drum zones and fire the engine (M3).
                            val triggers = hitArbiter.arbitrate(candidates, zs)
                            if (triggers.isNotEmpty()) {
                                for (t in triggers) drumEngine.trigger(t.pad, t.velocity)
                                analysisEvents.tryEmit(
                                    AnalysisEvent.Hit(
                                        zoneIds = triggers.map { it.zoneId },
                                        haptic = hapticHolder.get()
                                    )
                                )
                            }
                        }
                    }
                ),
                imageProxyConsumer = { proxy -> proxy.close() },
                onFpsUpdate = { fps -> analysisEvents.tryEmit(AnalysisEvent.Fps(fps)) }
            )
        )
    }

    val appSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    LaunchedEffect(Unit) {
        if (!camPermission.status.isGranted) camPermission.launchPermissionRequest()
    }

    val granted = camPermission.status.isGranted
    val permanentlyDenied = !granted && !camPermission.status.shouldShowRationale

    // PreviewView reported once by CameraPreview's AndroidView factory; binding
    // happens in a LaunchedEffect below so it re-runs when the `camera` instance
    // is rebuilt (e.g. after a performance-tier change once settings settle).
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var previewViewport by remember { mutableStateOf<PreviewViewport?>(null) }
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

    // Unbind the old camera instance whenever it is replaced (performance tier
    // change rebuilds `camera` via remember(settings.performanceLevel)). Without
    // this, the stale instance keeps the Preview surface while the new tracker
    // — whose StateFlow the UI actually collects — never receives frames.
    DisposableEffect(camera) {
        onDispose { camera.stop() }
    }

    // (Re)bind preview + analysis whenever the camera instance, lifecycle, or
    // PreviewView becomes available. This covers both the first-bind case and
    // the rebuild-after-settings-settle case that previously left the bound
    // camera and the collected HandTracker as two different instances.
    LaunchedEffect(camera, lifecycleOwner, previewView) {
        val pv = previewView ?: return@LaunchedEffect
        camera.startPreview(lifecycleOwner, pv)
        viewModel.setCameraReady(true)
    }

    // Keep the camera use cases' targetRotation in sync with the display so
    // imageInfo.rotationDegrees reflects the current orientation (needed for
    // correct bitmap rotation in FrameRouter after device rotation, since the
    // manifest uses configChanges to self-handle orientation without recreating
    // the Activity / rebinding the camera).
    DisposableEffect(Unit) {
        val ctx = context
        val displayManager = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                val pv = previewView ?: return
                val rotation = pv.display?.rotation ?: return
                camera.updateTargetRotation(rotation)
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
                    viewportRef.set(viewport)
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
                                    pickerRequestRef.set(
                                        PickerRequest(
                                            x = offset.x / width,
                                            y = offset.y / height,
                                            presetIndex = activePresetIndex
                                        )
                                    )
                                }
                                pickingColor = false
                            }
                        }
                ) { }
            }

            if (!calibrating) {
                if (session.mode == StudioMode.STEP) {
                    // Step-sequencer grid over the calibrated paper (F3.2).
                    val tip = mappedHands.firstOrNull()?.fingertip
                    val fingertip = if (tip != null) GridScanner.GridPoint(tip.x, tip.y) else null
                    StepSequencerOverlay(
                        gridScanner = gridScanner,
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
                modifier = Modifier
                    .statusBarsPadding()
                    .align(Alignment.TopCenter)
            )

            // Calibration overlay (F1.4) replaces the live overlays while active.
            if (calibrating) {
                CalibrationOverlay(
                    gridScanner = gridScanner,
                    initialCorners = gridScanner.calibration(),
                    onConfirm = viewModel::setCalibration,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Right-side FABs: calibration / settings.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExtendedFloatingActionButton(
                    text = { Text(if (calibrating) "完成校准" else "校准") },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    onClick = { calibrating = !calibrating }
                )
                ExtendedFloatingActionButton(
                    text = { Text("设置") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    onClick = onOpenSettings
                )
            }

            // Bottom controls: HSV in TAP mode (F4.1), transport in STEP mode (F3.4/F3.5).
            if (!calibrating) {
                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    if (session.mode == StudioMode.STEP) {
                        SequencerControls(
                            bpm = sequence.bpm,
                            isPlaying = sequence.isPlaying,
                            onBpmChange = transport::setBpm,
                            onPlayToggle = { if (sequence.isPlaying) transport.stop() else transport.play() },
                            onClear = transport::clear
                        )
                    } else if (colorPanelOpen) {
                        ColorControls(
                            presets = detectionConfig.presets,
                            activeIndex = activePresetIndex,
                            onActiveChange = { activePresetIndex = it },
                            onPickColor = { pickingColor = true },
                            isPicking = pickingColor,
                            onActiveRangeChange = { range: HsvRange ->
                                viewModel.updateDetectionConfig { cfg ->
                                    val presets = cfg.presets.toMutableList()
                                    if (activePresetIndex in presets.indices) {
                                        presets[activePresetIndex] =
                                            presets[activePresetIndex].copy(range = range)
                                    }
                                    cfg.copy(presets = presets)
                                }
                            }
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
/** Min ms between two toggles of the SAME step cell (prevents stutter-toggling). */
private const val STEP_TOGGLE_COOLDOWN_MS = 350L

private data class PreviewViewport(val width: Int, val height: Int)

/** Immutable cross-thread messages from the camera analysis executor to UI. */
private sealed interface AnalysisEvent {
    data class Zones(val zones: List<DrumZone>) : AnalysisEvent
    data class Fps(val value: Float) : AnalysisEvent
    data class Hit(val zoneIds: List<Int>, val haptic: Boolean) : AnalysisEvent
    data class StepToggle(val haptic: Boolean) : AnalysisEvent
    data class ColorPicked(val presetIndex: Int, val range: HsvRange) : AnalysisEvent
}

private data class PickerRequest(val x: Float, val y: Float, val presetIndex: Int)

private fun performTapHaptic(view: android.view.View) {
    view.performHapticFeedback(
        android.view.HapticFeedbackConstants.KEYBOARD_TAP,
        android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
    )
}
