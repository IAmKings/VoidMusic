package com.electrodig.voidmusic.audio

import com.electrodig.voidmusic.persistence.AudioAssetStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal enum class AudioImportErrorCode {
    TOO_LARGE,
    INVALID_WAV,
    IO_FAILURE
}

internal class AudioImportException(
    val code: AudioImportErrorCode,
    val wavValidationCode: WavValidationCode? = null,
    cause: Throwable? = null
) : Exception(code.name, cause)

/** A validated normalized file that has not yet crossed the final asset boundary. */
internal data class PreparedAudioImport(
    val stagingFile: File,
    val storageKey: String,
    val sourceSha256: String,
    val normalizedSha256: String,
    val sourceByteSize: Long,
    val normalizedByteSize: Long,
    val frameCount: Long,
    val sampleRate: Int,
    val channelCount: Int = 1,
    val encoding: String = "PCM_16"
)

/** Streams untrusted input into managed staging and emits a strict normalized WAV. */
internal class AudioImporter(
    private val assetStore: AudioAssetStore,
    private val targetSampleRate: Int = WavDecoder.NORMALIZED_SAMPLE_RATE
) {
    fun prepare(openInput: () -> InputStream): PreparedAudioImport {
        val sourceStaging = try {
            assetStore.createStagingFile()
        } catch (io: IOException) {
            throw AudioImportException(AudioImportErrorCode.IO_FAILURE, cause = io)
        }
        var normalizedStaging: File? = null
        var completed = false
        try {
            val copied = copyAndHash(openInput, sourceStaging)
            val normalized = try {
                WavDecoder.normalizeToMonoPcm16Wav(
                    data = sourceStaging.readBytes(),
                    targetSampleRate = targetSampleRate,
                    maxDurationSeconds = WavDecoder.MAX_DURATION_SECONDS
                )
            } catch (validation: WavValidationException) {
                throw AudioImportException(
                    code = AudioImportErrorCode.INVALID_WAV,
                    wavValidationCode = validation.code,
                    cause = validation
                )
            }

            normalizedStaging = assetStore.createStagingFile()
            normalizedStaging.outputStream().buffered().use { output ->
                output.write(normalized.bytes)
            }
            val normalizedHash = MessageDigest.getInstance(SHA_256)
                .digest(normalized.bytes)
                .toHex()
            if (!assetStore.discardStaging(sourceStaging)) {
                throw AudioImportException(
                    AudioImportErrorCode.IO_FAILURE,
                    cause = IOException("Cannot remove source staging file")
                )
            }
            completed = true
            return PreparedAudioImport(
                stagingFile = normalizedStaging,
                storageKey = "$normalizedHash.wav",
                sourceSha256 = copied.sha256,
                normalizedSha256 = normalizedHash,
                sourceByteSize = copied.byteCount,
                normalizedByteSize = normalized.bytes.size.toLong(),
                frameCount = normalized.frameCount.toLong(),
                sampleRate = normalized.sampleRate
            )
        } catch (known: AudioImportException) {
            throw known
        } catch (io: IOException) {
            throw AudioImportException(AudioImportErrorCode.IO_FAILURE, cause = io)
        } catch (denied: SecurityException) {
            throw AudioImportException(AudioImportErrorCode.IO_FAILURE, cause = denied)
        } finally {
            runCatching { assetStore.discardStaging(sourceStaging) }
            if (!completed) normalizedStaging?.let { file ->
                runCatching { assetStore.discardStaging(file) }
            }
        }
    }

    private fun copyAndHash(openInput: () -> InputStream, target: File): CopiedInput {
        val digest = MessageDigest.getInstance(SHA_256)
        var byteCount = 0L
        openInput().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) {
                        val singleByte = input.read()
                        if (singleByte < 0) break
                        byteCount += 1
                        if (byteCount > MAX_SOURCE_BYTES) {
                            throw AudioImportException(AudioImportErrorCode.TOO_LARGE)
                        }
                        output.write(singleByte)
                        digest.update(singleByte.toByte())
                        continue
                    }
                    byteCount += read
                    if (byteCount > MAX_SOURCE_BYTES) {
                        throw AudioImportException(AudioImportErrorCode.TOO_LARGE)
                    }
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
            }
        }
        return CopiedInput(byteCount, digest.digest().toHex())
    }

    private data class CopiedInput(val byteCount: Long, val sha256: String)

    companion object {
        const val MAX_SOURCE_BYTES = 10L * 1024L * 1024L
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val SHA_256 = "SHA-256"
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
