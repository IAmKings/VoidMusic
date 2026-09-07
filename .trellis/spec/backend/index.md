# Void Music Application and Backend Specs

> Executable contracts for the Android application, audio, persistence, and release layers.

## Specs

| Spec | Description | Status |
|---|---|---|
| [Directory Structure](./directory-structure.md) | Package ownership and dependency boundaries | Active |
| [Session Persistence](./session-persistence.md) | DataStore, onboarding, runtime performance, mode/grid/calibration state | Active |
| [Audio Import](./audio-import.md) | Strict WAV parsing, normalization, hashing, and staging | Active |
| [Audio Playback](./audio-playback.md) | Prepared kits, backend startup, switching, fallback, and recovery | Active |
| [Audio Library UI](./audio-library-ui.md) | Settings ownership, SAF import, lifecycle, feedback, and accessibility | Active |
| [Database Guidelines](./database-guidelines.md) | Room schema, managed audio files, transactions, and reconciliation | Active |
| [Error Handling](./error-handling.md) | Stable domain errors, exception boundaries, and user feedback | Active |
| [Quality Guidelines](./quality-guidelines.md) | Required test/build gates and forbidden shortcuts | Active |
| [Logging Guidelines](./logging-guidelines.md) | Android/native logging and sensitive-data rules | Active |
| [Release Signing](./release-signing.md) | Dedicated signing identity and automated GitHub Release | Active |

Vision and sequencer contracts live under [`../camera-vision/`](../camera-vision/).

## Pre-Development Checklist

Read the specific spec for every touched boundary:

- Session, navigation, DataStore, performance level, settings fields -> `session-persistence.md`.
- WAV parsing or import staging -> `audio-import.md`.
- Engine/JNI/SoundPool/Transport interaction -> `audio-playback.md`.
- Settings kit UI or SAF picker -> `audio-library-ui.md`.
- Room entities, DAO, migrations, file promotion, cleanup -> `database-guidelines.md`.
- New error code, fallback, or caught exception -> `error-handling.md`.
- Gradle signing, CI, R8, tags, release artifacts -> `release-signing.md`.
- Any new package or ownership move -> `directory-structure.md`.

Before changing a constant, search for the value and its tests. Before changing a cross-layer type, trace storage -> ViewModel -> UI/runtime consumers.

## Quality Check

- Run `./gradlew -PenableNativeBuild=true :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` for application changes.
- Add the relevant instrumented tests for Room, OpenCV, Compose semantics, or SoundPool behavior.
- For Release-affecting work, also build signed Release and run both release validation scripts.
- Confirm no UI layer opens managed files, queries Room, decodes WAV, or calls JNI directly.
- Confirm stable errors remain typed and cancellation is never converted into a generic failure.
- Update this index whenever adding, renaming, or retiring a spec.

**Language**: all code-spec documentation is written in English.
