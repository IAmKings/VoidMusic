# M6 Implementation Plan

## Prerequisites

- [ ] Install NDK 27.2.12479018 via Android Studio SDK Manager

---

## Phase A: P0 — Audio + APK Size (days 1-5)

### A1. Enable Native Audio

- [ ] Set `enableNativeBuild = true` in `app/build.gradle.kts`
- [ ] Verify `libdrumengine.so` builds successfully (`./gradlew assembleDebug`)
- [ ] Run app, verify `DrumEngine.isNativeAvailable() == true`, confirm sound
- [ ] Log `getLatency()` value at startup; verify < 40ms

### A2. AudioTrack Fallback

- [ ] Create `AudioTrackDrumEngine.kt` — Kotlin-only drum engine using `AudioTrack` + preloaded WAV
- [ ] Wire fallback: `DrumEngine` detects missing native lib → delegates to `AudioTrackDrumEngine`
- [ ] Verify fallback works (disable native build temporarily, test sound)

### A3. APK Size — OpenCV Trimming

- [ ] Extract OpenCV AAR, identify per-module .so sizes
- [ ] Configure `packaging.jniLibs.excludes` to keep only `core` + `imgproc`
- [ ] Verify color segmentation still works after trimming
- [ ] Measure APK size: `./gradlew assembleRelease`

### A4. APK Size — Minification & ABI

- [ ] Enable R8 minification: `isMinifyEnabled = true` for release
- [ ] Enable `isShrinkResources = true` for release
- [ ] Set release `ndk.abiFilters = ["arm64-v8a"]`
- [ ] Set debug `ndk.abiFilters = ["arm64-v8a", "armeabi-v7a", "x86_64"]`
- [ ] Measure release APK: target < 80MB
- [ ] If still >80MB: proceed to OpenCV fallback (pure Kotlin)

### Validation Gate A

- [ ] Audio plays on tap; latency log shows < 40ms (Oboe) or < 80ms (fallback)
- [ ] Release APK < 80MB
- [ ] Color segmentation + hit detection still functional

---

## Phase B: P1 — Performance (days 5-8)

### B1. Performance Tier Wiring

- [ ] Create `PerformanceConfig` data class (resolution, frame cap, delegate, hands, downsample)
- [ ] Add `PerformanceConfig.forLevel(PerformanceLevel)` factory
- [ ] Wire `CameraModule.setTargetResolution(config)`
- [ ] Wire `FrameRouter.setFrameCap(config.frameCap)`
- [ ] Wire `HandTracker.setConfig(config)` (delegate, maxHands)
- [ ] Wire `ColorSegmenter.setDownsample(config.downsample)`
- [ ] Verify tier switching takes effect without app restart

### B2. FPS & Latency Measurement

- [ ] Add `FrameMetricsAggregator` to `PreviewView` in `MainScreen`
- [ ] Add debug HUD component showing FPS + latency
- [ ] Add latency measurement: camera timestamp → audio callback delta
- [ ] Add `DebugOverlay` composable (visible only in debug builds or dev settings)

### B3. Thermal & Power Management

- [ ] Register `PowerManager.isPowerSaveMode` observer → auto-switch to LOW
- [ ] Register `BatteryManager` thermal status listener (API 29+)
- [ ] Add `SessionViewModel.onThermalStatusChanged()` → adjust tier
- [ ] Verify `onStop()` releases camera + audio (already in lifecycle; audit)

### Validation Gate B

- [ ] Tier switch changes camera resolution, frame rate, MediaPipe params (visual check)
- [ ] Debug HUD shows FPS; high tier >= 60, mid tier >= 30
- [ ] Power save mode auto-switches to LOW tier

---

## Phase C: P1 — Compatibility & Acceptance (days 8-11)

### C1. End-to-End Latency Acceptance

- [ ] Build debug APK with latency logging
- [ ] Test on at least one high-end device: verify < 80ms
- [ ] Test on at least one mid-range device: verify < 100ms
- [ ] Record results in task notes

### C2. Compatibility Matrix

- [ ] Test on 3 physical devices (high/mid/low) per §12.3
- [ ] For each device: 5-min session, record FPS / latency / memory / temp
- [ ] Document any device-specific issues (OEM audio HAL, camera resolution quirks)

### C3. Cold Start

- [ ] Measure cold start time (launch → viewfinder active)
- [ ] If > 3s: profile with Android Studio CPU Profiler, identify blockers
- [ ] Optimize: lazy MediaPipe init, defer non-critical work

### C4. Memory Peak

- [ ] Run Android Profiler during 20-min session
- [ ] Confirm peak < 400MB
- [ ] If over: check for bitmap leaks, MediaPipe frame accumulation

### C5. Privacy Compliance (R6.6)

- [ ] Audit AndroidManifest.xml: no INTERNET permission
- [ ] Audit code: no file writes of camera frames (grep for `FileOutputStream`, `bitmap.compress`, `save`)
- [ ] Verify privacy policy screen shows "纯本地处理，不上传任何数据"
- [ ] Verify permission rationale text for CAMERA is clear

### Final Acceptance

- [ ] All acceptance criteria from `prd.md` verified on 3 device tiers
- [ ] 20-min continuous run: no crash, no ANR, temp rise ≤ 12°C
- [ ] Release APK < 80MB
- [ ] Privacy checklist all pass

---

## Rollback Points

| After Step | Rollback |
|------------|----------|
| A1 | Revert `enableNativeBuild` to `false` |
| A3 | Revert packaging excludes |
| A4 | Revert minification/ABI if R8 breaks reflection |
| B1 | Revert tier wiring — no behavior change without config |
| C3 | Roll back any deferred-init changes if they cause race conditions |

## Key Files

| File | Risk | Reason |
|------|------|--------|
| `app/build.gradle.kts` | High | NDK, minification, ABI, packaging changes |
| `audio/DrumEngine.kt` | High | Audio path is critical; fallback must be bulletproof |
| `camera/CameraModule.kt` | Medium | Resolution changes may break aspect ratio |
| `detection/hand/HandTracker.kt` | Medium | Delegate switching may fail on some GPUs |
| `detection/color/ColorSegmenter.kt` | Low | Downsample is straightforward |
| `session/SessionViewModel.kt` | Medium | Central wiring point for PerformanceConfig |
