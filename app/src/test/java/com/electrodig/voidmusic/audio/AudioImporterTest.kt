package com.electrodig.voidmusic.audio

import com.electrodig.voidmusic.persistence.AudioAssetStore
import com.electrodig.voidmusic.persistence.AudioImportException
import com.electrodig.voidmusic.persistence.AudioImporter
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioImporterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val store by lazy { AudioAssetStore(temporaryFolder.root) }
    private val importer by lazy { AudioImporter(store, targetSampleRate = 16_000) }

    @Test
    fun `successful prepare hashes source and leaves only normalized staging`() {
        val source = wav(shortArrayOf(0, 10_000, 20_000, 30_000), channels = 2, sampleRate = 8_000)

        val prepared = importer.prepare { source.inputStream() }
        val normalized = WavDecoder.inspect(prepared.stagingFile.readBytes())

        assertEquals(sha256(source), prepared.sourceSha256)
        assertEquals(sha256(prepared.stagingFile.readBytes()), prepared.normalizedSha256)
        assertEquals(prepared.normalizedSha256 + ".wav", prepared.storageKey)
        assertEquals(source.size.toLong(), prepared.sourceByteSize)
        assertEquals(16_000, normalized.sampleRate)
        assertEquals(1, normalized.channelCount)
        assertEquals(4, normalized.frameCount)
        assertEquals(listOf(prepared.stagingFile.name), stagingFiles().map { it.name })

        assertTrue(store.discardStaging(prepared.stagingFile))
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `source larger than ten mebibytes stops and cleans staging`() {
        val oversized = object : InputStream() {
            var remaining = AudioImporter.MAX_SOURCE_BYTES + 1

            override fun read(): Int = if (remaining-- > 0) 0 else -1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                buffer.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }

        val error = assertThrows(AudioImportException::class.java) {
            importer.prepare { oversized }
        }

        assertEquals(AudioImportErrorCode.TOO_LARGE, error.code)
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `invalid and over-duration wavs expose stable errors and clean staging`() {
        val invalid = assertThrows(AudioImportException::class.java) {
            importer.prepare { ByteArrayInputStream(ByteArray(16)) }
        }
        assertEquals(AudioImportErrorCode.INVALID_WAV, invalid.code)
        assertEquals(WavValidationCode.NOT_RIFF, invalid.wavValidationCode)
        assertTrue(stagingFiles().isEmpty())

        val tooLong = wav(
            ShortArray(8_000 * WavDecoder.MAX_DURATION_SECONDS + 1),
            sampleRate = 8_000
        )
        val durationError = assertThrows(AudioImportException::class.java) {
            AudioImporter(store, targetSampleRate = 16_000).prepare { tooLong.inputStream() }
        }
        assertEquals(AudioImportErrorCode.INVALID_WAV, durationError.code)
        assertEquals(WavValidationCode.TOO_LONG, durationError.wavValidationCode)
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `source read failure is stable and cleans staging`() {
        val source = object : InputStream() {
            override fun read(): Int = throw IOException("provider unavailable")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw IOException("provider unavailable")
        }

        val error = assertThrows(AudioImportException::class.java) {
            importer.prepare { source }
        }

        assertEquals(AudioImportErrorCode.IO_FAILURE, error.code)
        assertTrue(stagingFiles().isEmpty())
    }

    @Test
    fun `failed prepare never creates a final asset`() {
        assertThrows(AudioImportException::class.java) {
            importer.prepare { ByteArrayInputStream(ByteArray(16)) }
        }

        val assetDirectory = temporaryFolder.root.resolve("audio-assets")
        assertTrue(assetDirectory.listFiles().isNullOrEmpty())
        assertFalse(temporaryFolder.root.resolve("outside.wav").exists())
    }

    private fun stagingFiles() = temporaryFolder.root
        .resolve("audio-import-staging")
        .listFiles()
        ?.toList()
        .orEmpty()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun wav(samples: ShortArray, channels: Int = 1, sampleRate: Int = 44_100): ByteArray {
        val dataSize = samples.size * 2
        return ByteArray(44 + dataSize).apply {
            putAscii(0, "RIFF")
            putU32(4, size - 8)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putU32(16, 16)
            putU16(20, 1)
            putU16(22, channels)
            putU32(24, sampleRate)
            putU32(28, sampleRate * channels * 2)
            putU16(32, channels * 2)
            putU16(34, 16)
            putAscii(36, "data")
            putU32(40, dataSize)
            samples.forEachIndexed { index, sample -> putU16(44 + index * 2, sample.toInt()) }
        }
    }

    private fun ByteArray.putAscii(offset: Int, value: String) {
        value.forEachIndexed { index, character -> this[offset + index] = character.code.toByte() }
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.putU32(offset: Int, value: Int) {
        putU16(offset, value)
        putU16(offset + 2, value ushr 16)
    }
}
