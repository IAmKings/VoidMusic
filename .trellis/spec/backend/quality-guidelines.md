# Quality Guidelines

> Required engineering and verification standards for Void Music.

## Core Rules

- Prefer immutable snapshots across thread and layer boundaries. Mutable CameraX, MediaPipe, OpenCV, Room, and audio resources each have one explicit owner.
- Keep algorithms Android-free when possible and inject clocks, sleepers, backend starters, input streams, or file roots to make failure paths deterministic.
- Clamp user-controlled numeric settings at the ViewModel boundary and validate persisted/external data again at its owning domain boundary.
- Use stable IDs for kits, assets, zones, and messages. Never use list positions, absolute paths, URI strings, or transient contour IDs as durable identity.
- Every native or lifecycle resource has symmetric, idempotent cleanup.
- A fallback must preserve observable truth: report which backend/tier is active and never label a partial start as success.

## Forbidden Patterns

- Compose calling DAO, file, WAV decoder, OpenCV, or JNI APIs directly.
- `GlobalScope`, unbounded queues, blocking file/database work on Main, or retry loops without a terminal state.
- `runBlocking` in production code.
- Catching `CancellationException` as a generic failure.
- Mutable collections published through `StateFlow` without defensive copies.
- Room destructive migration or `fallbackToDestructiveMigration` for user-authored data.
- Persisting PCM/BLOBs, absolute paths, provider URIs, runtime thermal downgrades, or in-progress picker state.
- Reusing smoothed hand landmarks for hit detection.
- Closing a borrowed MediaPipe input wrapper that would recycle the shared FrameRouter Bitmap.
- Release packaging without dedicated signing, shrinker validation, or tag/version equality.

## Required Test Placement

| Change | Required test layer |
|---|---|
| Pure mapping, parser, state, timing, policy | JVM unit test under `app/src/test` |
| Room schema/transaction/migration | Instrumented test plus committed `app/schemas` JSON |
| Real OpenCV Mat/native binding | Instrumented synthetic-image test |
| Compose semantics or calibration flow | Instrumented Compose test |
| SoundPool load callbacks | Instrumented Android test |
| JNI/Oboe, R8/MediaPipe, lifecycle, acoustic latency | Signed physical-device acceptance |
| Workflow/signing script | Shell syntax, failure-path, signed artifact, and metadata checks |

Bug fixes require a regression test that fails for the original cause. Avoid tautological tests that reproduce the implementation inside the assertion.

## Standard Gates

Application changes:

```bash
./gradlew -PenableNativeBuild=true +  :app:testDebugUnitTest +  :app:lintDebug +  :app:assembleDebug
```

Release-affecting changes additionally require:

```bash
./gradlew -PenableNativeBuild=true :app:assembleRelease
bash scripts/validate_release_shrinker.sh
bash scripts/prepare_release.sh app/build/outputs/apk/release/app-release.apk release-dist v<VERSION_NAME>
```

Use a dedicated Debug application ID and signature for routine device tests. Never uninstall or overwrite the signed app merely to work around a signature mismatch.

## Review Checklist

- Does the change follow the package ownership in `directory-structure.md`?
- Are request/result types concrete and exhaustive at every boundary?
- Are timestamps in one monotonic clock domain and are stale events rejected before mutating state?
- Are collection snapshots deep-copied where nested mutation is possible?
- Does every early return/exception release the resources owned by that scope?
- Are fallback, rollback, compensation, and background recovery tested?
- Are constants synchronized with settings defaults, migrations, UI ranges, tests, README, and code-spec?
- Does Release still compile native code and preserve reflective/JNI contracts under R8?

## Example

```kotlin
// Good: deterministic seam and immutable result.
internal fun stepPlaybackAction(isPlaying: Boolean, isCalibrated: Boolean): StepPlaybackAction

// Bad: UI reaches into mutable runtime owners and guesses state.
if (gridScanner.projection() == null) transport.play()
```
