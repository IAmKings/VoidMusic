# Camera & Vision Pipeline Spec

> Per-frame, latency-sensitive vision pipeline for hand tracking + object
> detection on Android. Covers CameraX → Bitmap → MediaPipe/OpenCV → overlay
> data flow and the latency contracts each stage must honour.

---

## Pipeline Overview

```
CameraX ImageAnalysis (sensor space, rotationDegrees=D)
  → FrameRouter.analyze [single bg thread, STRATEGY_KEEP_ONLY_LATEST]
    → image.toBitmap()                    [RGBA, sensor space, NO rotation]
    → rotateBitmapForDisplay(raw, D)      [→ screen space; 0° short-circuits]
    → consumer[0] HandTracker.detectAsync [async, non-blocking]
    → consumer[1] ColorSegmenter.segment  [SYNC, blocking OpenCV]
  → MediaPipe async callback
    → OneEuroHandStabilizer.smooth        [low-pass filter: latency vs smoothness]
    → _hands StateFlow
  → Compose collectAsState → HandOverlay Canvas redraw
```

## Latency Budget (per frame @ 30fps target)

| Stage | Budget | Notes |
|-------|--------|-------|
| toBitmap + rotate | ≤ 8ms | 0° must be zero-alloc; non-0° may allocate |
| HandTracker.detectAsync | ≤ 2ms (hand-off) | Inference is async |
| ColorSegmenter.segment | ≤ 25ms | Biggest sync cost; may run sub-rate |
| OneEuroFilter.smooth | ≤ 1ms | Pure float math |
| Compose recompose + draw | ≤ 8ms | Keep publish ≤ 30fps |
| **Total added latency** | **≤ 44ms** | Excludes camera exposure + inference queue |

## Key Contracts

### Bitmap Rotation (FrameRouter)

- `ImageProxy.toBitmap()` returns **sensor-space** bitmap (verified via
  CameraX 1.4.1 bytecode: `createBitmapFromRgbaImage` does NOT rotate).
- `imageInfo.rotationDegrees` is the CW degrees the consumer must rotate to
  match the target display.
- Rotation MUST be applied exactly once, at the FrameRouter fan-out point, so
  all downstream consumers (hand / zone / hit / overlay) share one screen-space
  coordinate system.
- `rotateBitmapForDisplay(bitmap, 0)` MUST return the same reference (zero
  allocation) — the common "already aligned" case.
- `normalisedRotationDegrees(degrees)` is the pure-function short-circuit gate;
  it handles negative and >360 inputs.

### OneEuroHandStabilizer Parameters

The stabilizer is a **low-pass filter**: it trades latency for smoothness.

| Parameter | Effect of ↑ | Default | Tuning range |
|-----------|------------|---------|--------------|
| `minCutoff` | less smoothing at rest, less lag | 1.5 | 1.5–4.0 |
| `beta` | faster speed response, less lag when moving | 0.05 | 0.05–0.1 |

**Convention**: `minCutoff=1.5, beta=0.05` is too conservative for real-time
skeleton overlay — causes visible "骨架不贴手" lag. For responsive overlay use
`minCutoff=3.0, beta=0.07`. Hit-detection jitter is handled separately by
`HitDetector.cooldownMs`, so stabilizer can afford to be more responsive.

### Camera Analysis Resolution vs Model Input

| Perf level | Camera res | MediaPipe input | OpenCV downsample |
|-----------|-----------|-----------------|-------------------|
| HIGH | 1920×1080 | 192×192 / 224×224 | 1.0 (full) |
| MEDIUM | 1280×720 | 192×192 / 224×224 | 0.5 |
| LOW | 640×480 | 192×192 / 224×224 | 0.25 |

**Gotcha**: Camera resolution far exceeds model input (192/224). Full-res
bitmap is copied, rotated, and wrapped in `BitmapImageBuilder` only for
MediaPipe to downscale internally. This is wasted work. Prefer lower camera
analysis resolution — preview can stay high quality via a separate Preview
use case at different resolution.

