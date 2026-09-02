# Implementation Plan — stabilizer timestamp + FrameRouter resilience fix

## Execution Checklist

1. **Fix the timestamp source** in `OneEuroHandStabilizer.smooth`
   (`app/src/main/java/com/electrodig/objectdrumstudio/detection/hand/OneEuroHandStabilizer.kt`):
   - Replace the fake `(lastTsMs + 1).also { lastTsMs = it }` derivation with
     `android.os.SystemClock.elapsedRealtime()`.
   - Keep `lastTsMs` to seed the first sample and guard the first-call branch
     (the filter already handles `lastTsMs < 0` internally, but the stabilizer
     should pass the real clock on every call, including the first).
   - Drop now-dead `(lastTsMs + 1)` logic; `lastTsMs` can reuse the same field
     or be removed if no longer referenced — prefer keeping it only if still
     used. (Verify: it is only used to build `ts`, so the field may be dropped
     in favour of reading the clock directly.)

2. **Verify import**: add `import android.os.SystemClock` if not present.

3. **Harden `FrameRouter.analyze`** so a failing consumer cannot stall the
   camera (`app/src/main/java/com/electrodig/objectdrumstudio/camera/FrameRouter.kt`):
   - Wrap `bitmapConsumers.forEach` in `try { ... } finally { imageProxyConsumer(image) }`
     so the proxy is always closed even if a consumer throws.
   - Isolate each consumer with `runCatching` + log, so one throwing consumer
     (colour/hit path) doesn't skip the rest of the loop.
   - Update the stale doc comment about "last consumer closes the proxy".
   - This unblocks on-device hand recognition (a leaked proxy under
     `STRATEGY_KEEP_ONLY_LATEST` freezes the whole analysis pipeline).

4. **Add a regression test** to
   `app/src/test/java/com/electrodig/objectdrumstudio/detection/hand/OneEuroFilterTest.kt`
   or a new `OneEuroHandStabilizerTest.kt`:
   - Feed `OneEuroHandStabilizer.smooth` a sequence of synthetic hands with
     a fixed landmark moving by a realistic per-frame delta (e.g. 0.01
     normalised units) across real-ish intervals. Assert the smoothed output
     tracks the input (|out - input| bounded) rather than stalling (|out - prev|
     ≈ 0 across many frames), which is exactly the old bug's signature.
   - Use a clock the stabilizer controls: since `smooth` now reads
     `SystemClock.elapsedRealtime()` internally, the test should drive the
     stabilizer fast enough that `elapsedRealtime` advances, OR the test
     verifies the "does not stall" property by feeding many frames in a tight
     loop and asserting the output converges to the input within a tolerance.
     Prefer the latter — it does not depend on wall-clock granularity and
     still catches the bug (old code: output frozen → assertion fails).

## Validation Commands

- Unit tests: `./gradlew :app:testDebugUnitTest --tests "com.electrodig.voidmusic.detection.hand.*"`
- Full build + lint (sanity): `./gradlew :app:assembleDebug`

## Review Gate

- Before `task.py start`: confirm PRD acceptance criteria map to checklist
  items 1 and 3.

## Rollback

- Single-file change; revert `OneEuroHandStabilizer.kt` (and delete the new
  test file if one was added) to restore prior behaviour.

## Rollback Points

- After step 1: build before running tests to catch compile issues.
- After step 3: if the new test is flaky on wall-clock, weaken the assertion
  to the "tracks within tolerance after N frames" form (still encodes the
  regression).
