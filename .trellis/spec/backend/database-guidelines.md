# Database Guidelines

> Executable Room and internal-file storage contracts for Void Music.

---

## Scenario: Imported audio kit library

### 1. Scope / Trigger

Use this contract whenever code creates, reads, updates, deletes, or migrates custom kits and imported audio assets. Built-in kits remain code-owned and must not be seeded into Room.

### 2. Signatures

- Database: `KitDatabase`, file `void_music_library.db`, schema version `1`.
- Tables:
  - `library_kits(id PRIMARY KEY, name, displayOrder, createdAtMs, updatedAtMs)`
  - `audio_assets(id PRIMARY KEY, storageKey UNIQUE, sha256 UNIQUE, metadata..., status)`
  - `kit_pad_mappings(kitId, pad, audioAssetId, PRIMARY KEY(kitId, pad))`
- Foreign keys:
  - deleting a kit cascades to its mappings;
  - deleting a referenced asset is restricted.
- File boundary: `AudioAssetStore` resolves only `<64 lowercase hex>.wav` storage keys under `filesDir/audio-assets` and stages imports under `filesDir/audio-import-staging`.
- Settings selection: persisted runtime selection uses `activeKitId`; `activeKitIndex` is legacy migration input only.
- Business boundary:
  - `copyKit(sourceId, name): LibraryResult<String>`
  - `replacePad(kitId, pad, originalName, openInput): LibraryResult<ReplacePadReport>`
  - `renameKit(kitId, name): LibraryResult<Unit>`
  - `deleteKit(kitId): LibraryResult<DeleteKitReport>`
  - `reconcile(): LibraryResult<ReconcileReport>`

### 3. Contracts

- Room stores metadata and relative `storageKey` values only. It must never store PCM/BLOB data, absolute paths, or document-provider URIs.
- Normalized audio files are private internal files. Staging and final asset directories must share `filesDir` so promotion can use an atomic move.
- Built-in kit summaries are merged with Room custom-kit summaries through `KitLibrary`; UI code must not query the DAO or resolve files directly.
- Export every Room schema to `app/schemas/` and commit it with the schema change.
- A complete kit insert is transactional: kit row, asset rows, and pad mappings either all commit or all roll back.
- `RoomKitLibrary` serializes mutations and runs them on its injected I/O dispatcher. Callers never perform DAO or final-file operations themselves.
- Built-in samples enter Room only when a user copies a built-in kit. The copy must create all five mappings or create nothing.
- `replacePad` promotes a normalized file before its Room transaction. A failed transaction deletes that newly promoted file; an undeletable file is reported with `recoveryRequired=true` and becomes a `reconcile()` orphan.
- Normalized SHA-256 is the deduplication identity. A matching usable asset is reused without creating a second row or file.
- Deletion first removes mappings and marks newly unreferenced rows `PENDING_DELETE` in one transaction. File deletion and final row deletion happen afterward and are retryable.
- `reconcile()` is lightweight: clean staging older than 24 hours, retry unreferenced rows, mark missing READY files BROKEN, and remove valid-key files with no database row. It must not hash, decode, or transcode.

### 4. Validation & Error Matrix

| Condition | Required result |
|---|---|
| Absolute, traversing, malformed, or uppercase storage key | Reject before filesystem access |
| Staging file outside the managed staging directory | Reject without deleting or moving it |
| Final asset already exists | Reject; never overwrite it |
| Atomic move unavailable | Fail the operation; do not silently downgrade to copy/delete |
| Mapping references a missing kit or asset | Foreign-key failure and transaction rollback |
| Asset is still referenced | Delete fails because of `RESTRICT` |
| Legacy or invalid kit index | Migrate to stable built-in ID; unknown values fall back to `default` |
| Blank, control-character, or over-40-character kit name | `LibraryErrorCode.INVALID_NAME` |
| Mutation targets a built-in kit | `BUILT_IN_IMMUTABLE` |
| Source kit lacks any stable pad mapping | `INCOMPLETE_KIT`; destination is not created |
| Imported WAV preparation fails | `IMPORT_FAILED` plus import/WAV code; original mapping is unchanged |
| Final atomic move fails | `STORAGE_FAILURE`; original mapping is unchanged and staging is cleaned when possible |
| Room transaction fails after file promotion | `DATABASE_FAILURE`; compensate the promoted file and set `recoveryRequired` if it remains |

### 5. Good / Base / Bad Cases

- Good: normalize into a managed staging file, validate it, atomically promote it using a SHA-256-derived storage key, then commit metadata and mappings through a transaction with explicit compensation on later-stage failure.
- Base: list built-in kits plus zero or more custom Room kits without writing built-ins to the database.
- Bad: persist `content://...`, `/data/...`, a raw WAV BLOB, or an array position as the durable identity of a kit or audio asset.
- Good deletion: remove one of two kits sharing an asset and keep the row/file until the last mapping is removed.
- Bad deletion: count references before deleting the kit in one transaction, then delete the file based on that stale count.

### 6. Tests Required

- Unit-test storage-key format, traversal rejection, outside-staging rejection, existing-target rejection, atomic promotion, and staging cleanup.
- Instrument-test schema creation from every committed schema JSON.
- Instrument-test unique indexes, foreign-key actions, observable catalogue ordering, and full rollback after a failed mapping insert.
- Instrument-test built-in copy completeness, normalized-content deduplication, custom-copy sharing, successful replacement, invalid-input preservation, database compensation, and final-move failure.
- Instrument-test deletion after the first and last shared reference, plus reconciliation of stale staging, PENDING_DELETE rows, missing READY files, and orphan final files.
- Unit-test legacy `activeKitIndex` values `0`, `1`, and an unknown value, plus migration idempotence.
- For later versions, add a Room migration test from every supported prior schema before increasing the version.

### 7. Wrong vs Correct

#### Wrong

```kotlin
@Entity data class AudioAsset(
    @PrimaryKey val id: String,
    val absolutePath: String,
    val pcm: ByteArray
)
```

#### Correct

```kotlin
@Entity(tableName = "audio_assets")
data class AudioAssetEntity(
    @PrimaryKey val id: String,
    val storageKey: String,
    val sha256: String,
    val status: String
)

val file = audioAssetStore.resolveAsset(entity.storageKey)
```

The correct form keeps filesystem ownership in `AudioAssetStore`, makes database backups portable inside the app sandbox, and prevents stale absolute paths or oversized Room rows.

For cross-resource mutation, never wrap filesystem I/O inside a Room transaction. Promote first, commit Room second, and explicitly delete the promoted file when the transaction fails.

---

## Migrations

- Do not migrate the obsolete `ods_kits.db` built-in metadata cache into the formal library; it contains no user-authored data.
- Future `void_music_library.db` changes must increase the Room version, add an explicit migration, update `app/schemas/`, and include a migration test.
- Preferences migrations must carry a version marker and be idempotent because decoding can also defensively apply current migrations.

## Naming Conventions

- SQL tables use plural `snake_case`; Kotlin entities use singular PascalCase with the `Entity` suffix.
- Stable enum-like database values use uppercase strings such as `READY`, `BROKEN`, and `PENDING_DELETE`.
- Filesystem keys are content-derived relative names, never user-provided filenames.

## Common Mistakes

- Do not use `REPLACE` for relational inserts: it can delete and recreate rows, triggering unintended foreign-key behavior. Use `ABORT` and explicit update operations.
- Do not switch the active runtime kit before every sample is prepared successfully; storage availability is not proof of playability.
- Do not delete an asset file before checking references and committing database state; use the task's pending-delete and reconciliation flow.
