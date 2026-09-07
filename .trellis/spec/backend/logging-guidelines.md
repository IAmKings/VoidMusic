# Logging Guidelines

> Operational logging and diagnostics for local Android, native audio, and release tooling.

## Logging Backends

- Kotlin/Android uses `android.util.Log` with one stable class/module tag.
- Native audio uses Android log output only for lifecycle, stream-open, and failure diagnostics; never log from the realtime audio callback.
- CI and shell scripts write concise human-readable progress to stdout and failures to stderr with non-zero exit status.
- High-frequency performance values use bounded in-memory metrics (`VisionMetrics` and `AudioRuntimeStatus`), not per-frame log lines.

## Levels

| Level | Use |
|---|---|
| Debug | Development-only state that is disabled or absent from hot production paths |
| Info | Successful lifecycle transition worth diagnosing: backend ready/recovered, tracker delegate selected |
| Warn | Recoverable degradation: dropped camera frame, GPU -> CPU, Oboe -> SoundPool, cleanup deferred |
| Error | Terminal component failure: both audio backends fail, MediaPipe cannot initialize, unrecoverable operation |

Do not log an expected typed user validation failure as a stack trace. Return its stable code to the UI.

## Required Context

- Identify the component through its tag.
- Log the failed operation and fallback/recovery outcome, not only the exception.
- For audio route recovery, include backend and elapsed recovery time; expose counters through status rather than repeated logs.
- For camera consumer failure, include consumer index while preserving later-consumer execution.
- Release scripts identify the failing validation step without printing secret values.

## Sensitive Data

Never log:

- keystore bytes, passwords, aliases supplied as secrets, or secret-file contents;
- imported audio bytes, PCM arrays, hashes combined with user paths, or absolute managed paths;
- document-provider URIs or provider metadata beyond a sanitized display name;
- camera frames, hand landmark arrays, or any image-derived personal data;
- full serialized Settings or Room rows.

Stable asset/kit IDs, enum codes, counts, durations, FPS/percentiles, backend names, and non-sensitive error codes are acceptable when needed.

## Hot-Path Rule

```kotlin
// Wrong: allocates and writes every frame.
Log.d(TAG, "hands=$hands zones=$zones fps=$fps")

// Correct: update bounded metrics; emit at most once per snapshot interval.
metricsRecorder.recordSegmentation(durationMs)
    ?.let { events.tryEmit(LivePerformanceEvent.Metrics(it)) }
```

The default metrics snapshot interval is one second with bounded sample windows. Do not add formatting, stack capture, JNI polling, or disk I/O to camera analysis, MediaPipe callback, hit arbitration, Transport tick, or Oboe callback paths.

## Failure Logging Pattern

```kotlin
val started = runCatching { startPrimary() }
    .onFailure { Log.w(TAG, "Primary backend unavailable; trying fallback", it) }
    .getOrNull()
    ?: startFallback()
```

Log once at the recovery boundary. Lower layers should not each emit the same exception unless they add distinct diagnostic context.
