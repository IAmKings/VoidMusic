# AR Step Sequencer

> Executable calibration, projection, gesture-toggle, persistence, and transport contracts for the 4×16 sequencer.

## Scenario: Calibrate and play the projected step grid

### 1. Scope / Trigger

Apply this contract when changing `GridProjection`, `GridScanner`, calibration UI, `StepSequencerOverlay`, STEP gesture handling, `SequenceState`, `Transport`, or saved sequence/calibration fields.

This spans UI, vision coordinates, gesture detection, audio scheduling, and DataStore persistence; all seven sections are mandatory.

### 2. Signatures

```kotlin
fun GridProjection.create(
    corners: List<GridScanner.GridPoint>,
    rows: Int,
    steps: Int
): GridProjectionResult

fun GridScanner.setCalibration(corners: List<GridPoint>): Boolean
fun GridProjection.locateCell(point: GridPoint): GridScanner.Cell?

fun stepPlaybackAction(
    isPlaying: Boolean,
    isCalibrated: Boolean
): StepPlaybackAction

fun Transport.setBpm(value: Int)
fun Transport.toggleStep(row: Int, step: Int)
fun Transport.restore(bpm: Int, grid: List<List<Boolean>>)
fun Transport.play()
fun Transport.stop()
```

`SequenceState` is fixed to four rows and sixteen sixteenth-note steps. `ROW_PADS` is ordered `KICK`, `SNARE`, `CLAP`, `HIHAT`.

### 3. Contracts

- Calibration input is exactly four finite normalized points in TL/TR/BR/BL order and must form the strictly convex orientation accepted by `GridProjection.create`.
- Projection is pure Kotlin and may run before OpenCV is initialized. It allocates no `Mat` and performs no storage I/O.
- Build a unit-square-to-view homography and its inverse only when corners change. Rendering consumes projected centers; hit lookup maps the point back to unit coordinates.
- Invalid calibration returns typed `GridProjectionError`. `GridScanner.setCalibration` preserves the last valid projection when a replacement is invalid.
- Calibration UI edits a draft. Only explicit confirmation of a valid draft publishes to `GridScanner` and `SessionViewModel.setCalibration`; cancel preserves the previous projection.
- Missing calibration is user-visible. Pressing Play when uncalibrated returns `CALIBRATE`, opens calibration, and must not start `Transport`.
- Cell visuals derive footprint from neighbouring projected-center spacing, not full viewport row/column bands.
- STEP gesture input uses raw, timestamped hand results mapped through `PreviewCoordinateMapper`; each candidate is mapped through the immutable projection. One cell has a 350 ms toggle cooldown.
- `SequenceState` updates are deep immutable copies. Out-of-range row/step reads return off and out-of-range toggles are no-ops.
- `Transport` clamps BPM to `40..220`, uses a monotonic clock and absolute musical deadlines, and emits active rows with velocity `0.9`.
- If wake-up is more than one step late, skip expired steps instead of bursting stale notes. `stop()` cancels the ticker and resets the playhead to step 0.
- Persist BPM and a normalized 4×16 grid after edits. Restore data without resuming playback automatically.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Corner count is not four | `GridProjectionError.INVALID_CORNER_COUNT`; `GridScanner.setCalibration` throws for programmer misuse |
| Corner contains NaN/infinity | `NON_FINITE_CORNER` |
| Corner is outside `0..1` | `CORNER_OUT_OF_BOUNDS` |
| Crossed, collapsed, or non-convex corners | `INVALID_QUADRILATERAL` |
| Homography cannot be inverted | `NON_INVERTIBLE` |
| Projected center is non-finite | `INVALID_CELL_CENTER` |
| Point is outside calibrated quadrilateral | `locateCell` returns `null` |
| Play pressed without projection | Open calibration; transport stays stopped |
| Repeated candidate in same cell within 350 ms | Ignore the duplicate toggle |
| Persisted grid has wrong shape | Restore a safe 4×16 copy, filling missing values with `false` |
| Ticker wakes multiple steps late | Advance over expired steps and emit only the current valid step |

### 5. Good / Base / Bad Cases

- Good: confirm four valid perspective corners, render a compact 4×16 grid, toggle cells with raw hand hits, then run the sequence from absolute deadlines.
- Base: fresh or reset state has an empty 4×16 grid at 110 BPM and no playback.
- Bad: construct projection through OpenCV during cold start, render each row across the full viewport, silently accept crossed corners, or start audio while calibration is missing.

### 6. Tests Required

- `GridProjectionTest`: rectangle, perspective, edge mapping, crossed/collapsed/out-of-range/non-finite corners, and preservation of the previous projection.
- `SequenceStateTest`: deep-copy isolation, safe reads/toggles, clear behavior, and row-to-pad order.
- `TransportTest`: BPM bounds, absolute deadlines, late-wake skipping, simultaneous row triggers, idempotent play, stop/reset, restore, and release.
- `StepPlaybackGateTest`: STOP takes priority; calibrated idle returns PLAY; uncalibrated idle returns CALIBRATE.
- `StepSequencerCalibrationTest`: initial prompt, invalid default confirmation, valid confirmation, cancel preservation, and calibrated rendering.
- Signed-device: cold-start calibration restoration, grid visibility, fingertip toggle accuracy, playback, pause, and clear.

### 7. Wrong vs Correct

#### Wrong

```kotlin
if (!gridScanner.isCalibrated()) transport.play()
val rowHeight = viewportHeight / 4f
delay(stepDurationMs) // next deadline starts after work completes
```

#### Correct

```kotlin
when (stepPlaybackAction(transport.isPlaying, gridScanner.isCalibrated())) {
    StepPlaybackAction.STOP -> transport.stop()
    StepPlaybackAction.PLAY -> transport.play()
    StepPlaybackAction.CALIBRATE -> showCalibration = true
}
nextDeadlineNanos += stepDurationNanos
```

The correct form makes calibration a hard playback precondition and prevents tick work from accumulating tempo drift.
