package com.electrodig.voidmusic.persistence

import android.content.Context
import com.electrodig.voidmusic.audio.BuiltInKits
import com.electrodig.voidmusic.audio.DeleteKitReport
import com.electrodig.voidmusic.audio.Kit
import com.electrodig.voidmusic.audio.KitLibrary
import com.electrodig.voidmusic.audio.KitSummary
import com.electrodig.voidmusic.audio.LibraryError
import com.electrodig.voidmusic.audio.LibraryErrorCode
import com.electrodig.voidmusic.audio.LibraryResult
import com.electrodig.voidmusic.audio.ReconcileReport
import com.electrodig.voidmusic.audio.ReplacePadReport
import com.electrodig.voidmusic.detection.color.DrumPad
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun interface BuiltInSampleSource {
    fun open(rawResourceId: Int): InputStream
}

/**
 * Owns the cross-resource commit protocol for custom kits.
 * All mutations are serialized so hash deduplication and compensation remain deterministic.
 */
internal class RoomKitLibrary(
    private val database: KitDatabase,
    private val assetStore: AudioAssetStore,
    private val audioImporter: AudioImporter,
    private val builtInSampleSource: BuiltInSampleSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) : KitLibrary {
    private val dao = database.kitDao()
    private val mutationMutex = Mutex()
    private val builtIns = BuiltInKits.all.map { kit ->
        KitSummary(id = kit.id, name = kit.name, isBuiltIn = true)
    }

    override val kits: Flow<List<KitSummary>> = dao.observeAllKits().map { imported ->
        builtIns + imported.map { kit ->
            KitSummary(id = kit.id, name = kit.name, isBuiltIn = false)
        }
    }

    override suspend fun copyKit(sourceId: String, name: String): LibraryResult<String> =
        mutate {
            val normalizedName = normalizeName(name)
                ?: return@mutate failure(LibraryErrorCode.INVALID_NAME)
            val customId = newId()
            if (BuiltInKits.all.any { it.id == customId } || dao.kitById(customId) != null) {
                return@mutate failure(LibraryErrorCode.DATABASE_FAILURE)
            }
            val createdAt = nowMs()
            val kit = LibraryKitEntity(
                id = customId,
                name = normalizedName,
                displayOrder = nextDisplayOrder(),
                createdAtMs = createdAt,
                updatedAtMs = createdAt
            )

            val builtIn = BuiltInKits.all.firstOrNull { it.id == sourceId }
            if (builtIn != null) {
                copyBuiltInKit(builtIn, kit)
            } else {
                copyCustomKit(sourceId, kit)
            }
        }

    override suspend fun replacePad(
        kitId: String,
        pad: DrumPad,
        originalName: String,
        openInput: () -> InputStream
    ): LibraryResult<ReplacePadReport> = mutate {
        if (BuiltInKits.all.any { it.id == kitId }) {
            return@mutate failure(LibraryErrorCode.BUILT_IN_IMMUTABLE)
        }
        val kit = dao.kitById(kitId) ?: return@mutate failure(LibraryErrorCode.KIT_NOT_FOUND)
        if (!isCompleteKit(kitId)) return@mutate failure(LibraryErrorCode.INCOMPLETE_KIT)

        val prepared = try {
            audioImporter.prepare(openInput)
        } catch (import: AudioImportException) {
            return@mutate LibraryResult.Failure(
                LibraryError(
                    code = LibraryErrorCode.IMPORT_FAILED,
                    importCode = import.code,
                    wavValidationCode = import.wavValidationCode
                )
            )
        }
        val materialized = try {
            materialize(prepared, normalizeOriginalName(originalName), nowMs())
        } catch (failure: StorageFailure) {
            return@mutate failure(
                LibraryErrorCode.STORAGE_FAILURE,
                recoveryRequired = prepared.stagingFile.exists()
            )
        }

        val oldPending = try {
            dao.replacePadAsset(
                kitId = kit.id,
                pad = pad.name,
                newAssetId = materialized.entity.id,
                insertedAsset = materialized.insertedEntity,
                updatedAsset = materialized.updatedEntity,
                updatedAtMs = nowMs()
            )
        } catch (cancelled: CancellationException) {
            compensate(materialized.createdFile)
            throw cancelled
        } catch (_: Exception) {
            compensate(materialized.createdFile)
            return@mutate failure(
                LibraryErrorCode.DATABASE_FAILURE,
                recoveryRequired = materialized.createdFile?.exists() == true
            )
        }

        val cleanupPending = oldPending?.let {
            cleanupPendingAssetSafely(it) != CleanupOutcome.DELETED
        } == true
        LibraryResult.Success(
            ReplacePadReport(
                reusedExistingAsset = materialized.existedBefore,
                pendingCleanupCount = if (cleanupPending) 1 else 0
            )
        )
    }

    override suspend fun renameKit(kitId: String, name: String): LibraryResult<Unit> = mutate {
        if (BuiltInKits.all.any { it.id == kitId }) {
            return@mutate failure(LibraryErrorCode.BUILT_IN_IMMUTABLE)
        }
        val normalizedName = normalizeName(name)
            ?: return@mutate failure(LibraryErrorCode.INVALID_NAME)
        if (dao.updateKitName(kitId, normalizedName, nowMs()) != 1) {
            failure(LibraryErrorCode.KIT_NOT_FOUND)
        } else {
            LibraryResult.Success(Unit)
        }
    }

    override suspend fun deleteKit(kitId: String): LibraryResult<DeleteKitReport> = mutate {
        if (BuiltInKits.all.any { it.id == kitId }) {
            return@mutate failure(LibraryErrorCode.BUILT_IN_IMMUTABLE)
        }
        if (dao.kitById(kitId) == null) return@mutate failure(LibraryErrorCode.KIT_NOT_FOUND)
        val pending = dao.deleteKitAndMarkOrphans(kitId, nowMs())
        var deleted = 0
        var failed = 0
        pending.forEach { asset ->
            when (cleanupPendingAssetSafely(asset)) {
                CleanupOutcome.DELETED -> deleted += 1
                CleanupOutcome.FAILED, CleanupOutcome.REFERENCED -> failed += 1
            }
        }
        LibraryResult.Success(
            DeleteKitReport(
                kitId = kitId,
                deletedAssetCount = deleted,
                pendingAssetCount = failed
            )
        )
    }

    override suspend fun reconcile(): LibraryResult<ReconcileReport> = mutate {
        val staging = storage {
            assetStore.cleanupStagingOlderThan(nowMs() - STAGING_MAX_AGE_MS)
        }
        var pendingDeleted = 0
        var pendingFailed = 0
        var broken = 0

        dao.assetsByStatus(AudioAssetStatuses.PENDING_DELETE).forEach { asset ->
            if (dao.assetReferenceCount(asset.id) != 0) {
                val status = if (storage { assetStore.assetExists(asset.storageKey) }) {
                    AudioAssetStatuses.READY
                } else {
                    broken += 1
                    AudioAssetStatuses.BROKEN
                }
                dao.updateAssetStatus(asset.id, status, nowMs())
            } else {
                when (cleanupPendingAsset(asset)) {
                    CleanupOutcome.DELETED -> pendingDeleted += 1
                    CleanupOutcome.FAILED, CleanupOutcome.REFERENCED -> pendingFailed += 1
                }
            }
        }

        dao.assetsByStatus(AudioAssetStatuses.READY).forEach { asset ->
            if (!storage { assetStore.assetExists(asset.storageKey) }) {
                dao.updateAssetStatus(asset.id, AudioAssetStatuses.BROKEN, nowMs())
                broken += 1
            }
        }

        // No mutation can be active while this mutex is held, so any zero-reference
        // row is a proven interrupted-operation orphan regardless of its status.
        dao.allAssets().forEach { asset ->
            if (asset.status != AudioAssetStatuses.PENDING_DELETE &&
                dao.assetReferenceCount(asset.id) == 0
            ) {
                when (cleanupPendingAsset(asset)) {
                    CleanupOutcome.DELETED -> pendingDeleted += 1
                    CleanupOutcome.FAILED, CleanupOutcome.REFERENCED -> pendingFailed += 1
                }
            }
        }

        val recordedKeys = dao.allAssets().map(AudioAssetEntity::storageKey).toSet()
        var orphanDeleted = 0
        var orphanFailed = 0
        (storage(assetStore::assetStorageKeys) - recordedKeys).forEach { storageKey ->
            if (storage { assetStore.deleteAsset(storageKey) }) orphanDeleted += 1 else orphanFailed += 1
        }

        LibraryResult.Success(
            ReconcileReport(
                deletedStagingCount = staging.deleted,
                failedStagingDeleteCount = staging.failed,
                deletedPendingAssetCount = pendingDeleted,
                pendingAssetFailureCount = pendingFailed,
                brokenAssetCount = broken,
                deletedOrphanFileCount = orphanDeleted,
                orphanFileFailureCount = orphanFailed
            )
        )
    }

    private suspend fun copyCustomKit(
        sourceId: String,
        destination: LibraryKitEntity
    ): LibraryResult<String> {
        if (dao.kitById(sourceId) == null) return failure(LibraryErrorCode.KIT_NOT_FOUND)
        val sourceMappings = dao.mappingsForKit(sourceId)
        if (!hasEveryPad(sourceMappings)) return failure(LibraryErrorCode.INCOMPLETE_KIT)
        val assets = sourceMappings.mapNotNull { dao.assetById(it.audioAssetId) }
        if (assets.size != sourceMappings.size || assets.any {
                it.status != AudioAssetStatuses.READY || !storage {
                    assetStore.assetExists(it.storageKey)
                }
            }
        ) {
            return failure(LibraryErrorCode.SOURCE_UNAVAILABLE)
        }
        val copiedMappings = sourceMappings.map { mapping ->
            mapping.copy(kitId = destination.id)
        }
        dao.insertCompleteKit(destination, emptyList(), copiedMappings)
        return LibraryResult.Success(destination.id)
    }

    private suspend fun copyBuiltInKit(
        source: Kit,
        destination: LibraryKitEntity
    ): LibraryResult<String> {
        val materializedByHash = linkedMapOf<String, MaterializedAsset>()
        return try {
            val mappings = DrumPad.entries.map { pad ->
                val sample = source.samples[pad]
                    ?: throw SourceFailure("Built-in kit is incomplete")
                val prepared = try {
                    audioImporter.prepare { builtInSampleSource.open(sample.rawResId) }
                } catch (import: AudioImportException) {
                    throw ImportFailure(import)
                } catch (sourceError: Exception) {
                    if (sourceError is CancellationException) throw sourceError
                    throw SourceFailure("Cannot read built-in sample", sourceError)
                }
                val materialized = materializedByHash[prepared.normalizedSha256]?.also {
                    if (!assetStore.discardStaging(prepared.stagingFile)) {
                        throw StorageFailure("Cannot discard duplicate staging file")
                    }
                } ?: materialize(
                    prepared = prepared,
                    originalName = "built-in-${sample.rawResId}.wav",
                    timestamp = nowMs()
                ).also { materializedByHash[prepared.normalizedSha256] = it }
                KitPadMappingEntity(destination.id, pad.name, materialized.entity.id)
            }
            val materialized = materializedByHash.values
            dao.insertCompleteKit(
                kit = destination,
                assets = materialized.mapNotNull(MaterializedAsset::insertedEntity),
                mappings = mappings,
                updatedAssets = materialized.mapNotNull(MaterializedAsset::updatedEntity)
            )
            LibraryResult.Success(destination.id)
        } catch (cancelled: CancellationException) {
            compensate(materializedByHash.values.mapNotNull(MaterializedAsset::createdFile))
            throw cancelled
        } catch (import: ImportFailure) {
            compensate(materializedByHash.values.mapNotNull(MaterializedAsset::createdFile))
            LibraryResult.Failure(
                LibraryError(
                    code = LibraryErrorCode.IMPORT_FAILED,
                    importCode = import.error.code,
                    wavValidationCode = import.error.wavValidationCode
                )
            )
        } catch (_: SourceFailure) {
            compensate(materializedByHash.values.mapNotNull(MaterializedAsset::createdFile))
            failure(LibraryErrorCode.SOURCE_UNAVAILABLE)
        } catch (_: StorageFailure) {
            compensate(materializedByHash.values.mapNotNull(MaterializedAsset::createdFile))
            failure(LibraryErrorCode.STORAGE_FAILURE)
        } catch (_: Exception) {
            val createdFiles = materializedByHash.values.mapNotNull(MaterializedAsset::createdFile)
            compensate(createdFiles)
            failure(
                LibraryErrorCode.DATABASE_FAILURE,
                recoveryRequired = createdFiles.any(File::exists)
            )
        }
    }

    private suspend fun materialize(
        prepared: PreparedAudioImport,
        originalName: String,
        timestamp: Long
    ): MaterializedAsset {
        val existing = dao.assetBySha256(prepared.normalizedSha256)
        if (existing != null && storage { assetStore.assetExists(existing.storageKey) }) {
            if (!assetStore.discardStaging(prepared.stagingFile)) {
                throw StorageFailure("Cannot discard deduplicated staging file")
            }
            val refreshed = if (existing.status == AudioAssetStatuses.READY) null else {
                prepared.toEntity(
                    id = existing.id,
                    originalName = originalName,
                    storageKey = existing.storageKey,
                    createdAtMs = existing.createdAtMs,
                    updatedAtMs = timestamp
                )
            }
            return MaterializedAsset(
                entity = refreshed ?: existing,
                updatedEntity = refreshed,
                existedBefore = true
            )
        }

        val storageKey = existing?.storageKey ?: prepared.storageKey
        if (existing == null && storage { assetStore.assetExists(storageKey) } &&
            !storage { assetStore.deleteAsset(storageKey) }
        ) {
            assetStore.discardStaging(prepared.stagingFile)
            throw StorageFailure("Cannot replace orphan asset")
        }
        val createdFile = try {
            assetStore.commit(prepared.stagingFile, storageKey)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            try {
                assetStore.discardStaging(prepared.stagingFile)
            } catch (_: Exception) {
                // The next reconcile pass owns an undeletable staging file.
            }
            throw StorageFailure("Cannot promote normalized audio", failure)
        }
        val entity = prepared.toEntity(
            id = existing?.id ?: newId(),
            originalName = originalName,
            storageKey = storageKey,
            createdAtMs = existing?.createdAtMs ?: timestamp,
            updatedAtMs = timestamp
        )
        return MaterializedAsset(
            entity = entity,
            insertedEntity = entity.takeIf { existing == null },
            updatedEntity = entity.takeIf { existing != null },
            createdFile = createdFile,
            existedBefore = existing != null
        )
    }

    private suspend fun cleanupPendingAsset(asset: AudioAssetEntity): CleanupOutcome {
        if (dao.assetReferenceCount(asset.id) != 0) return CleanupOutcome.REFERENCED
        if (!assetStore.deleteAsset(asset.storageKey)) return CleanupOutcome.FAILED
        return if (dao.deleteAssetIfUnreferenced(asset.id)) {
            CleanupOutcome.DELETED
        } else {
            CleanupOutcome.REFERENCED
        }
    }

    private suspend fun cleanupPendingAssetSafely(asset: AudioAssetEntity): CleanupOutcome = try {
        cleanupPendingAsset(asset)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        CleanupOutcome.FAILED
    }

    private suspend fun isCompleteKit(kitId: String): Boolean =
        hasEveryPad(dao.mappingsForKit(kitId))

    private fun hasEveryPad(mappings: List<KitPadMappingEntity>): Boolean =
        mappings.size == DrumPad.entries.size &&
            mappings.map(KitPadMappingEntity::pad).toSet() == DrumPad.entries.map { it.name }.toSet()

    private suspend fun nextDisplayOrder(): Int =
        ((dao.maximumDisplayOrder()?.toLong() ?: -1L) + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private suspend fun <T> mutate(block: suspend () -> LibraryResult<T>): LibraryResult<T> =
        withContext(ioDispatcher) {
            mutationMutex.withLock {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: StorageFailure) {
                    failure(LibraryErrorCode.STORAGE_FAILURE)
                } catch (_: Exception) {
                    failure(LibraryErrorCode.DATABASE_FAILURE)
                }
            }
        }

    private fun compensate(file: File?) {
        if (file == null) return
        try {
            assetStore.deleteAsset(file.name)
        } catch (_: Exception) {
            // reconcile() owns any final asset that compensation cannot remove.
        }
    }

    private fun compensate(files: Collection<File>) {
        files.forEach(::compensate)
    }

    private data class MaterializedAsset(
        val entity: AudioAssetEntity,
        val insertedEntity: AudioAssetEntity? = null,
        val updatedEntity: AudioAssetEntity? = null,
        val createdFile: File? = null,
        val existedBefore: Boolean
    )

    private enum class CleanupOutcome { DELETED, FAILED, REFERENCED }

    private class StorageFailure(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private class SourceFailure(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private class ImportFailure(val error: AudioImportException) : Exception(error)

    private inline fun <T> storage(action: () -> T): T = try {
        action()
    } catch (failure: Exception) {
        throw StorageFailure("Managed audio storage operation failed", failure)
    }

    companion object {
        private const val MAX_KIT_NAME_LENGTH = 40
        private const val MAX_ORIGINAL_NAME_LENGTH = 120
        private const val STAGING_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
        private const val STORAGE_VERSION = 1
        private const val VALIDATION_VERSION = 1

        fun open(context: Context): RoomKitLibrary {
            val appContext = context.applicationContext
            val assetStore = AudioAssetStore(appContext)
            return RoomKitLibrary(
                database = KitDatabase.open(appContext),
                assetStore = assetStore,
                audioImporter = AudioImporter(assetStore),
                builtInSampleSource = BuiltInSampleSource(appContext.resources::openRawResource)
            )
        }

        private fun normalizeName(name: String): String? = name
            .trim()
            .takeIf { it.isNotEmpty() && it.length <= MAX_KIT_NAME_LENGTH && it.none(Char::isISOControl) }

        private fun normalizeOriginalName(name: String): String = name
            .filterNot(Char::isISOControl)
            .trim()
            .take(MAX_ORIGINAL_NAME_LENGTH)
            .ifEmpty { "sample.wav" }

        private fun PreparedAudioImport.toEntity(
            id: String,
            originalName: String,
            storageKey: String,
            createdAtMs: Long,
            updatedAtMs: Long
        ) = AudioAssetEntity(
            id = id,
            storageKey = storageKey,
            originalName = originalName,
            sha256 = normalizedSha256,
            byteSize = normalizedByteSize,
            frameCount = frameCount,
            sampleRate = sampleRate,
            channelCount = channelCount,
            encoding = encoding,
            storageVersion = STORAGE_VERSION,
            validationVersion = VALIDATION_VERSION,
            status = AudioAssetStatuses.READY,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs
        )

        private fun <T> failure(
            code: LibraryErrorCode,
            recoveryRequired: Boolean = false
        ): LibraryResult<T> = LibraryResult.Failure(
            LibraryError(code = code, recoveryRequired = recoveryRequired)
        )
    }
}
