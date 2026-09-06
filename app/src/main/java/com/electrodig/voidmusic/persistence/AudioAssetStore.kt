package com.electrodig.voidmusic.persistence

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Owns the only filesystem paths used by imported audio assets. */
class AudioAssetStore internal constructor(private val root: File) {
    private val stagingDirectory = File(root, STAGING_DIRECTORY)
    private val assetDirectory = File(root, ASSET_DIRECTORY)

    constructor(context: Context) : this(context.applicationContext.filesDir)

    /** Creates an empty, uniquely named file inside the managed staging directory. */
    fun createStagingFile(): File {
        ensureDirectories()
        return File.createTempFile("import-${UUID.randomUUID()}-", ".tmp", stagingDirectory)
    }

    /** Resolves a database storage key without allowing absolute paths or traversal. */
    fun resolveAsset(storageKey: String): File {
        require(STORAGE_KEY.matches(storageKey)) { "Invalid audio storage key" }
        ensureDirectories()
        return File(assetDirectory, storageKey)
    }

    /**
     * Atomically promotes a managed staging file into the asset directory.
     * The directories share filesDir, so lack of atomic-move support is an error.
     */
    fun commit(stagingFile: File, storageKey: String): File {
        ensureManagedStagingFile(stagingFile)
        val target = resolveAsset(storageKey)
        require(!target.exists()) { "Audio asset already exists" }
        try {
            Files.move(
                stagingFile.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (unsupported: AtomicMoveNotSupportedException) {
            throw IllegalStateException("Atomic audio asset move is unavailable", unsupported)
        }
        return target
    }

    /** Deletes only a file owned by the staging directory. */
    fun discardStaging(stagingFile: File): Boolean {
        ensureManagedStagingFile(stagingFile)
        return !stagingFile.exists() || stagingFile.delete()
    }

    private fun ensureManagedStagingFile(file: File) {
        ensureDirectories()
        require(file.canonicalFile.parentFile == stagingDirectory.canonicalFile) {
            "File is outside the audio import staging directory"
        }
    }

    private fun ensureDirectories() {
        check(stagingDirectory.isDirectory || stagingDirectory.mkdirs()) {
            "Cannot create audio import staging directory"
        }
        check(assetDirectory.isDirectory || assetDirectory.mkdirs()) {
            "Cannot create audio asset directory"
        }
    }

    companion object {
        private const val STAGING_DIRECTORY = "audio-import-staging"
        private const val ASSET_DIRECTORY = "audio-assets"
        private val STORAGE_KEY = Regex("[a-f0-9]{64}\\.wav")
    }
}
