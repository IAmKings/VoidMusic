package com.electrodig.voidmusic.audio

import com.electrodig.voidmusic.detection.color.DrumPad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrumEngineSwitchTest {
    @Test
    fun `failed replacement restores the previous playable kit`() {
        val sessions = mutableListOf<FakeBackend>()
        val attempts = mutableListOf<String>()
        val engine = DrumEngine(AudioBackendStarter { kit ->
            attempts += kit.id
            if (kit.id == "broken") null else FakeBackend().also(sessions::add)
        })
        try {
            assertTrue(engine.start(preparedKit("old")))
            assertFalse(engine.setPreparedKit(preparedKit("broken")))
            engine.trigger(DrumPad.SNARE, 0.8f)

            assertEquals(listOf("old", "broken", "old"), attempts)
            assertTrue(sessions.first().stopped)
            assertEquals(listOf(DrumPad.SNARE), sessions.last().triggered)
            assertEquals(AudioRuntimePhase.RUNNING, engine.status.value.phase)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `hits queued during failed switch are replayed after recovery`() {
        lateinit var engine: DrumEngine
        val restored = FakeBackend()
        var oldStarts = 0
        engine = DrumEngine(AudioBackendStarter { kit ->
            when (kit.id) {
                "old" -> if (oldStarts++ == 0) FakeBackend() else restored
                else -> {
                    engine.trigger(DrumPad.KICK, 1f)
                    null
                }
            }
        })
        try {
            assertTrue(engine.start(preparedKit("old")))
            assertFalse(engine.setPreparedKit(preparedKit("broken")))

            assertEquals(listOf(DrumPad.KICK), restored.triggered)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `foreground startup failure restores the previously prepared kit`() {
        val attempts = mutableListOf<String>()
        val engine = DrumEngine(AudioBackendStarter { kit ->
            attempts += kit.id
            if (kit.id == "new") null else FakeBackend()
        })
        assertTrue(engine.start(preparedKit("old")))
        engine.stop()

        try {
            assertFalse(engine.start(preparedKit("new")))
            assertEquals(listOf("old", "new", "old"), attempts)
            assertEquals(AudioRuntimePhase.RUNNING, engine.status.value.phase)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `native route error rebuilds the active prepared kit`() {
        val sessions = mutableListOf<FakeBackend>()
        val engine = DrumEngine(AudioBackendStarter {
            FakeBackend(
                kind = AudioBackend.NATIVE_OBOE,
                streamError = if (sessions.isEmpty()) -899 else 0
            ).also(sessions::add)
        })
        try {
            assertTrue(engine.start(preparedKit("old")))

            engine.pollNativeHealth()

            assertEquals(2, sessions.size)
            assertTrue(sessions.first().stopped)
            assertEquals(AudioBackend.NATIVE_OBOE, engine.backend)
            assertEquals(AudioRuntimePhase.RUNNING, engine.status.value.phase)
        } finally {
            engine.stop()
        }
    }

    @Test
    fun `dense same-pad and cross-pad triggers remain ordered`() {
        val session = FakeBackend()
        val engine = DrumEngine(AudioBackendStarter { session })
        try {
            assertTrue(engine.start(preparedKit("dense")))
            repeat(100) { index ->
                engine.trigger(DrumPad.entries[index % DrumPad.entries.size], 1f)
            }

            assertEquals(100, session.triggered.size)
            assertEquals(
                List(100) { index -> DrumPad.entries[index % DrumPad.entries.size] },
                session.triggered
            )
        } finally {
            engine.stop()
        }
    }

    private fun preparedKit(id: String) = PreparedKit(
        id = id,
        name = id,
        sampleRate = 48_000,
        samples = DrumPad.entries.associateWith { pad ->
            PreparedSample(
                source = AudioSampleSource.BuiltIn(pad.ordinal + 1),
                pcm = floatArrayOf(0f, 0.5f, 0f),
                soundPoolSource = SoundPoolSampleSource.BuiltIn(pad.ordinal + 1)
            )
        }
    )

    private class FakeBackend(
        override val kind: AudioBackend = AudioBackend.SOUND_POOL,
        private val streamError: Int = 0
    ) : ActiveAudioBackend {
        val triggered = mutableListOf<DrumPad>()
        var stopped = false

        override fun trigger(pad: DrumPad, velocity: Float) {
            triggered += pad
        }

        override fun setMasterVolume(volume: Float) = Unit

        override fun streamError(): Int = streamError

        override fun stop() {
            stopped = true
        }
    }
}
