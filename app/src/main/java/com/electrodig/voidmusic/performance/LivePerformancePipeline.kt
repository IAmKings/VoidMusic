package com.electrodig.voidmusic.performance

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.electrodig.voidmusic.camera.CameraModule
import com.electrodig.voidmusic.camera.FrameRouter
import com.electrodig.voidmusic.camera.PreviewCoordinateMapper
import com.electrodig.voidmusic.camera.VisionMetrics
import com.electrodig.voidmusic.camera.VisionMetricsRecorder
import com.electrodig.voidmusic.detection.color.ColorSegmenter
import com.electrodig.voidmusic.detection.color.DetectionConfig
import com.electrodig.voidmusic.detection.color.DrumPad
import com.electrodig.voidmusic.detection.color.DrumZone
import com.electrodig.voidmusic.detection.color.HsvColorPicker
import com.electrodig.voidmusic.detection.color.HsvRange
import com.electrodig.voidmusic.detection.color.OpenCvLoader
import com.electrodig.voidmusic.detection.color.ZoneTracker
import com.electrodig.voidmusic.detection.grid.GridProjection
import com.electrodig.voidmusic.detection.hand.Hand
import com.electrodig.voidmusic.detection.hand.HandTracker
import com.electrodig.voidmusic.detection.hand.OneEuroHandStabilizer
import com.electrodig.voidmusic.detection.hand.TimestampedHands
import com.electrodig.voidmusic.detection.hit.HitArbiter
import com.electrodig.voidmusic.detection.hit.HitDetector
import com.electrodig.voidmusic.detection.hit.HitSnapshot
import com.electrodig.voidmusic.detection.hit.TapHitProcessor
import com.electrodig.voidmusic.persistence.PerformanceConfig
import com.electrodig.voidmusic.session.StudioMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Inputs that can change without rebuilding the expensive recognition stack. */
data class LivePerformanceInputs(
    val mode: StudioMode,
    val detectionConfig: DetectionConfig
)

/** Immutable messages crossing from analysis threads to the UI layer. */
sealed interface LivePerformanceEvent {
    data class Zones(val zones: List<DrumZone>) : LivePerformanceEvent
    data class Fps(val value: Float) : LivePerformanceEvent
    data class Metrics(val value: VisionMetrics) : LivePerformanceEvent
    data class Hit(val zoneIds: List<Int>) : LivePerformanceEvent
    data object StepToggle : LivePerformanceEvent
    data class ColorPicked(val presetIndex: Int, val range: HsvRange) : LivePerformanceEvent
}

/**
 * Owns the live camera → recognition → hit pipeline behind a small lifecycle interface.
 *
 * UI callers provide immutable runtime inputs and consume immutable events. CameraX,
 * MediaPipe, OpenCV caches, thread-safe snapshots and hit arbitration stay local to
 * this module. [stop] releases native resources and [start] recreates those that are
 * not reusable, notably [ColorSegmenter].
 */
