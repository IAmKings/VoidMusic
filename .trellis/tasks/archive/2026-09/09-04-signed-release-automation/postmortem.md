# Bug Analysis: Minified Release broke MediaPipe hand tracking

## 1. Root Cause Category

- **Category**: D - Test Coverage Gap, with a cross-layer contract component.
- **Specific Cause**: the Release build enabled R8, while validation stopped at compilation, signing, and APK metadata. MediaPipe 0.10.35 transitively depends on Flogger stack inspection and protobuf-javalite 4.26.1 field-name reflection. Neither runtime contract was represented in the app shrinker rules, so the signed APK built successfully but failed only on a physical device.

## 2. Why Fixes Failed

1. Existing `-keep class com.google.mediapipe.** { *; }`: protected MediaPipe classes but not its transitive Flogger and Protobuf runtime contracts.
2. Keeping only `FluentLogger`: restored the class and factory method name, but R8 still inlined the caller-finder chain, so the required stack shape remained broken.
3. Keeping all Flogger classes: fixed the fatal `Graph` initializer crash and exposed the next independent defect, renamed `GeneratedMessageLite` backing fields. The app stayed alive but hand tracking failed and retried continuously.
4. Applying Flogger stack preservation plus the protobuf-javalite 4.26.1 official field rule: both MediaPipe initialization paths passed on PJZ110.

## 3. Prevention Mechanisms

| Priority | Mechanism | Specific Action | Status |
| --- | --- | --- | --- |
| P0 | Runtime contract | Preserve `com.google.common.flogger.**` and `GeneratedMessageLite` fields in Release | DONE |
| P0 | CI regression gate | Run `scripts/validate_release_shrinker.sh` after every signed Release build | DONE |
| P0 | Device smoke test | Cold-start the minified signed APK, initialize hand tracking, trigger audio, and background/resume before tagging | DONE, pending user audio confirmation |
| P1 | Dependency review | Repeat the signed device smoke test whenever MediaPipe, protobuf-javalite, AGP, or R8 changes | DOCUMENTED |

## 4. Systematic Expansion

- **Similar Issues**: OpenCV JNI, Room/serialization-generated code, and future SDKs using reflection or call-stack discovery can also pass build-time checks while failing only after minification.
- **Design Improvement**: keep third-party runtime assumptions as explicit, narrow shrinker contracts and validate generated R8 outputs rather than disabling minification globally.
- **Process Improvement**: a signed Release is not a candidate until the exact minified APK passes a physical-device startup, core-feature, and background-resume smoke test.

## 5. Knowledge Capture

- [x] Updated `.trellis/spec/backend/release-signing.md` with shrinker and device-smoke contracts.
- [x] Added an executable R8 mapping/seeds regression check to the workflow.
- [x] Recorded the failed attempts and final evidence in this task.
- [x] Confirmed no project template mirror exists under `src/templates/markdown/spec/`; no template sync is applicable.
