package com.electrodig.voidmusic.detection.hand

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps the MediaPipe [HandLandmarker] in LIVE_STREAM mode and exposes the
 * latest detected hands as a [StateFlow] (PRD F5.1 / F5.2).
 *
 * Frames arrive from CameraX's ImageAnalysis use case; each YUV [ImageProxy]
 * is converted to a Bitmap-backed [MPImage] and fed to [HandLandmarker.detectAsync].
 * Results are returned asynchronously on the result listener, normalised into
 * our domain [Hand] type, optionally smoothed, then published.
 *
 * The [resultHandler] callback (set by the camera layer) is invoked for every
 * successful inference with its source/callback timestamps for observability.
 */
class HandTracker(
    private val context: Context,
    private val maxHands: Int = 2,
    private val minDetectionConfidence: Float = 0.5f,
    private val minTrackingConfidence: Float = 0.5f,
    private val delegate: Delegate = Delegate.GPU,
    private val stabilizer: HandStabilizer = IdentityHandStabilizer,
    private val resultHandler: ((TimestampedHands) -> Unit)? = null
) {

    private var landmarker: HandLandmarker? = null

    /** Latest hands AFTER stabilizer smoothing — observed by the overlay UI
     *  for steady skeleton rendering. */
    private val _hands = MutableStateFlow<List<Hand>>(emptyList())
    val hands: StateFlow<List<Hand>> = _hands.asStateFlow()

    /**
     * Raw MediaPipe output for the hit path. A small bounded queue preserves
     * timestamp/landmark pairing until the camera pipeline consumes it, while
     * preventing delayed inference results from growing into latency backlog.
     */
    private val pendingRawFrames = TimestampedHandsQueue(MAX_PENDING_RAW_FRAMES)

    /**
     * Lazily builds the [HandLandmarker]. Call after the camera is ready or on
     * first frame. Uses the GPU delegate where available for lower latency.
     */
    @Synchronized
    fun setup() {
        if (landmarker != null) return
        try {
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    com.google.mediapipe.tasks.core.BaseOptions.builder()
                        .setModelAssetPath(MODEL_PATH)
                        .setDelegate(delegate)
                        .build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(maxHands)
                .setMinHandDetectionConfidence(minDetectionConfidence)
                .setMinHandPresenceConfidence(minTrackingConfidence)
                .setMinTrackingConfidence(minTrackingConfidence)
                .setResultListener(::onResult)
                .setErrorListener(::onError)
                .build()

            landmarker = HandLandmarker.createFromOptions(context, options)
            Log.i(TAG, "HandLandmarker ready (${delegate.name} delegate, numHands=$maxHands)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init HandLandmarker with ${delegate.name}, retrying on CPU", e)
            initCpu()
        }
    }

    private fun initCpu() {
        runCatching {
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    com.google.mediapipe.tasks.core.BaseOptions.builder()
                        .setModelAssetPath(MODEL_PATH)
                        .setDelegate(Delegate.CPU)
                        .build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumHands(maxHands)
                .setMinHandDetectionConfidence(minDetectionConfidence)
                .setMinHandPresenceConfidence(minTrackingConfidence)
                .setMinTrackingConfidence(minTrackingConfidence)
                .setResultListener(::onResult)
                .setErrorListener(::onError)
                .build()
            landmarker = HandLandmarker.createFromOptions(context, options)
            Log.i(TAG, "HandLandmarker ready (CPU fallback)")
        }.onFailure { Log.e(TAG, "CPU init also failed", it) }
    }

    /**
     * Feed a frame to the landmarker. The Bitmap is produced upstream by the
     * camera layer (FrameRouter); [timestampMs] must be monotonic. The caller
     * owns the source [ImageProxy] lifecycle.
     */
    @Synchronized
    fun detect(bitmap: android.graphics.Bitmap, timestampMs: Long) {
        val detector = landmarker ?: run { setup(); landmarker } ?: return

        val mpImage = runCatching {
            BitmapImageBuilder(bitmap).build()
        }.getOrElse {
            Log.w(TAG, "Frame conversion failed", it); return
        }

        runCatching {
            detector.detectAsync(mpImage, timestampMs)
        }.onFailure {
            Log.w(TAG, "detectAsync failed", it)
        }
    }

    /** Convert a monotonic-nanos timestamp (from [ImageProxy.imageInfo]) to ms. */
    fun timestampMs(nanos: Long): Long = nanos / NANOS_PER_MS

    /** Drains every unprocessed raw result in source timestamp order. */
    fun drainRawHandFrames(): List<TimestampedHands> = pendingRawFrames.drain()

    /** Release native resources (PRD §4.4: release on background/cleanup). */
    @Synchronized
    fun close() {
        landmarker?.close()
        landmarker = null
        _hands.value = emptyList()
        pendingRawFrames.clear()
    }

    private fun onResult(result: HandLandmarkerResult, input: MPImage) {
        val callbackCompletedAtMs = SystemClock.elapsedRealtime()
        val width = input.width
        val height = input.height
        val landmarkSets = result.landmarks()
        if (landmarkSets.isEmpty()) {
            publish(emptyList(), width, height, result.timestampMs(), callbackCompletedAtMs)
            return
        }

        val handednessSets = result.handednesses()
        val hands = ArrayList<Hand>(landmarkSets.size)
        for (i in landmarkSets.indices) {
            val rawLandmarks = landmarkSets[i]
            val handedness = handednessSets
                .getOrNull(i)
                ?.firstOrNull()?.categoryName()
                ?: "Unknown"

            val landmarks = rawLandmarks.map { lm ->
                NormalizedLandmark(lm.x(), lm.y(), lm.z())
            }
            hands += Hand(
                landmarks = landmarks,
                handedness = handedness,
                imageWidth = width,
                imageHeight = height
            )
        }
        publish(hands, width, height, result.timestampMs(), callbackCompletedAtMs)
    }

    private fun publish(
        hands: List<Hand>,
        width: Int,
        height: Int,
        timestampMs: Long,
        callbackCompletedAtMs: Long
    ) {
        // Raw (un-smoothed) hands feed the hit-detection path so tap peaks are
        // preserved; smoothed hands feed the overlay so the skeleton is steady.
        val frame = TimestampedHands(timestampMs, hands, callbackCompletedAtMs)
        pendingRawFrames.offer(frame)
        val smoothed = if (hands.isEmpty()) hands else stabilizer.smooth(hands)
        _hands.value = smoothed
        resultHandler?.invoke(frame)
    }

    private fun onError(runtimeException: RuntimeException) {
        Log.e(TAG, "HandLandmarker error", runtimeException)
    }

    companion object {
        private const val TAG = "HandTracker"
        private const val MODEL_PATH = "hand_landmarker.task"
        private const val NANOS_PER_MS = 1_000_000L
        private const val MAX_PENDING_RAW_FRAMES = 4
    }
}
