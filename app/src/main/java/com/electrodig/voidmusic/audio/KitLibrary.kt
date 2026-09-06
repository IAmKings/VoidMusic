package com.electrodig.voidmusic.audio

import com.electrodig.voidmusic.detection.color.DrumPad
import java.io.InputStream
import kotlinx.coroutines.flow.Flow

/** Stable, storage-agnostic item rendered by kit selection UI. */
data class KitSummary(
    val id: String,
    val name: String,
    val isBuiltIn: Boolean
)

enum class AudioImportErrorCode {
    TOO_LARGE,
    INVALID_WAV,
    IO_FAILURE
}

enum class LibraryErrorCode {
    INVALID_NAME,
    KIT_NOT_FOUND,
    BUILT_IN_IMMUTABLE,
    INCOMPLETE_KIT,
    SOURCE_UNAVAILABLE,
    IMPORT_FAILED,
    STORAGE_FAILURE,
    DATABASE_FAILURE
}

data class LibraryError(
    val code: LibraryErrorCode,
    val importCode: AudioImportErrorCode? = null,
    val wavValidationCode: WavValidationCode? = null,
    val recoveryRequired: Boolean = false
)

sealed interface LibraryResult<out T> {
    data class Success<T>(val value: T) : LibraryResult<T>
    data class Failure(val error: LibraryError) : LibraryResult<Nothing>
}

data class ReplacePadReport(
    val reusedExistingAsset: Boolean,
    val pendingCleanupCount: Int
)

data class DeleteKitReport(
    val kitId: String,
    val deletedAssetCount: Int,
    val pendingAssetCount: Int
)

data class ReconcileReport(
    val deletedStagingCount: Int,
    val failedStagingDeleteCount: Int,
    val deletedPendingAssetCount: Int,
    val pendingAssetFailureCount: Int,
    val brokenAssetCount: Int,
    val deletedOrphanFileCount: Int,
    val orphanFileFailureCount: Int
)

/** Business boundary for the merged built-in and imported kit catalogue. */
interface KitLibrary {
    val kits: Flow<List<KitSummary>>

    suspend fun copyKit(sourceId: String, name: String): LibraryResult<String>

    suspend fun replacePad(
        kitId: String,
        pad: DrumPad,
        originalName: String,
        openInput: () -> InputStream
    ): LibraryResult<ReplacePadReport>

    suspend fun renameKit(kitId: String, name: String): LibraryResult<Unit>

    /** Reads, validates and decodes every pad on a background dispatcher. */
    suspend fun prepare(kitId: String): LibraryResult<PreparedKit>

    suspend fun deleteKit(kitId: String): LibraryResult<DeleteKitReport>

    suspend fun reconcile(): LibraryResult<ReconcileReport>
}
