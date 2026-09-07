# Audio Playback

> Executable prepared-kit, native/fallback backend, switching, and recovery contracts.

## Scenario: Prepare and activate a drum kit

### 1. Scope / Trigger

Apply this contract whenever built-in or imported samples cross from `KitLibrary` into `DrumEngine`, including foreground resume, kit selection, trigger submission, SoundPool fallback, and Oboe route recovery.

### 2. Signatures

```kotlin
suspend fun KitLibrary.prepare(kitId: String): LibraryResult<PreparedKit>

fun DrumEngine.start(): Boolean
fun DrumEngine.start(newKit: PreparedKit): Boolean
fun DrumEngine.setPreparedKit(newKit: PreparedKit): Boolean
fun DrumEngine.trigger(pad: DrumPad, velocity: Float)
fun DrumEngine.stop()

data class PreparedKit(
    val id: String,
    val name: String,
    val sampleRate: Int,
    val samples: Map<DrumPad, PreparedSample>
)
```

JNI is private to `DrumEngine`: `nativeStart`, `nativeTrigger`, `nativeSetVolume`, status counters, stream error, and `nativeStop`.

### 3. Contracts

- A `PreparedKit` contains every `DrumPad` exactly once, non-empty mono float PCM, one shared positive sample rate, stable source identity, and an internal SoundPool capability.
- `AudioSampleSource.BuiltIn` / `Imported` is exhaustive. Do not reintroduce boolean source flags or UI-visible paths.
- Room lookup, file reads, validation, normalization, and PCM decoding run through `KitLibrary.prepare` on its I/O dispatcher.
- Imported assets must be `READY`, present, mono PCM16 at 48 kHz, within five seconds, and consistent with stored metadata. Missing/invalid files become `BROKEN` and return `SOURCE_UNAVAILABLE`.
- Real backend order is Oboe first, then SoundPool. SoundPool succeeds only after all five asynchronous loads complete successfully.
- Oboe receives predecoded PCM and the prepared sample rate. No file I/O, allocation-heavy decode, or Room access may occur on trigger/audio callback paths.
- A kit switch commits only after a backend starts. Candidate failure restores the previous prepared kit and backend.
- Triggers during the short transition window use a bounded FIFO of 16 events; on overflow discard the oldest. Replay after successful recovery; clear after terminal failure/stop.
- Clamp trigger velocity and master volume to `0..1`.
- Foreground `stop()` releases active backend/monitor and queued events but retains the prepared kit for recovery. A later `start()` rebuilds from it.
- Poll native health every 250 ms. A non-zero stream error transitions `RUNNING -> RECOVERING -> RUNNING/FAILED` through the same startup/fallback path.
- `Transport` shares `DrumEngine.trigger` and therefore obeys the same readiness, queue, and volume contract.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Unknown kit ID | `KIT_NOT_FOUND`; current backend untouched |
| Incomplete custom mappings | `INCOMPLETE_KIT`; current backend untouched |
| Missing/non-READY/malformed/metadata-mismatched asset | `SOURCE_UNAVAILABLE`, mark `BROKEN`, keep current backend |
| `start()` without prepared kit | publish `FAILED/NONE` and return false |
| Native library/start unavailable | clean native state and try SoundPool |
| SoundPool partial load/failure/timeout | release partial pool; backend startup fails |
| Candidate Oboe and SoundPool both fail | restart previous prepared kit and return false |
| Previous-kit restart also fails | publish `FAILED/NONE`, clear queue, return false |
| Oboe route error | publish `RECOVERING`, rebuild current kit, then `RUNNING` or `FAILED` |
| Trigger while stopped intentionally | do not queue |
| Trigger during desired-running transition | queue up to 16, dropping oldest on overflow |

### 5. Good / Base / Bad Cases

- Good: prepare all five samples off Main, start Oboe at the prepared rate, queue a hit during a kit switch, then replay it after the candidate starts.
- Base: native library is absent, SoundPool loads all samples, and status truthfully reports `RUNNING/SOUND_POOL`.
- Bad: stop the old backend before preparation, treat a non-zero `SoundPool.load` ID as completion, or feed 44.1 kHz PCM to a stream opened at an implicit different rate.

### 6. Tests Required

- JVM: failed replacement restoration, failed foreground startup restoration, bounded queue ordering/overflow, same-pad/cross-pad density, volume/velocity clamping, and route recovery.
- JVM Transport: absolute deadlines, late-wake skip, stop/reset, restore, and grid isolation.
- Instrumented: built-in/imported preparation, missing-file `BROKEN` marking, complete SoundPool load and trigger.
- Native build: compile JNI/Oboe for every supported Debug ABI and arm64 Release.
- Signed device: Oboe primary, forced SoundPool fallback, active-kit switching, foreground/background recovery, route change, dropped trigger/xRun counters.

### 7. Wrong vs Correct

#### Wrong

```kotlin
engine.stop()
val bytes = File(path).readBytes()
engine.nativeStart(decode(bytes))
selectedKitId = candidateId
```

#### Correct

```kotlin
when (val prepared = kitLibrary.prepare(candidateId)) {
    is LibraryResult.Success ->
        if (engine.setPreparedKit(prepared.value)) persistActiveKit(candidateId)
    is LibraryResult.Failure -> publishFailure(prepared.error)
}
```

The correct order preserves the currently playable kit until the candidate is both complete and startable.
