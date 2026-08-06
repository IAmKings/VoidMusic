# Fix hand overlay stabilizer timestamp regression

## Goal

Restore correct hand-tracking overlay behaviour after the M3 swap from
`IdentityHandStabilizer` to `OneEuroHandStabilizer`. The overlay currently
shows hands that drift, freeze, or jitter because the stabilizer feeds the
One-Euro filter a synthetic monotonic counter instead of a real frame
timestamp, mismatching the filter's tuned constants.

## Root Cause

Two compounding defects blocked on-device hand recognition:

### A. Camera pipeline stall (the "无法识别手部" blocker)

`FrameRouter.analyze` ran `bitmapConsumers.forEach { it(bitmap, image) }` and
only closed the `ImageProxy` *after* the loop, in normal flow. Consumer[1]
(the OpenCV colour segmentation + hit pipeline in `MainScreen`) was **not**
exception-isolated. If any OpenCV op there threw (likely on the first frame
before/around initialisation, or any malformed frame), the exception aborted
`forEach`, so `imageProxyConsumer(image)` (proxy close) never ran → the
`ImageProxy` leaked. Under CameraX `STRATEGY_KEEP_ONLY_LATEST`, a single
unclosed proxy halts frame delivery → **the entire analysis pipeline freezes,
hand detection stops, no hands are recognised.**

### B. Stabilizer timestamp (the original "显示不正确")

`OneEuroHandStabilizer.smooth` (app/src/main/.../detection/hand/OneEuroHandStabilizer.kt)
derives the smoothing timestamp as `lastTsMs + 1` (a per-frame integer
counter), then passes it to `OneEuroFilter.filter(value, tsMs)`.

`OneEuroFilter.filter` computes `dt = (tsMs - lastTsMs) / 1000f`, so the
fake counter yields a constant `dt = 0.001`. The filter constants
(`minCutoff = 1.5f`, `beta = 0.05f`) were calibrated against real frame times
(see `OneEuroFilterTest`, which steps `t += 10`, i.e. `dt ≈ 0.01` at ~100 fps;
real camera frames give `dt ≈ 0.033` at 30 fps). With `dt = 0.001`:

- the derivative `dx = (value - prev) / dt` is inflated ~10-30x, corrupting the
  adaptive cutoff (`minCutoff + beta * |dxFiltered|`);
- `smoothingAlpha` produces a near-zero alpha at rest → the filtered position
  barely moves from its previous value, so the fingertip appears to freeze/lag;
- on fast motion the inflated derivative flips the filter into an oversensitive
  regime → jitter/smearing.

Net effect: `HandOverlay` no longer tracks the actual hand → "手部动作捕获显示不正确".

A is the reason hands could not be recognised at all on device, preventing
validation of B.

## Requirements

- The stabilizer must use a real, monotonic clock — not a per-frame counter —
  so `OneEuroFilter` sees the actual inter-frame delta.
- No change to the `HandStabilizer` interface signature (keeps `smooth(hands)`
  single-arg); the timestamp source is internal to `OneEuroHandStabilizer`
  (injectable for tests, default = `SystemClock.elapsedRealtime()`).
- `IdentityHandStabilizer` behaviour is unchanged.
- `HandOverlay`/`HandTracker`/`HitDetector` require no edits.
- `FrameRouter` must guarantee the `ImageProxy` is closed even if a frame
  consumer throws, so a failing downstream (colour/hit) path can never stall
  the camera and freeze hand detection.
- Existing `OneEuroFilterTest` continues to pass unchanged.

## Acceptance Criteria

- [x] `OneEuroHandStabilizer.smooth` derives its timestamp from a real
      monotonic clock (`SystemClock.elapsedRealtime()` by default), not an
      internal counter. Clock is injectable for tests.
- [x] `FrameRouter.analyze` closes the `ImageProxy` in a `finally` block and
      isolates each consumer in try/catch, so a throwing consumer cannot leak
      the proxy or stall the camera pipeline.
- [x] Rebuild + unit tests green: `./gradlew :app:testDebugUnitTest` passes,
      including `OneEuroFilterTest` and the new `OneEuroHandStabilizerTest`.
      A repro test using the old fake-counter clock path FAILS (proving the
      new test catches the regression).
- [x] Sanity build: `./gradlew :app:assembleDebug` succeeds.
- [ ] Device regression (manual): hands are now recognised at all (unblocked
      by the FrameRouter fix).
- [ ] Device regression (manual): in TAP mode the fingertip ring tracks the
      index finger with no visible freeze or lag at rest, and follows quick
      downward strikes without smearing; hit detection still fires.
- [ ] Device regression (manual): in STEP mode the fingertip cursor over the
      grid stays glued to the finger when held still.

## Out of Scope

- Overlay/preview aspect-ratio alignment (`FILL_CENTER` cropping) — separate
  task; assess later.
- Front-camera mirroring — app uses the back camera by default
  (`CameraModule.selectCamera`); not a regression here.
- Retuning `minCutoff`/`beta` — only revisit if real-clock behaviour exposes
  residual jitter; not expected.

## Notes

- Lightweight task: PRD + `implement.md`. No `design.md` (no interface change).
- Two files changed: `OneEuroHandStabilizer.kt` (real clock, injectable) and
  `FrameRouter.kt` (proxy-close resilience + consumer isolation). The
  FrameRouter hardening is what unblocks on-device hand recognition so that the
  stabilizer display fix can actually be validated.
- Remaining manual device checks depend on (1) hands being recognised again
  (FrameRouter fix) and (2) the overlay tracking correctly (stabilizer fix).