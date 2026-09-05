package com.electrodig.voidmusic.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraBindingGateTest {
    @Test
    fun `new request invalidates older callback`() {
        val gate = CameraBindingGate()
        val first = requireNotNull(gate.beginRequest())
        val second = requireNotNull(gate.beginRequest())

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `preview stop invalidates callback but permits restart`() {
        val gate = CameraBindingGate()
        val stopped = requireNotNull(gate.beginRequest())

        gate.cancelPending()

        assertFalse(gate.isCurrent(stopped))
        assertNotNull(gate.beginRequest())
    }

    @Test
    fun `close is idempotent and terminal`() {
        val gate = CameraBindingGate()
        val pending = requireNotNull(gate.beginRequest())

        assertTrue(gate.close())
        assertFalse(gate.close())
        assertFalse(gate.isCurrent(pending))
        assertNull(gate.beginRequest())
    }
}
