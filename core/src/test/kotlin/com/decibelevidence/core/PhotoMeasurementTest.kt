package com.decibelevidence.core

import org.junit.Assert.*
import org.junit.Test

class PhotoMeasurementTest {
    private val second = 1_000_000_000L
    private val start = 5 * second

    private fun snapshot(atSeconds: Long, vararg points: CurvePoint) = MeasurementSnapshot(
        timeNs = start + atSeconds * second,
        wallTimeMs = 100_000 + atSeconds * 1000,
        currentDb = 65.0,
        averageDb = 90.0,
        maximumDb = 99.0,
        calibrated = false,
        valid = true,
        curve = points.toList(),
    )

    @Test fun `photo window energy averages valid bins and resets cumulative statistics`() {
        val source = snapshot(3,
            CurvePoint(0, 60.0, 63.0, 90.0, 99.0),
            CurvePoint(1, null, null, 90.0, 99.0),
            CurvePoint(2, 70.0, 73.0, 90.0, 99.0),
        )
        val photo = source.forPhotoWindow(start, start + 3 * second)
        assertEquals(67.4036268949, photo.averageDb!!, 1e-8)
        assertEquals(73.0, photo.maximumDb!!, 0.0)
        assertEquals(listOf(0L, 1L, 2L), photo.curve.map { it.elapsedSeconds })
        assertEquals(60.0, photo.curve[0].cumulativeAverageDb!!, 0.0)
        assertEquals(60.0, photo.curve[1].cumulativeAverageDb!!, 0.0)
        assertEquals(63.0, photo.curve[1].cumulativeMaximumDb!!, 0.0)
        assertNull(photo.curve[1].db)
        assertEquals(photo.averageDb, photo.curve.last().cumulativeAverageDb)
        assertEquals(photo.maximumDb, photo.curve.last().cumulativeMaximumDb)
        assertEquals(90.0, source.curve.first().cumulativeAverageDb!!, 0.0)
    }

    @Test fun `old maximum and bins straddling either window edge are excluded`() {
        val captured = start + 61 * second + second / 2
        val source = snapshot(63,
            CurvePoint(0, 90.0, 99.0),
            CurvePoint(1, 80.0, 89.0),
            CurvePoint(2, 60.0, 63.0),
            CurvePoint(60, 70.0, 73.0),
            CurvePoint(61, 90.0, 98.0),
            CurvePoint(62, 90.0, 99.0),
        )
        val photo = source.forPhotoWindow(start, captured)
        assertEquals(listOf(2L, 60L), photo.curve.map { it.elapsedSeconds })
        assertEquals(67.4036268949, photo.averageDb!!, 1e-8)
        assertEquals(73.0, photo.maximumDb!!, 0.0)
        assertEquals(captured, photo.timeNs)
        assertEquals(161_500L, photo.wallTimeMs)
    }

    @Test fun `exact boundaries retain sixty complete seconds`() {
        val source = snapshot(65, *(0L..65L).map { CurvePoint(it, 60.0, 63.0) }.toTypedArray())
        val photo = source.forPhotoWindow(start, start + 65 * second)
        assertEquals((5L..64L).toList(), photo.curve.map { it.elapsedSeconds })
    }

    @Test fun `missing measurements never become zero or lifetime statistics`() {
        val source = snapshot(2, CurvePoint(0, null, null), CurvePoint(1, null, null))
        val photo = source.forPhotoWindow(start, start + 2 * second)
        assertNull(photo.averageDb)
        assertNull(photo.maximumDb)
        assertTrue(photo.curve.all { it.cumulativeAverageDb == null && it.cumulativeMaximumDb == null })
    }

    @Test fun `capture before first completed bin has no summary statistics`() {
        val photo = snapshot(1, CurvePoint(0, 60.0, 63.0))
            .forPhotoWindow(start, start + second / 2)
        assertTrue(photo.curve.isEmpty())
        assertNull(photo.averageDb)
        assertNull(photo.maximumDb)
    }

    @Test fun `current level requires a valid snapshot no more than 250 milliseconds before capture`() {
        val source = snapshot(2, CurvePoint(1, 60.0, 63.0))
        val fresh = source.forPhotoWindow(start, source.timeNs + 250_000_000)
        assertTrue(fresh.valid)
        assertEquals(source.currentDb, fresh.currentDb)
        for (invalid in listOf(
            source.forPhotoWindow(start, source.timeNs + 250_000_001),
            source.forPhotoWindow(start, source.timeNs - 1),
            source.copy(valid = false).forPhotoWindow(start, source.timeNs),
        )) {
            assertFalse(invalid.valid)
            assertNull(invalid.currentDb)
        }
    }
}
