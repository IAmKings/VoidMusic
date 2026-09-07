# Directory Structure

> Actual package ownership and dependency boundaries for the single-module Void Music Android application.

## Source Layout

```text
app/src/main/
├── java/com/electrodig/voidmusic/
│   ├── audio/          # Playback domain, WAV parser, prepared kits, Oboe/SoundPool adapter, Transport
│   ├── camera/         # CameraX binding, frame ownership, coordinate mapping, vision metrics
│   ├── detection/
│   │   ├── color/      # HSV models, sampling, segmentation, stable zone tracking
│   │   ├── grid/       # Pure projection geometry and immutable sequence state
│   │   ├── hand/       # MediaPipe wrapper, hand model, smoothing, raw-frame queue
│   │   └── hit/        # Gesture candidates, freshness processing, pad lookup, arbitration
│   ├── performance/    # Cross-vision runtime pipeline, adaptive cadence, thermal/power policy
│   ├── persistence/    # DataStore, Room, managed audio files, importer, library coordinator
│   ├── session/        # Activity-scoped ViewModel and UI-facing immutable state
│   └── ui/
│       ├── components/ # Stateless or state-hoisted Compose pieces
│       ├── nav/        # Linear destinations and shared ViewModel navigation graph
│       ├── screens/    # Screen composition and lifecycle wiring
│       └── theme/      # Material tokens
├── cpp/                # JNI bridge and Oboe callback engine
├── assets/             # Uncompressed MediaPipe .task model
└── res/                # Built-in WAV resources and Android resources

app/src/test/           # JVM tests for pure contracts and orchestration seams
app/src/androidTest/    # Android/Room/OpenCV/SoundPool/Compose integration tests
app/schemas/            # Committed Room schema exports
scripts/                # Release validation and artifact preparation
.github/workflows/      # CI verification and tag publication
```

## Module Ownership

- `ui` renders state and emits intent. It does not open files, access a DAO, decode WAV, run OpenCV, or call JNI.
- `session` coordinates user intent, persistence, and UI messages. It exposes `StateFlow` snapshots, not database entities or files.
- `performance.LivePerformancePipeline` owns the live camera/vision/hit boundary. CameraX/MediaPipe/OpenCV thread state must not leak into Compose.
- `persistence.RoomKitLibrary` is the business coordinator for built-in/custom kits. DAO and managed-file operations remain behind it.
- `audio.PreparedKit` is the only value allowed to cross into realtime playback. It contains complete decoded samples and no UI/DAO types.
- `detection.grid` and pure mapping/state classes remain Android-free so JVM tests can exercise geometry and timing.

## Dependency Direction

```text
ui -> session -> audio interfaces / persistence coordinators
ui MainScreen -> performance pipeline -> camera + detection -> audio trigger callback
persistence -> audio domain models and WAV parser
audio -> detection.color.DrumPad only
camera/detection -> no UI dependency
native C++ <- DrumEngine JNI declarations only
```

Do not create reverse dependencies from `audio`, `camera`, `detection`, or `persistence` into Compose screens.

## Naming and Placement

- Domain models use singular PascalCase; Room models add `Entity`; UI snapshots add `UiState` or `Message`.
- Stateful resource owners use nouns such as `DrumEngine`, `HandTracker`, `CameraModule`; pure decision helpers use explicit names such as `stepPlaybackAction`.
- Put Android-free algorithms beside their domain, not in a generic `util` package.
- Add a new package only when it owns a distinct lifecycle or dependency boundary. Do not create wrapper layers that only forward calls.

## Good vs Bad

```kotlin
// Good: UI asks the activity-scoped owner to perform the operation.
viewModel.replaceKitPad(kitId, pad, uri)

// Bad: a Composable reaches through every boundary.
dao.updateMappingAsset(kitId, pad.name, File(uri.path!!).readBytes().hashCode().toString())
```

The package layout is a boundary map, not cosmetic organization. Move tests and update the relevant spec when ownership changes.
