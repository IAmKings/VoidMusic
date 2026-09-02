package com.electrodig.voidmusic.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer

/**
 * Minimal PCM WAV decoder: reads 16-bit (mono or stereo) WAV bytes and returns
 * a mono float array in [-1, 1]. Stereo is averaged to mono. We control the
 * source files (see /tmp/gen_drum_samples.py) so we only need to support PCM_16.
 */
internal object WavDecoder {

    fun toMonoFloats(data: ByteArray): FloatArray {
        require(data.size > 44) { "WAV too short" }
        // RIFF header parse (little-endian).
        require(data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte()) { "Not a RIFF file" }
        val audioFormat = leU16(data, 20)
        require(audioFormat == 1) { "Only PCM supported, got $audioFormat" }
        val channels = leU16(data, 22)
        require(channels in 1..2) { "Unsupported channel count $channels" }
        val bits = leU16(data, 34)
        require(bits == 16) { "Only 16-bit supported, got $bits" }

        // Find the "data" chunk (other chunks like "fmt " / "LIST" may precede).
        var pos = 12
        var dataLen = 0
        var dataStart = -1
        while (pos + 8 <= data.size) {
            val chunkId = String(data, pos, 4, Charsets.US_ASCII)
            val chunkSize = leU32(data, pos + 4).toInt()
            if (chunkId == "data") {
                dataStart = pos + 8
                dataLen = chunkSize
                break
            }
            pos += 8 + chunkSize + (chunkSize and 1) // pad to even
        }
        require(dataStart >= 0) { "No data chunk" }

        val frameSize = channels * (bits / 8)
        val frameCount = dataLen / frameSize
        val sb: ShortBuffer = ByteBuffer.wrap(data, dataStart, frameSize * frameCount)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val out = FloatArray(frameCount)
        val inv = 1f / 32768f
        if (channels == 1) {
            for (i in 0 until frameCount) out[i] = sb.get(i) * inv
        } else {
            for (i in 0 until frameCount) {
                val l = sb.get(i * 2)
                val r = sb.get(i * 2 + 1)
                out[i] = ((l + r) * 0.5f) * inv
            }
        }
        return out
    }

    private fun leU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun leU32(b: ByteArray, off: Int): Long =
        leU16(b, off).toLong() or (leU16(b, off + 2).toLong() shl 16)
}
