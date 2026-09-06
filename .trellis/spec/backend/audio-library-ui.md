# Audio Library UI Guidelines

> Executable settings and lifecycle contracts for local custom-kit management in Void Music.

## Scenario: Manage and import a custom drum kit

### 1. Scope / Trigger

Apply this contract whenever Settings displays the merged kit catalogue or starts a copy, selection, rename, delete, or per-pad document import.

### 2. Boundaries

```kotlin
val SessionViewModel.kitLibraryState: StateFlow<KitLibraryUiState>
fun SessionViewModel.copyActiveKit(name: String)
fun SessionViewModel.selectKit(id: String)
fun SessionViewModel.replaceKitPad(kitId: String, pad: DrumPad, uri: Uri)
```

- `MainActivity` owns one `SessionViewModel` and passes it to both Main and Settings destinations. Do not create destination-local session owners.
- Compose renders `KitSummary` and invokes ViewModel commands only. It must not access a DAO, database entity, managed path, staging file, or decoded PCM.
- The system `OpenDocument` result may exist transiently while the ViewModel copies it. Never persist the document URI as library state.
- Query provider metadata and open the document stream only on the library I/O path. Treat filename and MIME as untrusted display hints.

### 3. Interaction Contracts

- The user first copies the active built-in or custom kit, so every new custom kit starts with five playable mappings.
- Built-in kits remain selectable but never expose rename, delete, or pad replacement actions.
- A custom active kit exposes exactly one import action for each `DrumPad`.
- Serialize mutations with one visible progress state. While busy, all selection and mutation controls are disabled to prevent duplicate commits.
- Prepare a candidate before persisting selection. Replacing a pad invalidates the prepared custom-kit cache and increments the playback revision only after the atomic library operation succeeds.
- If the active custom kit is deleted, persist the default built-in ID before deleting its mappings/assets. Restore the original selection when deletion fails.
- Every result produces a localizable, actionable message. Size, duration, encoding, channels, bit depth, sample rate, structural corruption, storage, and database failures must remain distinguishable where supported by `LibraryError`.

### 4. Lifecycle Contracts

- Save only dialog text and pending picker kit/pad identity across recreation. Never save a URI or an in-progress stream.
- Room owns kit/mapping persistence and DataStore owns the stable active kit ID, so backgrounding, rotation, process recreation, offline restart, and app update do not require UI state reconstruction.
- Main and Settings share the same playback revision stream. A successful replacement of the active kit must cause the next playback session to prepare the new mapping.
- Dismissing or cancelling the picker performs no mutation and emits no success message.

### 5. Accessibility and Tests

- Kit cards identify both the kit name and whether it is currently selected.
- Rename and delete buttons include the target kit name; the progress indicator announces that a kit operation is running.
- Compose tests cover built-in immutability, custom five-pad actions, selection callbacks, busy-state disabling, and progress semantics.
- Instrument tests cover prepared custom-kit cache reuse and invalidation after rename and replacement.
- The final signed-Release gate still requires a real provider import, offline restart, background recovery, active-kit deletion fallback, and playback on Oboe plus SoundPool.

### 6. Forbidden Patterns

- Do not call `setActiveKit` directly from a kit card without preparing the kit first.
- Do not keep separate `SessionViewModel` instances per navigation destination.
- Do not expose an absolute path, Room entity, file handle, or persisted document URI to Compose.
- Do not allow built-in mutation or concurrent library commands.
- Do not claim an import is ready until file promotion, Room mapping update, cache invalidation, and playback revision publication have completed.
