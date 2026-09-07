# Audio Library UI

> Executable Settings, SAF, lifecycle, and feedback contracts for local custom-kit management.

## Scenario: Manage and import a custom drum kit

### 1. Scope / Trigger

Apply this contract whenever Settings displays the merged kit catalogue or starts selection, copy, rename, delete, or per-pad document import.

### 2. Signatures

```kotlin
val SessionViewModel.kitLibraryState: StateFlow<KitLibraryUiState>
val SessionViewModel.playbackKitRevision: StateFlow<Long>

fun SessionViewModel.selectKit(id: String)
fun SessionViewModel.copyActiveKit(name: String)
fun SessionViewModel.renameKit(id: String, name: String)
fun SessionViewModel.deleteKit(id: String)
fun SessionViewModel.replaceKitPad(kitId: String, pad: DrumPad, uri: Uri)
fun SessionViewModel.clearKitMessage(id: Long)
```

`KitLibraryUiState` contains only `KitSummary`, one optional `KitOperation`, and one optional `KitActionMessage`. It never contains a Room entity, path, URI, stream, or PCM.

### 3. Contracts

- `MainActivity` owns one `SessionViewModel` and passes it to Main and Settings. Never create destination-local session owners.
- Compose renders state and invokes ViewModel commands only. DAO, database entities, managed paths, staging files, and decoded PCM stay below the session boundary.
- The user must copy a built-in or custom kit before mutation; every copy starts with all five playable `DrumPad` mappings.
- Built-in kits remain selectable and immutable. Only custom kits expose rename, delete, and per-pad import.
- Serialize mutations with one visible `KitOperation`. While busy, disable every selection/mutation control; compare-and-set rejects duplicate starts.
- `OpenDocument` URI and pending picker identity may be transient UI state. Never persist the URI. Query display name and open its stream on the library I/O path.
- Prepare a candidate before persisting selection. A selection succeeds only when `KitLibrary.prepare` succeeds.
- Active-kit replacement invalidates the prepared-kit cache and increments `playbackKitRevision` only after the atomic operation succeeds.
- Before deleting the active custom kit, persist `default`. If deletion fails, restore the original active ID.
- Every completed action publishes one message with a monotonic ID. Clearing a message must only clear the matching ID.
- Cancellation is not failure: rethrow `CancellationException` and clear busy state in `finally`.

### 4. Validation & Error Matrix

| Condition | UI/session behavior |
|---|---|
| Blank/control/over-40 name | Show `INVALID_NAME`; keep dialog data available for correction |
| Built-in rename/delete/import | Action is hidden/disabled; domain still returns `BUILT_IN_IMMUTABLE` |
| Unknown/incomplete kit | Show `KIT_NOT_FOUND` / `INCOMPLETE_KIT`; selection unchanged |
| Picker cancelled | No mutation and no success message |
| Provider has no display name | Use sanitized fallback `sample.wav` |
| Provider stream unavailable | Show storage/import failure; mapping unchanged |
| Import validation fails | Render specific size/duration/format message from nested codes |
| Successful active-pad replacement | Publish success, increment revision, next playback prepares new mapping |
| Delete active kit fails | Restore its active ID and publish failure |
| Cleanup remains pending | Publish success plus pending cleanup count; reconciliation owns retry |

### 5. Good / Base / Bad Cases

- Good: copy the active kit, import one WAV through SAF, atomically update the mapping, invalidate prepared audio, and show one actionable result.
- Base: list two built-ins plus zero custom kits without creating Room rows for built-ins.
- Bad: let a Composable retain `content://...`, call a DAO, or mark selection active before all five samples prepare.

### 6. Tests Required

- Compose: built-in immutability, five custom pad actions, selection callbacks, busy-state disabling, progress semantics, and target-specific accessibility labels.
- Instrumented: built-in/custom catalogue merge, cache reuse, invalidation after rename/replacement, provider import, active deletion fallback, and process/offline recovery.
- Playback acceptance: imported kit on Oboe and forced SoundPool fallback; failed candidate restores previous audible kit.
- Assert picker cancellation creates no message, row, file, or revision.

### 7. Wrong vs Correct

#### Wrong

```kotlin
onKitClick = {
    viewModel.setActiveKit(kit.id)
    engine.setPreparedKit(library.prepare(kit.id).value)
}
```

#### Correct

```kotlin
onKitClick = { viewModel.selectKit(kit.id) }
// ViewModel prepares first and persists only in onSuccess.
```

The correct form keeps preparation, rollback, busy state, and user feedback in one coordinator.
