# Camera and Vision Pipeline Specs

> Executable contracts for the latency-sensitive CameraX -> MediaPipe/OpenCV -> hit/UI pipeline.

## Specs

| Spec | Description | Status |
|---|---|---|
| [Pipeline Latency](./pipeline-latency.md) | Frame ownership, rotation, coordinates, cadence, hand output, hit timing, and metrics | Active |
| [AR Step Sequencer](./step-sequencer.md) | Four-point projection, gesture toggles, persistence, and transport timing | Active |

## Pre-Development Checklist

- Read `pipeline-latency.md` before changing camera binding, bitmap ownership, MediaPipe, OpenCV, performance tiers, hand smoothing, hit detection, or HUD metrics.
- Read `step-sequencer.md` before changing calibration, projection geometry, grid rendering, STEP gestures, `SequenceState`, or `Transport`.
- Trace the source timestamp through callback, hit processing, and audio submission; do not substitute wall-clock or callback-only age.
- Verify whether coordinates are sensor, rotated analysis, or `PreviewView` coordinates before reusing a point.
- Search for shared timing constants such as `MAX_HIT_ZONE_AGE_MS` before changing cadence or freshness.

## Quality Check

- Run the JVM suites for every changed pure component and `:app:lintDebug`.
- Run OpenCV/Compose instrumented tests when native segmentation or calibration UI changes.
- Run the signed-device camera, hand, hit, orientation, background-resume, and grid checks for Release-sensitive changes.
- Confirm every accepted `ImageProxy`, Bitmap, MPImage, native Mat, camera binding, listener, and executor has one explicit owner and symmetric cleanup.
- Confirm the preview stays responsive when a downstream consumer fails or becomes slow.
