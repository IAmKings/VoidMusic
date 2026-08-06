# M6 Technical Design

## 1. Audio System (R6.1)

### Problem
`enableNativeBuild = false` in `build.gradle.kts` — Oboe native lib not compiled. Audio is a no-op.

### Decision: Enable Oboe + Add Kotlin Fallback

| Layer | Approach |
|-------|----------|
| Primary | Enable CMake build → `libdrumengine.so` (exclusive low-latency stream, Float mono, I16 fallback) |
| Fallback | Kotlin `AudioTrack` with preloaded WAV buffers when native lib unavailable (covers emulators, non-AAudio devices) |

**Rationale**: Oboe already implemented in `drumengine.cpp`, just needs NDK and build flag. AudioTrack fallback guarantees basic audio on all API 26+ devices.

**NDK requirement**: Install NDK 27.2.12479018 via SDK Manager (already declared in build.gradle.kts).

### Contracts
- `DrumEngine.kt` already handles missing native lib (returns `false`, no crash)
- Fallback path: `DrumEngine` detects `libdrumengine.so` absence → delegates to `AudioTrackDrumEngine`
- Latency budget: Oboe < 40ms, AudioTrack fallback < 80ms

## 2. Performance Tier Wiring (R6.1, R6.2)

### Problem
`PerformanceLevel` (LOW/MEDIUM/HIGH) persisted to DataStore and shown in UI, but has zero runtime effect.

### Decision: Wire PerformanceLevel into Pipeline

| Parameter | HIGH (骁龙8 Gen / 天玑9300) | MEDIUM (骁龙7/6系) | LOW (骁龙4系 / 4GB) |
|-----------|---------------------------|-------------------|---------------------|
| Camera resolution | 1080p (1920x1080) | 720p (1280x720) | 480p (640x480) |
| Analysis frame cap | 30 fps | 20 fps | 15 fps |
| MediaPipe delegate | GPU | GPU (CPU fallback) | CPU |
| Max hands | 2 | 2 | 1 |
| Color segmentation downsample | 1x (full) | 0.5x | 0.25x |

### Flow
```
SettingsRepository (DataStore)
  → SessionViewModel collects PerformanceLevel
    → CameraModule: setTargetResolution(level)
    → FrameRouter: setFrameCap(level)
    → HandTracker: setDelegate(level), setMaxHands(level)
    → ColorSegmenter: setDownsample(level)
```

## 3. APK Size Reduction (R6.4)

### Problem
Debug APK = 156MB. Target release APK < 80MB.

### Breakdown (estimated)

| Component | Current | Target | Method |
|-----------|---------|--------|--------|
| OpenCV native libs | ~40MB | ~8MB | Exclude unused .so modules, keep `core`+`imgproc` only |
| MediaPipe native libs | ~30MB (3 ABIs) | ~20MB (2 ABIs) | Drop x86_64, keep arm64-v8a + armeabi-v7a |
| App code + resources | ~20MB | ~10MB | R8 minification, shrinkResources |
| Assets (model + audio) | ~5MB | ~5MB | Already minimal |
| **Total** | **~156MB (debug)** | **<80MB (release)** | |

### OpenCV Trimming Strategy

**Primary**: ABI-split + module filtering in Gradle:
```kotlin
android {
    packaging {
        jniLibs {
            excludes += listOf(
                "**/libopencv_java*.so",  // keep only needed ABIs
            )
        }
    }
}
```
Keep: `libopencv_core.so`, `libopencv_imgproc.so` (~4MB each per ABI).
Drop: `features2d`, `calib3d`, `objdetect`, `video`, `ml`, `photo`, `dnn`, etc.

**Fallback** (if still >80MB): Pure Kotlin HSV+connected-components replacement for OpenCV. Remove OpenCV dependency entirely. This is a ~2-day effort.

### ABI Strategy
- Release: `arm64-v8a` only (covers 90%+ active devices)
- Debug: `arm64-v8a`, `armeabi-v7a`, `x86_64` (emulator support)
- Config: `ndk.abiFilters` in `build.gradle.kts` with BuildType-based filtering

## 4. Performance Measurement (R6.1)

### FPS Counter
- `FrameMetricsAggregator` on `PreviewView` → render FPS
- `Choreographer.FrameCallback` → UI thread FPS (Compose)
- HUD overlay showing current FPS in debug builds

### Latency Measurement
- Camera frame timestamp (`ImageProxy.imageInfo.timestamp`) → `System.nanoTime()`
- Audio callback timestamp in Oboe C++ → passed back via JNI
- Log delta; expose via debug HUD

### Stability
- `StrictMode` in debug builds for disk/network violations
- Memory tracking via `Debug.getNativeHeapAllocatedSize()` in HUD

## 5. Compatibility Matrix (R6.3)

### Test Devices

| Tier | Device | Chipset | RAM | Android |
|------|--------|---------|-----|---------|
| High | Xiaomi 14 / OnePlus 12 | Snapdragon 8 Gen 3 | 12GB | 14 |
| Mid | Redmi Note 13 Pro | Snapdragon 7s Gen 2 | 8GB | 13 |
| Low | Redmi 12C | Helio G85 | 4GB | 12 |

### Test Protocol
1. Set performance tier to match device
2. Run 5-minute continuous session
3. Record: avg FPS, max latency spike, peak memory, temp delta
4. Pass/fail against acceptance criteria per tier

## 6. Thermal & Power (R6.2)

### Approach
- `PowerManager.isPowerSaveMode` → auto-switch to LOW tier
- `BatteryManager` thermal status (API 29+) → reduce quality when THERMAL_STATUS_* escalates
- Background: `onStop()` → `CameraModule.stop()`, `HandTracker.close()`, `DrumEngine.stop()`

## 7. Trade-offs

| Decision | Pro | Con |
|----------|-----|-----|
| OpenCV trimming vs Kotlin rewrite | Low risk, incremental | May not reach <80MB alone |
| AudioTrack fallback vs Oboe-only | Works on all devices | Higher latency (~80ms vs 40ms) |
| Auto-tier detection vs manual | Better UX | Added complexity; skip if time-constrained |
| x86_64 drop from release | Saves ~10MB | No x86 emulator release testing |
