package com.electrodig.voidmusic.audio

import kotlin.math.roundToInt

/** Stable validation categories that UI-facing import errors can map to localized text. */
internal enum class WavValidationCode {
    TOO_SHORT,
    NOT_RIFF,
    NOT_WAVE,
    INVALID_RIFF_SIZE,
    TRUNCATED_CHUNK,
    MISSING_FMT,
    MISSING_DATA,
    DUPLICATE_FMT,
    DUPLICATE_DATA,
    UNSUPPORTED_ENCODING,
    UNSUPPORTED_CHANNELS,
    UNSUPPORTED_BITS,
    UNSUPPORTED_SAMPLE_RATE,
    INVALID_BLOCK_ALIGN,
    INVALID_BYTE_RATE,
    EMPTY_DATA,
    MISALIGNED_DATA,
    TOO_LONG
}

internal class WavValidationException(
    val code: WavValidationCode,
    message: String
) : IllegalArgumentException(message)

/** Validated offsets and PCM metadata shared by inspection, decoding, and normalization. */
internal data class Pcm16Wav(
    val sampleRate: Int,
    val channelCount: Int,
    val frameCount: Int,
    val dataOffset: Int,
    val dataByteCount: Int
)

internal data class NormalizedWav(
    val bytes: ByteArray,
    val frameCount: Int,
    val sampleRate: Int
)

/** Strict RIFF/WAVE PCM16 parser and mono normalizer. */
internal object WavDecoder {
    const val MIN_SAMPLE_RATE = 8_000
    const val MAX_SAMPLE_RATE = 96_000
    const val NORMALIZED_SAMPLE_RATE = 48_000
    const val MAX_DURATION_SECONDS = 5

    fun inspect(data: ByteArray, maxDurationSeconds: Int? = null): Pcm16Wav {
        if (data.size < RIFF_HEADER_SIZE) fail(WavValidationCode.TOO_SHORT, "WAV header is truncated")
        if (!data.hasAscii(0, "RIFF")) fail(WavValidationCode.NOT_RIFF, "Not a RIFF file")
        if (!data.hasAscii(8, "WAVE")) fail(WavValidationCode.NOT_WAVE, "RIFF form is not WAVE")

        val declaredRiffSize = data.leU32(4)
        val riffEnd = declaredRiffSize + 8L
        if (declaredRiffSize < 4L || riffEnd > data.size.toLong()) {
            fail(WavValidationCode.INVALID_RIFF_SIZE, "RIFF size exceeds available bytes")
        }

        var position = RIFF_HEADER_SIZE
        var format: FormatChunk? = null
        var dataOffset = -1
        var dataByteCount = -1
        while (position.toLong() < riffEnd) {
            if (position.toLong() + CHUNK_HEADER_SIZE > riffEnd) {
                fail(WavValidationCode.TRUNCATED_CHUNK, "Chunk header is truncated")
            }
            val chunkSize = data.leU32(position + 4)
            val contentStart = position.toLong() + CHUNK_HEADER_SIZE
            val contentEnd = contentStart + chunkSize
            val paddedEnd = contentEnd + (chunkSize and 1L)
            if (contentEnd > riffEnd || paddedEnd > riffEnd || contentEnd > Int.MAX_VALUE) {
                fail(WavValidationCode.TRUNCATED_CHUNK, "Chunk exceeds RIFF bounds")
            }

            when {
                data.hasAscii(position, "fmt ") -> {
                    if (format != null) fail(WavValidationCode.DUPLICATE_FMT, "Duplicate fmt chunk")
                    format = parseFormat(data, contentStart.toInt(), chunkSize)
                }
                data.hasAscii(position, "data") -> {
                    if (dataOffset >= 0) fail(WavValidationCode.DUPLICATE_DATA, "Duplicate data chunk")
                    dataOffset = contentStart.toInt()
                    dataByteCount = chunkSize.toInt()
                }
            }
            position = paddedEnd.toInt()
        }

        val pcmFormat = format ?: fail(WavValidationCode.MISSING_FMT, "Missing fmt chunk")
        if (dataOffset < 0) fail(WavValidationCode.MISSING_DATA, "Missing data chunk")
        if (dataByteCount == 0) fail(WavValidationCode.EMPTY_DATA, "Audio data is empty")
        if (dataByteCount % pcmFormat.blockAlign != 0) {
            fail(WavValidationCode.MISALIGNED_DATA, "Audio data is not frame aligned")
        }

        val frameCount = dataByteCount / pcmFormat.blockAlign
        if (maxDurationSeconds != null) {
            require(maxDurationSeconds > 0) { "Maximum duration must be positive" }
            if (frameCount.toLong() > pcmFormat.sampleRate.toLong() * maxDurationSeconds) {
                fail(WavValidationCode.TOO_LONG, "Audio exceeds $maxDurationSeconds seconds")
            }
        }
        return Pcm16Wav(
            sampleRate = pcmFormat.sampleRate,
            channelCount = pcmFormat.channelCount,
            frameCount = frameCount,
            dataOffset = dataOffset,
            dataByteCount = dataByteCount
        )
    }

    fun toMonoFloats(data: ByteArray): FloatArray {
        val samples = decodeMonoPcm16(data, inspect(data))
        return FloatArray(samples.size) { samples[it] / 32768f }
    }

    fun normalizeToMonoPcm16Wav(
        data: ByteArray,
        targetSampleRate: Int = NORMALIZED_SAMPLE_RATE,
        maxDurationSeconds: Int = MAX_DURATION_SECONDS
    ): NormalizedWav {
        require(targetSampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE) {
            "Target sample rate is unsupported"
        }
        val source = inspect(data, maxDurationSeconds)
        val mono = decodeMonoPcm16(data, source)
        val normalizedSamples = resampleLinear(mono, source.sampleRate, targetSampleRate)
        return NormalizedWav(
            bytes = encodeMonoPcm16(normalizedSamples, targetSampleRate),
            frameCount = normalizedSamples.size,
            sampleRate = targetSampleRate
        )
    }

