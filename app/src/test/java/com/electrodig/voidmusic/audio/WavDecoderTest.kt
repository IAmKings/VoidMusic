package com.electrodig.voidmusic.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavDecoderTest {

    /** Build a minimal PCM16 WAV from int16 samples. */
    private fun wav(samples: ShortArray, channels: Int = 1, sampleRate: Int = 44100): ByteArray {
        val audioFormat = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream()
        val le = ByteOrder.LITTLE_ENDIAN
        fun u32(v: Int) = ByteBuffer.allocate(4).order(le).putInt(v).array()
        fun u16(v: Int) = ByteBuffer.allocate(2).order(le).putShort(v.toShort()).array()

        out.write("RIFF".toByteArray())
        out.write(u32(36 + dataSize))
        out.write("WAVE".toByteArray())
        // fmt chunk
        out.write("fmt ".toByteArray())
        out.write(u32(16))
        out.write(u16(audioFormat))
        out.write(u16(channels))
        out.write(u32(sampleRate))
        out.write(u32(byteRate))
        out.write(u16(blockAlign.toInt()))
        out.write(u16(bitsPerSample))
        // data chunk
        out.write("data".toByteArray())
        out.write(u32(dataSize))
        for (s in samples) out.write(u16(s.toInt()))
        return out.toByteArray()
    }

    @Test
    fun `decodes mono wav to floats scaled to minus one to one`() {
        val samples = shortArrayOf(0, 16384, 32767, -16384, -32768)
        val floats = WavDecoder.toMonoFloats(wav(samples, channels = 1))
        assertEquals(samples.size, floats.size)
        assertEquals(0f, floats[0], 1e-3f)
        assertEquals(0.5f, floats[1], 0.01f)
        assertEquals(1.0f, floats[2], 1e-3f)
        assertEquals(-0.5f, floats[3], 0.01f)
        assertEquals(-1.0f, floats[4], 1e-3f)
    }

    @Test
    fun `decodes stereo wav by averaging channels`() {
        // Left ramping up, right ramping down; average should be the mid value.
        // Interleaved: L0,R0,L1,R1 -> L=[100,-100], R=[100,-100] => avg=[100,-100].
        val samples = shortArrayOf(100, 100, -100, -100)
        val floats = WavDecoder.toMonoFloats(wav(samples, channels = 2))
        assertEquals(2, floats.size)
        val inv = 1f / 32768f
        assertEquals(100 * inv, floats[0], 1e-5f)
        assertEquals(-100 * inv, floats[1], 1e-5f)
    }

    @Test
    fun `rejects non-RIFF input`() {
        assertThrows(IllegalArgumentException::class.java) {
            WavDecoder.toMonoFloats(ByteArray(60))
        }
    }
}