class LivePerformancePipeline(
    context: Context,
    private val performanceConfig: PerformanceConfig,
    smoothingMinCutoff: Float,
    smoothingBeta: Float,
    hitVelocityThreshold: Float,
    hitCooldownMs: Long,
    initialInputs: LivePerformanceInputs,
    private val projectionProvider: () -> GridProjection?,
    private val audioTrigger: (DrumPad, Float) -> Unit,
    private val stepToggle: (row: Int, step: Int) -> Unit
) : AutoCloseable {

    private data class Viewport(val width: Int, val height: Int)
    private data class PickerRequest(val x: Float, val y: Float, val presetIndex: Int)

    private val appContext = context.applicationContext
    private val active = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val inputs = AtomicReference(initialInputs)
    private val viewport = AtomicReference<Viewport?>(null)
    private val pickerRequest = AtomicReference<PickerRequest?>(null)
    private val tapSnapshot = AtomicReference(HitSnapshot())
    private val cachedZones = AtomicReference<List<DrumZone>>(emptyList())
    private val cachedZonesTimestampMs = AtomicLong(Long.MIN_VALUE)
    private val segmenter = AtomicReference<ColorSegmenter?>(null)
    private val segmentationCadence = SegmentationCadence(performanceConfig.analysisFrameCap)
    private val metricsRecorder = VisionMetricsRecorder()
    private val zoneTracker = ZoneTracker()
    private val tapHitProcessor = TapHitProcessor(
        detector = HitDetector(hitVelocityThreshold, hitCooldownMs),
        arbiter = HitArbiter()
    )
    private val stepHitDetector = HitDetector(hitVelocityThreshold, hitCooldownMs)
    private val lastCellToggleMs = HashMap<Long, Long>()

    private val mutableEvents = MutableSharedFlow<LivePerformanceEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<LivePerformanceEvent> = mutableEvents.asSharedFlow()

    private val handTracker = HandTracker(
        appContext,
        maxHands = performanceConfig.maxHands,
        delegate = performanceConfig.mediaPipeDelegate,
        stabilizer = OneEuroHandStabilizer(
            minCutoff = smoothingMinCutoff,
            beta = smoothingBeta
        ),
        resultHandler = ::onHandResult
    )
    val hands: StateFlow<List<Hand>> = handTracker.hands

    private val camera = CameraModule(
        context = appContext,
        targetResolution = performanceConfig.cameraResolution,
        analyzer = FrameRouter(
            bitmapConsumers = listOf(
                { bitmap, proxy ->
                    if (active.get()) {
                        handTracker.detect(bitmap, handTracker.timestampMs(proxy.imageInfo.timestamp))
                    }
                },
                { bitmap, proxy -> analyzeSegmentation(bitmap, proxy) }
            ),
            imageProxyConsumer = { proxy -> proxy.close() },
            analysisFrameCap = performanceConfig.analysisFrameCap,
            onFpsUpdate = { fps ->
                if (active.get()) mutableEvents.tryEmit(LivePerformanceEvent.Fps(fps))
            }
        )
    )

    /** Starts native recognition resources and binds CameraX to [previewView]. */
    @Synchronized
    fun start(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onCameraBound: (Boolean) -> Unit = {}
    ) {
        if (closed.get()) {
            onCameraBound(false)
            return
        }
        if (active.getAndSet(true)) return
        OpenCvLoader.ensureInitialised(appContext)
        segmenter.set(ColorSegmenter(downsample = performanceConfig.colorDownsample))
        handTracker.setup()
        camera.startPreview(lifecycleOwner, previewView, onCameraBound)
    }

    /** Pauses camera and releases MediaPipe/OpenCV resources; the module remains reusable. */
    @Synchronized
    fun stop() {
        if (!active.getAndSet(false)) return
        camera.stopPreview()
        handTracker.close()
        segmenter.getAndSet(null)?.close()
        tapSnapshot.set(HitSnapshot())
        cachedZones.set(emptyList())
        cachedZonesTimestampMs.set(Long.MIN_VALUE)
        pickerRequest.set(null)
        segmentationCadence.reset()
        lastCellToggleMs.clear()
        mutableEvents.tryEmit(LivePerformanceEvent.Zones(emptyList()))
    }

    /** Updates mode and colour thresholds as one coherent analysis snapshot. */
    fun updateInputs(value: LivePerformanceInputs) {
        val previous = inputs.getAndSet(value)
        if (previous != value) segmentationCadence.invalidate()
    }

    /** Updates the PreviewView size used for source/display coordinate mapping. */
    fun updateViewport(width: Int, height: Int) {
        viewport.set(if (width > 0 && height > 0) Viewport(width, height) else null)
    }

    /** Samples one colour from the next available camera frame. */
    fun requestColorPick(x: Float, y: Float, presetIndex: Int) {
        pickerRequest.set(PickerRequest(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), presetIndex))
    }

    /** Applies a display rotation without rebinding CameraX. */
    fun updateTargetRotation(rotation: Int = Surface.ROTATION_0) {
        camera.updateTargetRotation(rotation)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        camera.close()
    }

    private fun onHandResult(frame: TimestampedHands) {
        if (!active.get()) return
        metricsRecorder.recordHandResult(frame.callbackCompletedAtMs - frame.timestampMs)
            ?.let { mutableEvents.tryEmit(LivePerformanceEvent.Metrics(it)) }
        val consumedAtMs = SystemClock.elapsedRealtime()
        metricsRecorder.recordHandCallbackToConsume(consumedAtMs - frame.callbackCompletedAtMs)
            ?.let { mutableEvents.tryEmit(LivePerformanceEvent.Metrics(it)) }
        if (inputs.get().mode != StudioMode.TAP) return

        val triggers = tapHitProcessor.process(frame, consumedAtMs, tapSnapshot.get())
        if (triggers.isEmpty()) return
        val resolvedAtMs = SystemClock.elapsedRealtime()
        for (trigger in triggers) audioTrigger(trigger.pad, trigger.velocity)
        metricsRecorder.recordHitToAudioSubmit(SystemClock.elapsedRealtime() - resolvedAtMs)
            ?.let { mutableEvents.tryEmit(LivePerformanceEvent.Metrics(it)) }
        mutableEvents.tryEmit(LivePerformanceEvent.Hit(triggers.map { it.zoneId }))
    }

    private fun analyzeSegmentation(bitmap: android.graphics.Bitmap, proxy: androidx.camera.core.ImageProxy) {
        if (!active.get()) return
        val timestampMs = handTracker.timestampMs(proxy.imageInfo.timestamp)
        val targetViewport = viewport.get() ?: return
        val mapper = PreviewCoordinateMapper.forFillCenter(
            bitmap.width,
            bitmap.height,
            targetViewport.width,
            targetViewport.height
        ) ?: return

        pickerRequest.getAndSet(null)?.let { request ->
            val sourcePoint = mapper.unmap(request.x, request.y)
            HsvColorPicker.sample(bitmap, sourcePoint.x, sourcePoint.y)?.let { range ->
                mutableEvents.tryEmit(LivePerformanceEvent.ColorPicked(request.presetIndex, range))
            }
        }

        if (segmentationCadence.shouldSegment()) {
            val activeSegmenter = segmenter.get() ?: return
            val segmentStartMs = SystemClock.elapsedRealtime()
            val trackedZones = zoneTracker.update(
                activeSegmenter.segment(bitmap, inputs.get().detectionConfig),
                timestampMs
            )
            segmentationCadence.record(trackedZones)
            metricsRecorder.recordSegmentation(SystemClock.elapsedRealtime() - segmentStartMs)
                ?.let { mutableEvents.tryEmit(LivePerformanceEvent.Metrics(it)) }
            cachedZones.set(trackedZones)
            cachedZonesTimestampMs.set(timestampMs)
            tapSnapshot.set(HitSnapshot(trackedZones, timestampMs))
            mutableEvents.tryEmit(LivePerformanceEvent.Zones(trackedZones.map(mapper::map)))
        }

        val cacheTimestampMs = cachedZonesTimestampMs.get()
        if (cachedZones.get().isNotEmpty() && cacheTimestampMs != Long.MIN_VALUE) {
            metricsRecorder.recordZoneCacheAge(timestampMs - cacheTimestampMs)
                ?.let { mutableEvents.tryEmit(LivePerformanceEvent.Metrics(it)) }
        }

        val rawFrames = handTracker.drainRawHandFrames()
        if (inputs.get().mode != StudioMode.STEP) return
        for (rawFrame in rawFrames) {
            val latestHands = rawFrame.hands.map { hand ->
                PreviewCoordinateMapper.forFillCenter(
                    hand.imageWidth,
                    hand.imageHeight,
                    targetViewport.width,
                    targetViewport.height
                )?.map(hand) ?: hand
            }
            val candidates = stepHitDetector.update(latestHands, rawFrame.timestampMs)
            val projection = projectionProvider() ?: continue
            for (candidate in candidates) {
                val cell = projection.locateCell(
                    com.electrodig.voidmusic.detection.grid.GridScanner.GridPoint(
                        candidate.point.x,
                        candidate.point.y
                    )
                ) ?: continue
                val key = cell.row.toLong() * 100 + cell.step
                val previousToggleMs = lastCellToggleMs[key] ?: 0L
                if (candidate.timestampMs - previousToggleMs < STEP_TOGGLE_COOLDOWN_MS) continue
                stepToggle(cell.row, cell.step)
                lastCellToggleMs[key] = candidate.timestampMs
                mutableEvents.tryEmit(LivePerformanceEvent.StepToggle)
            }
        }
    }

    private companion object {
        const val STEP_TOGGLE_COOLDOWN_MS = 350L
    }
}