    private fun parseFormat(data: ByteArray, offset: Int, chunkSize: Long): FormatChunk {
        if (chunkSize < PCM_FMT_SIZE) {
            fail(WavValidationCode.TRUNCATED_CHUNK, "fmt chunk is shorter than PCM metadata")
        }
        val encoding = data.leU16(offset)
        if (encoding != PCM_ENCODING) {
            fail(WavValidationCode.UNSUPPORTED_ENCODING, "Only integer PCM is supported")
        }
        val channelCount = data.leU16(offset + 2)
        if (channelCount !in 1..2) {
            fail(WavValidationCode.UNSUPPORTED_CHANNELS, "Only mono and stereo are supported")
        }
        val sampleRateLong = data.leU32(offset + 4)
        if (sampleRateLong !in MIN_SAMPLE_RATE.toLong()..MAX_SAMPLE_RATE.toLong()) {
            fail(WavValidationCode.UNSUPPORTED_SAMPLE_RATE, "Sample rate is unsupported")
        }
        val bitsPerSample = data.leU16(offset + 14)
        if (bitsPerSample != BITS_PER_SAMPLE) {
            fail(WavValidationCode.UNSUPPORTED_BITS, "Only 16-bit PCM is supported")
        }

        val expectedBlockAlign = channelCount * BYTES_PER_SAMPLE
        val blockAlign = data.leU16(offset + 12)
        if (blockAlign != expectedBlockAlign) {
            fail(WavValidationCode.INVALID_BLOCK_ALIGN, "Block alignment does not match PCM format")
        }
        val expectedByteRate = sampleRateLong * expectedBlockAlign
        if (data.leU32(offset + 8) != expectedByteRate) {
            fail(WavValidationCode.INVALID_BYTE_RATE, "Byte rate does not match PCM format")
        }
        return FormatChunk(sampleRateLong.toInt(), channelCount, expectedBlockAlign)
    }

    private fun decodeMonoPcm16(data: ByteArray, wav: Pcm16Wav): ShortArray {
        val output = ShortArray(wav.frameCount)
        var offset = wav.dataOffset
        repeat(wav.frameCount) { frame ->
            val left = data.leI16(offset)
            output[frame] = if (wav.channelCount == 1) {
                left.toShort()
            } else {
                val right = data.leI16(offset + BYTES_PER_SAMPLE)
                ((left + right) / 2).toShort()
            }
            offset += wav.channelCount * BYTES_PER_SAMPLE
        }
        return output
    }

    private fun resampleLinear(samples: ShortArray, sourceRate: Int, targetRate: Int): ShortArray {
        if (sourceRate == targetRate) return samples.copyOf()
        val outputSize = ((samples.size.toLong() * targetRate + sourceRate / 2L) / sourceRate)
            .coerceAtLeast(1L)
            .toInt()
        val output = ShortArray(outputSize)
        val sourceStep = sourceRate.toDouble() / targetRate
        repeat(outputSize) { outputIndex ->
            val sourcePosition = outputIndex * sourceStep
            val lower = sourcePosition.toInt().coerceAtMost(samples.lastIndex)
            val upper = (lower + 1).coerceAtMost(samples.lastIndex)
            val fraction = sourcePosition - lower
            output[outputIndex] = (
                samples[lower] + (samples[upper] - samples[lower]) * fraction
                ).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }

    private fun encodeMonoPcm16(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * BYTES_PER_SAMPLE
        val output = ByteArray(STANDARD_WAV_HEADER_SIZE + dataSize)
        output.putAscii(0, "RIFF")
        output.putU32(4, output.size - 8)
        output.putAscii(8, "WAVE")
        output.putAscii(12, "fmt ")
        output.putU32(16, PCM_FMT_SIZE)
        output.putU16(20, PCM_ENCODING)
        output.putU16(22, 1)
        output.putU32(24, sampleRate)
        output.putU32(28, sampleRate * BYTES_PER_SAMPLE)
        output.putU16(32, BYTES_PER_SAMPLE)
        output.putU16(34, BITS_PER_SAMPLE)
        output.putAscii(36, "data")
        output.putU32(40, dataSize)
        samples.forEachIndexed { index, sample ->
            output.putU16(STANDARD_WAV_HEADER_SIZE + index * BYTES_PER_SAMPLE, sample.toInt())
        }
        return output
    }

    private fun fail(code: WavValidationCode, message: String): Nothing =
        throw WavValidationException(code, message)

    private data class FormatChunk(
        val sampleRate: Int,
        val channelCount: Int,
        val blockAlign: Int
    )

    private const val RIFF_HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private const val PCM_FMT_SIZE = 16
    private const val STANDARD_WAV_HEADER_SIZE = 44
    private const val PCM_ENCODING = 1
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2
}

private fun ByteArray.hasAscii(offset: Int, value: String): Boolean =
    offset >= 0 && offset + value.length <= size && value.indices.all {
        this[offset + it] == value[it].code.toByte()
    }

private fun ByteArray.leU16(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

private fun ByteArray.leI16(offset: Int): Int {
    val unsigned = leU16(offset)
    return if (unsigned and 0x8000 == 0) unsigned else unsigned - 0x10000
}

private fun ByteArray.leU32(offset: Int): Long =
    leU16(offset).toLong() or (leU16(offset + 2).toLong() shl 16)

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
