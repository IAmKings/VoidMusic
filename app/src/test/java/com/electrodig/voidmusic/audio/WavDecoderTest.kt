package com.electrodig.voidmusic.audio

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class WavDecoderTest {
    @Test
    fun `decodes mono wav to floats scaled to minus one to one`() {
        val samples = shortArrayOf(0, 16384, 32767, -16384, -32768)

        val floats = WavDecoder.toMonoFloats(wav(samples))

        assertEquals(samples.size, floats.size)
        assertEquals(0f, floats[0], 1e-3f)
        assertEquals(0.5f, floats[1], 0.01f)
        assertEquals(1.0f, floats[2], 1e-3f)
        assertEquals(-0.5f, floats[3], 0.01f)
        assertEquals(-1.0f, floats[4], 1e-3f)
    }

    @Test
    fun `decodes stereo by averaging without overflowing`() {
        val samples = shortArrayOf(32767, 32767, -32768, -32768, 32767, -32768)

        val floats = WavDecoder.toMonoFloats(wav(samples, channels = 2))

        assertEquals(3, floats.size)
        assertEquals(32767f / 32768f, floats[0], 1e-5f)
        assertEquals(-1f, floats[1], 1e-5f)
        assertEquals(0f, floats[2], 1e-5f)
    }

    @Test
    fun `unknown odd-sized chunk and padding are skipped`() {
        val bytes = riff(
            chunk("JUNK", byteArrayOf(1, 2, 3)),
            chunk("fmt ", format()),
            chunk("data", pcm(shortArrayOf(100, -100)))
        )

        val inspected = WavDecoder.inspect(bytes)

        assertEquals(2, inspected.frameCount)
        assertEquals(1, inspected.channelCount)
    }

    @Test
    fun `missing padding after odd chunk is rejected`() {
        val bytes = riff(
            chunk("fmt ", format()),
            chunk("JUNK", byteArrayOf(1), includePadding = false)
        )

        assertCode(WavValidationCode.TRUNCATED_CHUNK) { WavDecoder.inspect(bytes) }
    }

    @Test
    fun `truncated riff and forged chunk lengths are rejected`() {
        val truncatedRiff = wav(shortArrayOf(1)).copyOfRange(0, 20)
        val forgedRiff = wav(shortArrayOf(1)).apply { putU32(4, size + 100) }
        val forgedData = wav(shortArrayOf(1)).apply { putU32(40, 1_000) }

        assertCode(WavValidationCode.INVALID_RIFF_SIZE) { WavDecoder.inspect(truncatedRiff) }
        assertCode(WavValidationCode.INVALID_RIFF_SIZE) { WavDecoder.inspect(forgedRiff) }
        assertCode(WavValidationCode.TRUNCATED_CHUNK) { WavDecoder.inspect(forgedData) }
    }

    @Test
    fun `riff and wave signatures are both required`() {
        assertCode(WavValidationCode.TOO_SHORT) { WavDecoder.inspect(ByteArray(11)) }
        assertCode(WavValidationCode.NOT_RIFF) { WavDecoder.inspect(ByteArray(12)) }
        val notWave = wav(shortArrayOf(1)).apply { putAscii(8, "NOPE") }
        assertCode(WavValidationCode.NOT_WAVE) { WavDecoder.inspect(notWave) }
    }

    @Test
    fun `fmt and data chunks are both required and unique`() {
        val onlyData = riff(chunk("data", pcm(shortArrayOf(1))))
        val onlyFormat = riff(chunk("fmt ", format()))
        val duplicateFormat = riff(
            chunk("fmt ", format()),
            chunk("fmt ", format()),
            chunk("data", pcm(shortArrayOf(1)))
        )
        val duplicateData = riff(
            chunk("fmt ", format()),
            chunk("data", pcm(shortArrayOf(1))),
            chunk("data", pcm(shortArrayOf(2)))
        )

        assertCode(WavValidationCode.MISSING_FMT) { WavDecoder.inspect(onlyData) }
        assertCode(WavValidationCode.MISSING_DATA) { WavDecoder.inspect(onlyFormat) }
        assertCode(WavValidationCode.DUPLICATE_FMT) { WavDecoder.inspect(duplicateFormat) }
        assertCode(WavValidationCode.DUPLICATE_DATA) { WavDecoder.inspect(duplicateData) }
    }

    @Test
    fun `encoding channel and bit depth constraints are enforced`() {
        assertCode(WavValidationCode.UNSUPPORTED_ENCODING) {
            WavDecoder.inspect(wav(shortArrayOf(1), encoding = 3))
        }
        assertCode(WavValidationCode.UNSUPPORTED_CHANNELS) {
            WavDecoder.inspect(wav(shortArrayOf(1, 2, 3), channels = 3))
        }
        assertCode(WavValidationCode.UNSUPPORTED_BITS) {
            WavDecoder.inspect(wav(shortArrayOf(1), bitsPerSample = 8))
        }
    }

    @Test
    fun `sample rate boundaries are enforced`() {
        assertEquals(8_000, WavDecoder.inspect(wav(shortArrayOf(1), sampleRate = 8_000)).sampleRate)
        assertEquals(96_000, WavDecoder.inspect(wav(shortArrayOf(1), sampleRate = 96_000)).sampleRate)
        assertCode(WavValidationCode.UNSUPPORTED_SAMPLE_RATE) {
            WavDecoder.inspect(wav(shortArrayOf(1), sampleRate = 7_999))
        }
        assertCode(WavValidationCode.UNSUPPORTED_SAMPLE_RATE) {
            WavDecoder.inspect(wav(shortArrayOf(1), sampleRate = 96_001))
        }
    }

    @Test
    fun `byte rate block alignment empty data and frame alignment are validated`() {
        val invalidByteRate = wav(shortArrayOf(1)).apply { putU32(28, 1) }
        val invalidBlockAlign = wav(shortArrayOf(1)).apply { putU16(32, 4) }
        val empty = riff(chunk("fmt ", format()), chunk("data", byteArrayOf()))
        val partialStereoFrame = riff(
            chunk("fmt ", format(channels = 2)),
            chunk("data", pcm(shortArrayOf(1)))
        )

        assertCode(WavValidationCode.INVALID_BYTE_RATE) { WavDecoder.inspect(invalidByteRate) }
        assertCode(WavValidationCode.INVALID_BLOCK_ALIGN) { WavDecoder.inspect(invalidBlockAlign) }
        assertCode(WavValidationCode.EMPTY_DATA) { WavDecoder.inspect(empty) }
        assertCode(WavValidationCode.MISALIGNED_DATA) { WavDecoder.inspect(partialStereoFrame) }
    }

    @Test
    fun `duration accepts five seconds and rejects one frame more`() {
        val rate = 8_000
        val maximum = wav(ShortArray(rate * WavDecoder.MAX_DURATION_SECONDS), sampleRate = rate)
        val tooLong = wav(ShortArray(rate * WavDecoder.MAX_DURATION_SECONDS + 1), sampleRate = rate)

        assertEquals(
            rate * WavDecoder.MAX_DURATION_SECONDS,
            WavDecoder.inspect(maximum, WavDecoder.MAX_DURATION_SECONDS).frameCount
        )
        assertCode(WavValidationCode.TOO_LONG) {
            WavDecoder.inspect(tooLong, WavDecoder.MAX_DURATION_SECONDS)
        }
    }

    @Test
    fun `normalization writes reloadable mono PCM16 and performs resampling`() {
        val source = wav(
            shortArrayOf(0, 10_000, 20_000, 30_000, 0, -10_000, -20_000, -30_000),
            channels = 2,
            sampleRate = 8_000
        )

        val normalized = WavDecoder.normalizeToMonoPcm16Wav(source, targetSampleRate = 16_000)
        val inspected = WavDecoder.inspect(normalized.bytes)
        val decoded = WavDecoder.toMonoFloats(normalized.bytes)

        assertEquals(1, inspected.channelCount)
        assertEquals(16_000, inspected.sampleRate)
        assertEquals(8, inspected.frameCount)
        assertEquals(inspected.frameCount, normalized.frameCount)
        assertEquals(5_000f / 32768f, decoded.first(), 1e-5f)
        assertEquals(-25_000f / 32768f, decoded.last(), 1e-5f)
    }

    @Test
    fun `malformed byte arrays never escape as bounds errors`() {
        val random = Random(0x564f4944)

        repeat(2_000) {
            val bytes = ByteArray(random.nextInt(0, 257)).also(random::nextBytes)
            val failure = runCatching { WavDecoder.inspect(bytes) }.exceptionOrNull()

            if (failure != null && failure !is WavValidationException) {
                throw AssertionError("Unexpected parser failure for ${bytes.size} bytes", failure)
            }
        }
    }

    private fun assertCode(code: WavValidationCode, action: () -> Unit) {
        val error = assertThrows(WavValidationException::class.java, action)
        assertEquals(code, error.code)
    }

    private fun wav(
        samples: ShortArray,
        channels: Int = 1,
        sampleRate: Int = 44_100,
        encoding: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray = riff(
        chunk("fmt ", format(channels, sampleRate, encoding, bitsPerSample)),
        chunk("data", pcm(samples))
    )

    private fun format(
        channels: Int = 1,
        sampleRate: Int = 44_100,
        encoding: Int = 1,
        bitsPerSample: Int = 16
    ): ByteArray {
        val bytesPerSample = bitsPerSample / 8
        val blockAlign = channels * bytesPerSample
        return ByteArray(16).apply {
            putU16(0, encoding)
            putU16(2, channels)
            putU32(4, sampleRate)
            putU32(8, sampleRate * blockAlign)
            putU16(12, blockAlign)
            putU16(14, bitsPerSample)
        }
    }

    private fun pcm(samples: ShortArray): ByteArray = ByteArray(samples.size * 2).apply {
        samples.forEachIndexed { index, sample -> putU16(index * 2, sample.toInt()) }
    }

    private fun chunk(
        id: String,
        content: ByteArray,
        includePadding: Boolean = true
    ): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray(Charsets.US_ASCII))
        write(leU32(content.size))
        write(content)
        if (includePadding && content.size % 2 != 0) write(0)
    }.toByteArray()

    private fun riff(vararg chunks: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write("WAVE".toByteArray(Charsets.US_ASCII))
            chunks.forEach(::write)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(leU32(body.size))
            write(body)
        }.toByteArray()
    }

    private fun leU32(value: Int): ByteArray = ByteArray(4).apply { putU32(0, value) }

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
