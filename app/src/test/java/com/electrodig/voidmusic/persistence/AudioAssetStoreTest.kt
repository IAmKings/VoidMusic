package com.electrodig.voidmusic.persistence

import java.io.File
import org.junit.Assert.assertArrayEquals
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
}
