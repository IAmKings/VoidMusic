# Session and Settings Persistence

> Executable DataStore, ViewModel, onboarding, and runtime-performance contracts for Void Music.

## Scenario: Restore and mutate application session state

### 1. Scope / Trigger

Apply this contract when changing `Settings`, `SettingsRepository`, `SessionViewModel`, navigation startup, onboarding completion, performance selection, hit tuning, sequence persistence, or calibration persistence.

This is a cross-layer contract because DataStore state controls navigation, Compose state, camera pipeline construction, audio selection, and step playback.

### 2. Signatures

```kotlin
data class Settings(
    val detectionConfig: DetectionConfig,
    val performanceLevel: PerformanceLevel,
    val hapticEnabled: Boolean,
    val masterVolume: Float,
    val lastBpm: Int,
    val activeKitId: String?,
    val hitVelocityThreshold: Float,
    val hitCooldownMs: Long,
    val smoothingMinCutoff: Float,
    val smoothingBeta: Float,
    val lastMode: String,
    val sequenceGrid: List<List<Boolean>>,
    val calibration: List<CalibrationPoint>
)

val SettingsRepository.settings: Flow<Settings>
val SettingsRepository.onboardingCompleted: Flow<Boolean>
suspend fun SettingsRepository.update(transform: (Settings) -> Settings)
suspend fun SettingsRepository.reset()
suspend fun SettingsRepository.markOnboardingCompleted()

fun initialDestination(onboardingCompleted: Boolean): String
```

Persist settings as JSON in DataStore Preferences file `ods_settings`, key `settings_json`. Persist onboarding separately under `onboarding_completed`.

### 3. Contracts

- `SessionViewModel` is activity-scoped and is the only settings subscription shared by Main and Settings destinations.
- `settingsState` starts as `Loading`, then becomes `Ready(settings)` or `Error(Settings.DEFAULT, cause)`. UI must not choose navigation or construct persisted-state-dependent runtime objects from a temporary default.
- `onboardingState` is atomic: render no navigation graph until `isLoaded == true`; route to Main only when `completed == true`.
- Opening the guide from Settings must not clear `onboarding_completed`. `reset()` restores `Settings.DEFAULT` only.
- Every partial mutation uses `SettingsRepository.update`; it decodes the latest stored snapshot inside `DataStore.edit`, applies the transform, then applies all current migrations. Never save a stale full UI snapshot.
- JSON uses `ignoreUnknownKeys=true` and `encodeDefaults=true`. Added fields require defaults so older JSON remains decodable.
- Malformed settings JSON falls back inside `SettingsRepository`. A settings-stream read failure becomes `SettingsLoadState.Error` and completes settings loading.
- `activeKitIndex` is legacy input only. Versioned migration maps `0 -> default`, `1 -> electro`, and every unknown value to `default`; a non-null current `activeKitId` is stable identity.
- Runtime power-save or thermal downgrade is derived in `RuntimePerformancePolicy`; it must never overwrite the persisted preferred performance level.
- Persisted grid shape is always normalized to 4×16. BPM is clamped to `40..220`; volume to `0..1`; hit threshold to `0.2..2.0`; hit rearm to `50..500 ms`; smoothing to `minCutoff 1.5..4.0` and `beta 0.02..0.1`.
- Persist calibration only when exactly four points are supplied; normalize each coordinate into `0..1`. `GridProjection` remains responsible for geometric validity before the UI confirms a draft.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| No stored JSON | Emit `Settings.DEFAULT` |
| Malformed or incompatible JSON | Emit `Settings.DEFAULT`; do not crash startup |
| Unknown future JSON field | Ignore it and decode known fields |
| Settings DataStore read failure | `SettingsLoadState.Error` with default fallback; mark settings loading complete |
| Onboarding DataStore read failure | Required target: emit `OnboardingState(isLoaded=true, completed=false)` so startup can show onboarding |
| Onboarding value not loaded | Render the neutral loading surface; do not create `NavHost` |
| Legacy kit index | Migrate once to a stable ID and current version marker |
| Legacy hit defaults `0.6` plus `110/250 ms` | Migrate once to `0.5` and `60 ms` |
| Custom legacy hit values | Preserve values and only advance the version marker |
| Invalid mode string | Restore `StudioMode.TAP` |
| Grid with missing/excess cells | Copy only a safe 4×16 snapshot; missing cells become `false` |
| Power-save enabled or thermal status moderate+ | Effective runtime level is LOW; persisted preference is unchanged |

### 5. Good / Base / Bad Cases

- Good: wait for `onboardingState.isLoaded`, create one `SessionViewModel`, restore the persisted mode/grid/calibration, and derive a temporary LOW level while the device is hot.
- Base: a fresh install emits defaults, shows onboarding once, and later launches directly into Main.
- Bad: create the navigation graph from `StateFlow`'s initial `false`, causing onboarding to flash or reappear; or persist the runtime thermal downgrade as the user's new preference.

### 6. Tests Required

- `SettingsSerializationTest`: missing fields, unknown fields, 4×16 defaults, migration idempotence, and custom hit-value preservation.
- `SettingsPreferencesMigrationTest`: DataStore migration persists the stable kit ID before consumers observe settings.
- `DestinationsTest`: incomplete onboarding routes to Onboarding and completed onboarding routes to Main.
- `RuntimeConstraintsTest`: power-save/thermal downgrade and restoration of the preferred level.
- ViewModel/Compose coverage: Settings and Main share one owner; reset does not clear onboarding; malformed settings complete loading with a fallback.
- Add a repository/ViewModel failure test proving onboarding read failure exits the neutral loading surface.

### 7. Wrong vs Correct

#### Wrong

```kotlin
val settings by viewModel.settings.collectAsState(Settings.DEFAULT)
NavHost(startDestination = initialDestination(false))
repo.save(settings.copy(masterVolume = value))
```

#### Correct

```kotlin
val onboarding by viewModel.onboardingState.collectAsStateWithLifecycle()
if (onboarding.isLoaded) {
    VoidMusicNavHost(initialDestination(onboarding.completed), viewModel, viewModel::completeOnboarding)
}
repo.update { current -> current.copy(masterVolume = value.coerceIn(0f, 1f)) }
```

The correct form waits for durable startup state and patches the latest stored value atomically.

> **Known implementation gap**: `SessionViewModel.onboardingState` currently maps
> `repo.onboardingCompleted` without a `catch` fallback. A DataStore read failure
> can therefore leave `isLoaded == false` indefinitely. Treat the validation row
> and test above as a required follow-up before claiming startup failure recovery.
