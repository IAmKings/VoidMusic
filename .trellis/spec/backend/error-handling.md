# Error Handling

> Stable domain failures, exception boundaries, recovery, and UI feedback for Void Music.

## Scenario: Propagate a recoverable failure across storage, session, and UI

### 1. Scope / Trigger

Apply this contract when adding an operation that can fail across file, Room, DataStore, camera, MediaPipe, OpenCV, JNI/audio, or UI boundaries.

### 2. Signatures

```kotlin
sealed interface LibraryResult<out T> {
    data class Success<T>(val value: T) : LibraryResult<T>
    data class Failure(val error: LibraryError) : LibraryResult<Nothing>
}

data class LibraryError(
    val code: LibraryErrorCode,
    val importCode: AudioImportErrorCode? = null,
    val wavValidationCode: WavValidationCode? = null,
    val recoveryRequired: Boolean = false
)

sealed interface SettingsLoadState {
    data object Loading : SettingsLoadState
    data class Ready(val settings: Settings) : SettingsLoadState
    data class Error(val fallback: Settings, val cause: Throwable) : SettingsLoadState
}

data class AudioRuntimeStatus(
    val phase: AudioRuntimePhase,
    val backend: AudioBackend,
    val droppedTriggerCount: Long,
    val xRunCount: Long,
    val lastNativeErrorCode: Int
)
```

Public stable error enums are `LibraryErrorCode`, `AudioImportErrorCode`, `WavValidationCode`, `GridProjectionError`, and `AudioRuntimePhase`. Internal parser/import exceptions must not cross into Compose.

### 3. Contracts

- Expected business failures return typed results; do not use exception message text as a UI or test contract.
- `WavValidationException` and `AudioImportException` are internal translation mechanisms. `RoomKitLibrary` converts them into `LibraryError` before returning.
- Catch exceptions at the layer that can add a stable meaning. Preserve the most specific available nested code.
- Always rethrow `CancellationException` from coroutine catch blocks.
- A failure message is published through immutable state (`KitActionMessage` or `AudioRuntimeStatus`); UI renders localizable text and does not inspect exceptions.
- Recover before committing selection: failed kit preparation/start keeps or restores the previous playable kit and durable ID.
- When a filesystem action succeeds before a Room transaction fails, compensate the file; set `recoveryRequired=true` only if cleanup cannot complete.
- Camera/frame failures are isolated per frame: log, close/recycle owned resources exactly once, and continue the stream.
- GPU MediaPipe initialization failure may retry CPU. Oboe startup failure may retry SoundPool. Fallback success is a degraded success, not an exception leak.
- DataStore decode/read failure uses an explicit default fallback and completes startup; it must not leave navigation permanently loading.

### 4. Validation & Error Matrix

| Failure | Stable boundary behavior |
|---|---|
| Invalid kit name | `INVALID_NAME` |
| Unknown/built-in mutation target | `KIT_NOT_FOUND` / `BUILT_IN_IMMUTABLE` |
| Invalid imported stream | `IMPORT_FAILED` plus import/WAV code |
| Managed file missing or malformed | `SOURCE_UNAVAILABLE` and mark asset `BROKEN` |
| Atomic file promotion failure | `STORAGE_FAILURE`; mapping unchanged |
| Room transaction failure | `DATABASE_FAILURE`; compensate promoted file |
| Candidate playback backend cannot start | restore previous kit; return false and publish playback failure |
| Oboe route error | `RECOVERING` -> `RUNNING` or `FAILED` |
| Malformed settings JSON | default settings |
| Camera frame conversion/rotation failure | discard only that frame; close resources |
| Invalid grid geometry | typed `GridProjectionError`; preserve last valid projection |

### 5. Good / Base / Bad Cases

- Good: return `LibraryResult.Failure(LibraryError(IMPORT_FAILED, INVALID_WAV, TOO_LONG))` and keep the original pad mapping.
- Base: Oboe fails, SoundPool starts, status reports `RUNNING/SOUND_POOL`, and the user can continue.
- Bad: `catch (Exception) { Success(Unit) }`, expose `Throwable.message` to Compose, swallow coroutine cancellation, or leave a promoted file after transaction failure without recovery metadata.

### 6. Tests Required

- Assert stable error codes and nested WAV codes, not prose messages.
- Assert original database selection/mapping/backend is unchanged after every failed replacement.
- Assert compensation and `recoveryRequired` behavior for cleanup failure.
- Assert cancellation escapes operation wrappers and busy state is cleared in `finally`.
- Assert camera consumer failure isolation and exactly-once cleanup.
- Assert native fallback/recovery status transitions and previous-kit restoration.
- Assert malformed settings complete loading with a default fallback.

### 7. Wrong vs Correct

#### Wrong

```kotlin
try {
    library.replacePad(...)
} catch (error: Exception) {
    snackbar(error.message ?: "failed")
}
```

#### Correct

```kotlin
when (val result = library.replacePad(...)) {
    is LibraryResult.Success -> publishSuccess(result.value)
    is LibraryResult.Failure -> publishFailure(result.error)
}
```

The correct form keeps internal exceptions private and gives tests/UI a stable exhaustive contract.
