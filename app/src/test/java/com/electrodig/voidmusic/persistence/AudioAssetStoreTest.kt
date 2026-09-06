package com.electrodig.voidmusic.persistence

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioAssetStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `commit atomically promotes managed staging file`() {
        val store = AudioAssetStore(temporaryFolder.root)
        val staging = store.createStagingFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val key = "a".repeat(64) + ".wav"

        val committed = store.commit(staging, key)

        assertFalse(staging.exists())
        assertTrue(committed.isFile)
        assertArrayEquals(byteArrayOf(1, 2, 3), committed.readBytes())
        assertTrue(committed.canonicalPath.startsWith(temporaryFolder.root.canonicalPath))
    }

    @Test
    fun `storage key rejects traversal absolute paths and malformed hashes`() {
        val store = AudioAssetStore(temporaryFolder.root)

        listOf("../sample.wav", "/sample.wav", "sample.wav", "A".repeat(64) + ".wav").forEach { key ->
            assertThrows(IllegalArgumentException::class.java) { store.resolveAsset(key) }
        }
    }

    @Test
    fun `commit and discard reject files outside managed staging directory`() {
        val store = AudioAssetStore(temporaryFolder.root)
        val outside = File(temporaryFolder.root, "outside.tmp").apply { writeText("audio") }
        val key = "b".repeat(64) + ".wav"

        assertThrows(IllegalArgumentException::class.java) { store.commit(outside, key) }
        assertThrows(IllegalArgumentException::class.java) { store.discardStaging(outside) }
        assertTrue(outside.exists())
    }

    @Test
    fun `commit refuses to replace an existing asset`() {
        val store = AudioAssetStore(temporaryFolder.root)
        val key = "c".repeat(64) + ".wav"
        store.commit(store.createStagingFile().apply { writeText("first") }, key)
        val second = store.createStagingFile().apply { writeText("second") }

        assertThrows(IllegalArgumentException::class.java) { store.commit(second, key) }
        assertTrue(second.exists())
    }

    @Test
    fun `managed assets can be listed and deletion is idempotent`() {
        val store = AudioAssetStore(temporaryFolder.root)
        val firstKey = "d".repeat(64) + ".wav"
        val secondKey = "e".repeat(64) + ".wav"
        store.commit(store.createStagingFile().apply { writeText("first") }, firstKey)
        store.commit(store.createStagingFile().apply { writeText("second") }, secondKey)

        assertEquals(setOf(firstKey, secondKey), store.assetStorageKeys())
        assertTrue(store.deleteAsset(firstKey))
        assertTrue(store.deleteAsset(firstKey))
        assertEquals(setOf(secondKey), store.assetStorageKeys())
    }

    @Test
    fun `staging cleanup deletes only files at or before cutoff`() {
        val store = AudioAssetStore(temporaryFolder.root)
        val old = store.createStagingFile().apply { setLastModified(100) }
        val boundary = store.createStagingFile().apply { setLastModified(200) }
        val recent = store.createStagingFile().apply { setLastModified(201) }

        val report = store.cleanupStagingOlderThan(200)

        assertEquals(CleanupReport(deleted = 2, failed = 0), report)
        assertFalse(old.exists())
        assertFalse(boundary.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `commit rejects missing staging file`() {
        val store = AudioAssetStore(temporaryFolder.root)
        val missing = store.createStagingFile().also { assertTrue(it.delete()) }

        assertThrows(IllegalArgumentException::class.java) {
            store.commit(missing, "f".repeat(64) + ".wav")
        }
    }
}
