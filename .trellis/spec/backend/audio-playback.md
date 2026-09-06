# Audio Playback Guidelines

> Executable prepared-kit and backend-switching contracts for Void Music.

## Scenario: Prepare and activate a drum kit

### 1. Scope / Trigger

Apply this contract whenever built-in or imported samples cross from `KitLibrary` into `DrumEngine`, including foreground resume, kit selection, SoundPool fallback, and Oboe route recovery.

### 2. Signatures

```kotlin
suspend fun KitLibrary.prepare(kitId: String): LibraryResult<PreparedKit>
fun DrumEngine.start(newKit: PreparedKit): Boolean
fun DrumEngine.setPreparedKit(newKit: PreparedKit): Boolean
```

`PreparedKit` contains every `DrumPad`, non-empty mono float PCM, one shared sample rate, a stable `AudioSampleSource`, and an internal SoundPool load capability. It never contains a DAO entity, URI, or UI-owned absolute path.

### 3. Contracts

- `SampleRef` and boolean source flags are forbidden. Use the exhaustive `AudioSampleSource.BuiltIn` / `Imported` model.
- Room lookup, managed-file reads, WAV validation, normalization, and PCM decoding run through `KitLibrary.prepare` on its I/O dispatcher.
- Imported assets must be `READY`, present, mono PCM16 at 48 kHz, within five seconds, and consistent with their stored byte/frame metadata before a `PreparedKit` is returned.
- A missing or invalid imported asset is marked `BROKEN` and returns `SOURCE_UNAVAILABLE`; it never reaches a playback backend.
- `MainScreen` may request preparation through its ViewModel, but it must not open files, query Room, resolve storage keys, or decode audio.
- Oboe receives only predecoded PCM and the prepared sample rate. Both exclusive/Float and shared/I16 stream attempts request that rate.
- SoundPool startup succeeds only after every scheduled sample reports successful completion. A queued load, partial kit, timeout, or failed sample is startup failure.
- A kit switch commits only after a backend session starts. Startup failure restores the previous `PreparedKit`; transition triggers use a bounded queue and replay after recovery.
- A failed backend start publishes `PLAYBACK_FAILURE`, informs the user immediately, and persists the restored kit selection.
- Foreground stop retains the prepared kit so resume can rebuild it without weakening the rollback path.
- The health monitor rebuilds the current prepared kit after an Oboe route error and may fall back to SoundPool through the same backend-start contract.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Unknown kit ID | `KIT_NOT_FOUND`; current backend is untouched |
| Incomplete custom mappings | `INCOMPLETE_KIT`; current backend is untouched |
| Missing, non-READY, malformed, or metadata-mismatched asset | `SOURCE_UNAVAILABLE`, mark asset `BROKEN`, keep current backend |
| Candidate Oboe and SoundPool startup both fail | Restart previous prepared kit and return `false` |
| Previous-kit restart also fails | Publish `FAILED`, clear transition queue, return `false` |
| SoundPool load callback fails or times out | Release the partial pool and report startup failure |
| Oboe route error | Publish `RECOVERING`, rebuild current kit, then publish `RUNNING` or `FAILED` |

### 5. Tests Required

- Unit-test failed replacement restoration, failed foreground startup restoration, bounded transition-trigger replay, dense same-pad/cross-pad ordering, and Oboe route recovery.
- Keep STEP `Transport` deadline, late-wake, stop, and grid-isolation tests passing because it shares `DrumEngine.trigger`.
- Instrument-test built-in and imported preparation, missing-file `BROKEN` marking, and SoundPool loading/triggering of an imported prepared kit.
- Compile JNI/Oboe in the quality gate; signed Release must verify imported playback on both the primary and forced-fallback paths before publication.

### 6. Forbidden Patterns

- Do not call `resources.openRawResource`, `File.readBytes`, `WavDecoder`, or Room DAO methods from Compose.
- Do not stop the old backend before the candidate is fully prepared.
- Do not treat `SoundPool.load()` returning a non-zero ID as completion.
- Do not let Oboe consume 44.1 kHz PCM while its stream runs at an implicit device rate.
- Do not persist decoded PCM, absolute paths, or document-provider URIs.
