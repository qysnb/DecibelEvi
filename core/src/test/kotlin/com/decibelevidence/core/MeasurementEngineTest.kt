package com.decibelevidence.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class MeasurementEngineTest {
    private val rate = 48_000
    private fun tone(frequency: Double, seconds: Int = 1, amplitude: Double = .1) =
        ShortArray(rate * seconds) { (sin(2 * PI * frequency * it / rate) * amplitude * 32768).roundToInt().toShort() }

    @Test fun `sample timestamps remain accurate beyond 53 hours`() {
        val samples = 9_223_372_037L
        assertEquals(192_153_584_104_166L, samplesToNanos(samples, rate))
        assertEquals(0L, samplesToNanos(0, rate))
        assertEquals(20_833L, samplesToNanos(1, rate))
        assertEquals(1_000_000_000L, samplesToNanos(rate.toLong(), rate))
    }

    @Test fun `energy averaging uses linear energy`() {
        assertEquals(67.4036268949, energyAverageDb(listOf(60.0, 70.0))!!, 1e-8)
        assertNull(energyAverageDb(emptyList()))
    }

    @Test fun `empty and invalid reads do not invent data`() {
        val engine = MeasurementEngine(startNs = 0, startWallMs = 1234)
        assertNull(engine.snapshot(0).currentDb)
        assertFalse(engine.process(shortArrayOf(), 0, 0).valid)
        assertFalse(engine.process(shortArrayOf(1), -1, 0).valid)
        assertFalse(engine.process(shortArrayOf(1), 2, 0).valid)
        assertNull(engine.snapshot(0).averageDb)
        assertTrue(engine.snapshot(0).curve.isEmpty())
    }

    @Test fun `A weighting matches low mid and high frequency reference`() {
        // The bilinear digital network warps the analog -2.5 dB at 10 kHz to ~-3.7 dB.
        for ((frequency, weighting) in listOf(100.0 to -19.1, 1000.0 to 0.0, 10000.0 to -3.7)) {
            val pcm = tone(frequency, 3)
            val result = MeasurementEngine(startNs = 0, startWallMs = 0).process(pcm, pcm.size, 0)
            assertEquals("frequency $frequency", -23.0103 + weighting, result.currentDb!!, .45)
        }
    }

    @Test fun `calibration offset applies equally to every level`() {
        val pcm = tone(1000.0, 2)
        val raw = MeasurementEngine(startNs = 0, startWallMs = 0).process(pcm, pcm.size, 0)
        val calibration = calibrationFromRawMean(-23.0, 70.0, 123, "built-in")
        val calibrated = MeasurementEngine(calibration = calibration, startNs = 0, startWallMs = 0).process(pcm, pcm.size, 0)
        assertEquals(93.0, calibration.offsetDb, 0.0)
        assertTrue(calibrated.calibrated)
        assertEquals(raw.currentDb!! + 93, calibrated.currentDb!!, 1e-9)
        assertEquals(raw.averageDb!! + 93, calibrated.averageDb!!, 1e-9)
        assertEquals(raw.maximumDb!! + 93, calibrated.maximumDb!!, 1e-9)
        assertEquals(raw.curve[0].db!! + 93, calibrated.curve[0].db!!, 1e-9)
    }

    @Test fun `curve cumulative statistics stop at each bin boundary`() {
        val quiet = tone(1000.0, amplitude = .01)
        val loud = tone(1000.0, amplitude = .0316227766)
        val combined = quiet + loud
        val quietSnapshot = MeasurementEngine(startNs = 0, startWallMs = 0).process(quiet, quiet.size, 0)
        val combinedSnapshot = MeasurementEngine(startNs = 0, startWallMs = 0).process(combined, combined.size, 0)
        val first = combinedSnapshot.curve[0]
        val second = combinedSnapshot.curve[1]
        assertEquals(quietSnapshot.averageDb!!, first.cumulativeAverageDb!!, 1e-9)
        assertEquals(quietSnapshot.maximumDb!!, first.cumulativeMaximumDb!!, 1e-9)
        assertEquals(combinedSnapshot.averageDb!!, second.cumulativeAverageDb!!, 1e-9)
        assertEquals(combinedSnapshot.maximumDb!!, second.cumulativeMaximumDb!!, 1e-9)
        assertEquals(first.cumulativeAverageDb!! + 7.4036, second.cumulativeAverageDb!!, .04)
        val calibrated = MeasurementEngine(
            calibration = Calibration(100.0, 0, "built-in", 70.0), startNs = 0, startWallMs = 0,
        ).process(combined, combined.size, 0)
        combinedSnapshot.curve.zip(calibrated.curve).forEach { (raw, adjusted) ->
            assertEquals(raw.cumulativeAverageDb!! + 100, adjusted.cumulativeAverageDb!!, 1e-9)
            assertEquals(raw.cumulativeMaximumDb!! + 100, adjusted.cumulativeMaximumDb!!, 1e-9)
        }
    }

    @Test fun `Fast maximum persists while Fast current decays`() {
        val engine = MeasurementEngine(startNs = 0, startWallMs = 0)
        val loud = tone(1000.0)
        val high = engine.process(loud, loud.size, 0)
        val quiet = tone(1000.0, amplitude = .01)
        val low = engine.process(quiet, quiet.size, 1_000_000_000)
        assertEquals(high.maximumDb!!, low.maximumDb!!, .001)
        assertTrue(low.currentDb!! < low.maximumDb!! - 18)
        assertTrue(high.maximumDb!! < -22.8) // RMS Fast maximum, never PCM peak.
    }

    @Test fun `one second bins are independent of audio chunking`() {
        val pcm = tone(1000.0, 2)
        val whole = MeasurementEngine(startNs = 0, startWallMs = 0).process(pcm, pcm.size, 0)
        val chunks = MeasurementEngine(startNs = 0, startWallMs = 0)
        var offset = 0
        while (offset < pcm.size) {
            val n = min(721, pcm.size - offset)
            chunks.process(pcm.copyOfRange(offset, offset + n), n, offset.toLong() * 1_000_000_000 / rate)
            offset += n
        }
        val split = chunks.snapshot(2_000_000_000)
        assertEquals(2, whole.curve.size)
        assertEquals(whole.curve.map { it.elapsedSeconds }, split.curve.map { it.elapsedSeconds })
        whole.curve.zip(split.curve).forEach { (a,b) -> assertEquals(a.db!!, b.db!!, 1e-8) }
    }

    @Test fun `gaps produce missing bins without silence in the average`() {
        val engine = MeasurementEngine(startNs = 0, startWallMs = 10_000)
        val pcm = tone(1000.0)
        engine.process(pcm, pcm.size, 0)
        assertFalse(engine.snapshot(2_000_000_000).valid)
        val result = engine.process(pcm, pcm.size, 3_000_000_000)
        assertEquals(listOf(0L, 1L, 2L, 3L), result.curve.map { it.elapsedSeconds })
        assertNull(result.curve[1].db)
        assertNull(result.curve[2].db)
        assertEquals(-23.0103, result.averageDb!!, .08)
        assertEquals(14_000, result.wallTimeMs)
    }

    @Test fun `curve evicts by elapsed time and snapshots are immutable`() {
        val engine = MeasurementEngine(startNs = 0, startWallMs = 0)
        val pcm = tone(1000.0)
        val initial = engine.process(pcm, pcm.size, 0)
        val latest = engine.process(pcm, pcm.size, 70_000_000_000)
        assertEquals(60, latest.curve.size)
        assertEquals(11L, latest.curve.first().elapsedSeconds)
        assertEquals(70L, latest.curve.last().elapsedSeconds)
        assertEquals(1, initial.curve.size)
    }

    @Test fun `out of order buffers cannot change session totals or move time backwards`() {
        val engine = MeasurementEngine(startNs = 0, startWallMs = 100)
        val pcm = tone(1000.0)
        val first = engine.process(pcm, pcm.size, 0)
        val invalid = engine.process(pcm, pcm.size, 0)
        assertFalse(invalid.valid)
        assertEquals(first.averageDb!!, invalid.averageDb!!, 0.0)
        assertEquals(first.timeNs, engine.snapshot(0).timeNs)
    }

    @Test fun `new engine resets session and partial bins are never padded`() {
        val engine = MeasurementEngine(startNs = 0, startWallMs = 0)
        val half = tone(1000.0).copyOf(rate / 2)
        assertTrue(engine.process(half, half.size, 0).curve.isEmpty())
        assertTrue(engine.finish(500_000_000).curve.isEmpty())
        assertNull(MeasurementEngine(startNs = 100, startWallMs = 200).snapshot(100).maximumDb)
    }

    @Test fun `terminal interruption records missing complete seconds without changing totals`() {
        val engine = MeasurementEngine(startNs = 0, startWallMs = 0)
        val pcm = tone(1000.0)
        val before = engine.process(pcm, pcm.size, 0)
        val ended = engine.finish(3_500_000_000)
        assertEquals(listOf(0L, 1L, 2L), ended.curve.map { it.elapsedSeconds })
        assertTrue(ended.curve.drop(1).all { it.db == null && it.maximumDb == null })
        assertEquals(before.averageDb, ended.averageDb)
        assertEquals(before.maximumDb, ended.maximumDb)
    }

    @Test fun `terminal partial sample second is invalid and no samples is never silence`() {
        val engine = MeasurementEngine(startNs = 0, startWallMs = 0)
        val pcm = tone(1000.0).copyOf(rate / 2)
        engine.process(pcm, pcm.size, 0)
        assertNull(engine.finish(1_500_000_000).curve.single().db)
        val empty = MeasurementEngine(startNs = 0, startWallMs = 0).finish(2_000_000_000)
        assertEquals(2, empty.curve.size)
        assertTrue(empty.curve.all { it.db == null })
        assertNull(empty.averageDb)
    }

    @Test fun `terminal long interruption stays bounded and repeated finish is stable`() {
        val engine = MeasurementEngine(startNs = 0, startWallMs = 0)
        val ended = engine.finish(3600_000_000_000)
        assertEquals(60, ended.curve.size)
        assertEquals(3540L, ended.curve.first().elapsedSeconds)
        assertEquals(3599L, ended.curve.last().elapsedSeconds)
        assertEquals(ended, engine.finish(3600_000_000_000))
    }
}
