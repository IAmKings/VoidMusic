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

### Analysis Frame Caps

`analysisFrameCap` is a long-term target, not a minimum elapsed time since the
previous accepted source frame. Use a stateful deadline gate: accept the first
frame, retain a `nextDueTimestampNs`, and advance that deadline by whole
intervals after each accepted frame. Resetting the deadline to the accepted
frame causes 30 FPS input with a 20 FPS cap to degrade to 15 FPS.

### OpenCV Bitmap Conversion

`Utils.bitmapToMat()` writes the Android bitmap to a reusable 4-channel Mat.
On the bundled OpenCV 4.13 binding, use `COLOR_RGB2HSV` directly on that
3-or-4-channel input (alpha is ignored); do not copy through `IntArray` /
`ByteArray` or convert RGBA→BGR first. Reuse RGBA, HSV, and threshold Mats and
release every retained Mat from `ColorSegmenter.close()`. `ColorSegmenter` is
created during Compose composition before the `LaunchedEffect` that loads
OpenCV, so all native `Mat` allocation must be lazy inside `segment()`.

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
Hit detection already has a 60 ms lift-gated rearm interval, so stabilizer jitter
tolerance is acceptable.

## Scenario: Musical Tap Hit Timing Contract

### 1. Scope / Trigger

- Applies when changing `HitDetector`, `TapHitProcessor`, performance frame caps,
  colour-segmentation cadence, or persisted hit-response settings.
- Prevents a valid lift or static drum zone from falling into a timing gap
  between asynchronous hand results and sub-rate colour segmentation.

### 2. Signatures

- `HitDetector.update(hands: List<Hand>, timestampMs: Long): List<HitCandidate>`
- `TapHitProcessor.process(frame: TimestampedHands, consumedAtMs: Long, snapshot: HitSnapshot): List<TriggerEvent>`
- `Settings.withCurrentHitTuning(): Settings`

### 3. Contracts

| Field / state | Contract |
|---|---|
| `HitDetector.cooldownMs` | Default 60 ms; it is a minimum rearm delay, not a fixed post-hit mute window. |
| `Tracker.liftObserved` | Once a lift/deceleration is observed after a hit, retain it until rearm or tracker expiry. |
| Same-zone retrigger | Default 70 ms in `HitArbiter`; different zones do not share this cooldown. |
| Hand-result age | `consumedAtMs - frame.timestampMs` uses a 140 ms target, recent baseline + 40 ms jitter margin, and a 260 ms hard ceiling. Callback completion time is metrics-only. |
| Zone age | `consumedAtMs - snapshot.zoneTimestampMs` must be within `0..260` ms. |
| Medium frame cap | 24 FPS target; colour segmentation remains every third accepted frame. |
| Persisted tuning | Version 1 defaults are threshold `0.50` and rearm `60 ms`; legacy default pairs migrate once, custom pairs are preserved. |

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Hand or zone age is negative | Drop the frame; monotonic-clock domains do not match. |
| Hand source age suddenly exceeds the adaptive budget | Drop before mutating gesture state; let a sustained baseline increase adapt within the 260 ms hard ceiling. |
| Hand source age exceeds 260 ms | Always drop before mutating gesture state. |
| Zone source age exceeds 260 ms | Drop before mutating gesture state. |
| Lift occurs before 60 ms has elapsed | Record `liftObserved`; do not arm yet. |
| Next downstroke arrives after the delay | Arm and allow that same sample to produce a candidate. |
| Motion continues downward without a lift | Produce no duplicate candidate. |

### 5. Good / Base / Bad Cases

- Good: hit at 40 ms, lift at 70 ms, next downstroke at 134 ms -> two hits
  (about a sixteenth note at 160 BPM).
- Base: one continuous downward trajectory -> one hit only.
- Bad: forget a lift because it happened before the delay, or compare
  `consumedAtMs` only with `callbackCompletedAtMs`; both create late or missing notes.

### 6. Tests Required

- `HitDetectorTest`: early lift retained, 94 ms retrigger, continuous-down suppression,
  lateral same-hand transition, and hand-order changes.
- `TapHitProcessorTest`: transient-stale rejection, sustained 186–208 ms recovery,
  260 ms hand/zone hard boundaries, 16 FPS three-frame cache interval, and alternating colours.
- `SettingsSerializationTest`: legacy defaults migrate and current/custom tuning is preserved.
- On-device: same-colour roll, four-colour alternation, two-hand alternation, and
  HUD callback-to-consume P95 at or below 15 ms.

### 7. Wrong vs Correct

#### Wrong

```kotlin
if (rearmDelayPassed && liftedThisFrame) tracker.armed = true
val age = consumedAtMs - frame.callbackCompletedAtMs
```

#### Correct

```kotlin
if (liftedThisFrame) tracker.liftObserved = true
if (rearmDelayPassed && tracker.liftObserved) tracker.armed = true
val age = consumedAtMs - frame.timestampMs
```

## Validation

- `./gradlew :app:testDebugUnitTest` — includes `FrameRouterTest`
  (`normalisedRotationDegrees` boundary cases) and `OneEuroHandStabilizerTest`.
- On-device: skeleton aligns with real hand in both portrait and landscape;
  downward tap fires drum sound; cold start shows skeleton immediately.
