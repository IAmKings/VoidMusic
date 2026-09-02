package com.electrodig.objectdrumstudio.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewCoordinateMapperTest {
    @Test
    fun `portrait viewport crops landscape analysis equally on left and right`() {
        val mapper = PreviewCoordinateMapper.forFillCenter(640, 480, 360, 800)!!

        assertEquals(-0.9815f, mapper.map(0f, 0.5f).x, EPSILON)
        assertEquals(1.9815f, mapper.map(1f, 0.5f).x, EPSILON)
        assertEquals(0.5f, mapper.map(0.5f, 0.5f).x, EPSILON)
        assertEquals(0.5f, mapper.map(0.5f, 0.5f).y, EPSILON)
    }

    @Test
    fun `landscape viewport crops portrait analysis equally on top and bottom`() {
        val mapper = PreviewCoordinateMapper.forFillCenter(480, 640, 800, 360)!!

        assertEquals(-0.9815f, mapper.map(0.5f, 0f).y, EPSILON)
        assertEquals(1.9815f, mapper.map(0.5f, 1f).y, EPSILON)
        assertEquals(0.5f, mapper.map(0.5f, 0.5f).x, EPSILON)
        assertEquals(0.5f, mapper.map(0.5f, 0.5f).y, EPSILON)
    }

    @Test
    fun `same aspect ratio leaves normalized coordinates unchanged`() {
        val mapper = PreviewCoordinateMapper.forFillCenter(1280, 720, 640, 360)!!
        val point = mapper.map(0.12f, 0.84f)

        assertEquals(0.12f, point.x, EPSILON)
        assertEquals(0.84f, point.y, EPSILON)
    }

    @Test
    fun `invalid dimensions do not produce a mapper`() {
        assertEquals(null, PreviewCoordinateMapper.forFillCenter(0, 480, 360, 800))
    }

    private companion object { const val EPSILON = 0.0002f }
}