### Consumer Ordering (FrameRouter)

Consumers run **serially** in `bitmapConsumers` list order. A blocking
consumer delays `finally { imageProxyConsumer(image) }` (proxy close), which
under `STRATEGY_KEEP_ONLY_LATEST` can stall the next frame delivery.

- **HandTracker.detectAsync** is async — returns immediately, safe as consumer[0].
- **ColorSegmenter.segment** is synchronous OpenCV — blocks the analysis
  thread. If it exceeds the frame budget, consider:
  1. Running it on a separate executor/coroutine (fire-and-forget the zones).
  2. Running it sub-rate (every Nth frame; zones are spatially slow-moving).

## Common Mistakes

### Mistake: Forgetting to rotate the bitmap

**Symptom**: Hand skeleton appears 90° rotated relative to the real hand;
hit detection never fires (downward tap ≠ positive Y in sensor space).

**Cause**: `ImageProxy.toBitmap()` does NOT apply `rotationDegrees`. Each
downstream consumer that assumes screen-space coordinates will be wrong.

**Fix**: Rotate once in `FrameRouter.analyze` via
`rotateBitmapForDisplay(raw, image.imageInfo.rotationDegrees)`.

**Prevention**: The FrameRouter is the single rotation point — never assume
sensor-space coordinates downstream.

### Mistake: Camera bound once, tracker rebuilt later

**Symptom**: Hand tracking only appears after navigating to Settings and back.

**Cause**: `camera` and `handTracker` are `remember(settings.performanceLevel)`.
`settings` starts as `DEFAULT` then emits the persisted value later. If the
persisted level ≠ default, both are rebuilt to instance #2, but the camera was
already bound to instance #1 (whose tracker's StateFlow the UI doesn't
collect).

**Fix**: Bind the camera in a `LaunchedEffect(camera, lifecycleOwner,
previewView)` that re-runs when `camera` changes, plus a
`DisposableEffect(camera)` that `stop()`s the old instance.

**Prevention**: Never bind camera in a one-shot AndroidView factory callback
when the camera instance is keyed to mutable state.

### Mistake: Over-smoothing the skeleton overlay

**Symptom**: Skeleton "doesn't stick to the hand" — visible lag when moving.

**Cause**: OneEuro `minCutoff=1.5, beta=0.05` over-smooths for real-time
visual tracking.

**Fix**: Raise to `minCutoff=3.0, beta=0.07` for overlay responsiveness; rely
on `HitDetector.cooldownMs` for tap jitter, not the stabilizer.

## Design Decisions

### Decision: Rotate in FrameRouter, not per-consumer

**Context**: Bitmap from `toBitmap()` is sensor-space; consumers need
screen-space normalized coords.

**Options**:
1. Rotate in each consumer (hand tracker, segmenter, overlay) independently.
2. Rotate once in FrameRouter before fan-out.

**Decision**: Option 2. Single rotation point = single source of truth, no
risk of one consumer forgetting to rotate, no duplicated rotation cost.

**Tradeoff**: Every consumer receives a (possibly newly allocated) rotated
bitmap. The 0° short-circuit avoids allocation in the aligned case.

### Decision: Responsive stabilizer params over max smoothness

**Context**: M3 introduced OneEuro to reduce tap false-positives from jitter.

**Options**:
1. Keep conservative params (1.5/0.05) — smooth but laggy.
2. Tune responsive (3.0/0.07) — less lag, slightly more jitter.
3. Make it a user setting.

**Decision**: Option 2 as the new default; Option 3 as future enhancement.
Hit detection already has `cooldownMs=120` debounce, so stabilizer jitter
tolerance is acceptable.

## Validation

- `./gradlew :app:testDebugUnitTest` — includes `FrameRouterTest`
  (`normalisedRotationDegrees` boundary cases) and `OneEuroHandStabilizerTest`.
- On-device: skeleton aligns with real hand in both portrait and landscape;
  downward tap fires drum sound; cold start shows skeleton immediately.
