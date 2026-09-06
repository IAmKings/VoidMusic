# Audio Import Guidelines

> Executable WAV validation and normalization contracts for Void Music.

## Scenario: Prepare an untrusted WAV for the kit library

### 1. Scope / Trigger

Apply this contract to every user-selected audio stream before it can become an `AudioAssetEntity` or a playable sample.

### 2. Signatures

```kotlin
fun WavDecoder.inspect(data: ByteArray, maxDurationSeconds: Int? = null): Pcm16Wav
fun WavDecoder.normalizeToMonoPcm16Wav(
    data: ByteArray,
    targetSampleRate: Int = 48_000,
    maxDurationSeconds: Int = 5
): NormalizedWav
fun AudioImporter.prepare(openInput: () -> InputStream): PreparedAudioImport
```

Stable error types are `WavValidationCode`, `AudioImportErrorCode`, `WavValidationException`, and `AudioImportException`.

### 3. Contracts

- Input content, not filename, MIME type, URI, or provider metadata, determines validity.
- Accept RIFF/WAVE integer PCM16 with one or two channels and a sample rate from 8,000 through 96,000 Hz.
- Reject inputs over 10 MiB while streaming and inputs over five seconds after validated frame counting.
- Interpret `fmt ` and `data` only through `WavDecoder.inspect`; decoding and normalization consume that validated result.
- Skip unknown chunks using unsigned little-endian lengths and mandatory odd-byte padding.
- Normalize to mono PCM16 using sample conversion plus real resampling. Never change only the WAV header.
- Compute SHA-256 during source copying and again from normalized content. The normalized hash becomes `<hash>.wav`.
- A successful `prepare` leaves exactly one managed normalized staging file. Every failure attempts to leave none and never creates a final asset.

### 4. Validation & Error Matrix

| Condition | Stable result |
|---|---|
| Stream exceeds 10 MiB | `AudioImportErrorCode.TOO_LARGE` |
| RIFF/WAVE or chunk structure invalid | `INVALID_WAV` plus a `WavValidationCode` |
| Source/staging read, write, permission, or cleanup failure | `IO_FAILURE` |
| Missing/duplicate `fmt ` or `data` | matching missing/duplicate WAV code |
| Unsupported encoding, channels, bits, or sample rate | matching unsupported WAV code |
| Empty, misaligned, truncated, or over-duration data | matching structural WAV code |

### 5. Good / Base / Bad Cases

- Good: stream to managed staging with a 32 KiB buffer, validate once, decode from validated offsets, resample samples, write a new canonical WAV, hash it, and return its staging handle.
- Base: mono PCM16 already at the target rate is copied into a fresh canonical WAV without changing sample values.
- Bad: call `readBytes()` directly on a document-provider stream, trust header offsets `20/22/34`, divide a forged data size, or persist the source URI.

### 6. Tests Required

- Cover RIFF/WAVE signatures, declared RIFF length, chunk bounds, unknown chunks, odd padding, missing and duplicate chunks.
- Cover PCM encoding, mono/stereo, 16-bit depth, byte rate, block alignment, empty data, frame alignment, and 8–96 kHz boundaries.
- Assert exactly five seconds succeeds and one extra frame fails.
- Reload normalized output through the same parser and assert mono, target rate, frame count, and representative sample values.
- Fuzz deterministic malformed byte arrays and assert failures never escape as bounds errors.
- Assert oversize, invalid, over-duration, read-failure, and cleanup paths leave no final asset or orphan staging file.

### 7. Wrong vs Correct

#### Wrong

```kotlin
val bytes = resolver.openInputStream(uri)!!.readBytes()
val channels = readU16(bytes, 22)
rewriteSampleRateHeader(bytes, 48_000)
```

#### Correct

```kotlin
val prepared = audioImporter.prepare { requireNotNull(resolver.openInputStream(uri)) }
// The library coordinator may now atomically promote prepared.stagingFile.
```

The correct boundary limits memory and size before parsing, exposes stable errors, performs actual sample-rate conversion, and retains a single cleanup owner.
