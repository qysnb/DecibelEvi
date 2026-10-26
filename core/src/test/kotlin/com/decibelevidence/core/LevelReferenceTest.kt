package com.decibelevidence.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class LevelReferenceTest {
    private val rate = 48_000
    private fun tone(rmsPcm: Double) = ShortArray(rate * 3) {
        (sin(2 * PI * 1000 * it / rate) * rmsPcm * sqrt(2.0)).roundToInt().toShort()
    }
    private fun measure(pcm: ShortArray, reference: LevelReference) =
        MeasurementEngine(startNs = 0, startWallMs = 0, levelReference = reference).process(pcm, pcm.size, 0)

    @Test fun `nominal inputs reproduce their acoustic references at one kilohertz`() {
        for ((source, rms, expected) in listOf(Triple(9, 520.0, 94.0), Triple(6, 2500.0, 90.0))) {
            val ref = LevelReference.androidNominal(source, "built-in:$source")
            assertEquals(LevelReferenceMode.ANDROID_NOMINAL, ref.mode)
            assertEquals(expected - 20 * log10(rms / 32768.0), ref.offsetDb, 1e-10)
            assertTrue(ref.isAcoustic)
            assertEquals("估算 dB(A)", ref.unitLabel)
            assertTrue(ref.source.isNotBlank())
            assertTrue(ref.basis.isNotBlank())
            val result = measure(tone(rms), ref)
            assertEquals(expected, result.currentDb!!, .03)
            assertEquals(expected, result.averageDb!!, .03)
            assertFalse(result.calibrated)
            assertEquals(ref, result.levelReference)
        }
    }

    @Test fun `nominal offset shifts all snapshot and curve fields exactly once`() {
        val pcm = tone(700.0)
        val reference = LevelReference.androidNominal(9, "input")
        val raw = measure(pcm, LevelReference.digital())
        val adjusted = measure(pcm, reference)
        val expectedOffset = 94.0 - 20 * log10(520.0 / 32768.0)
        fun shifted(a: Double?, b: Double?) = assertEquals(a!! + expectedOffset, b!!, 1e-9)
        shifted(raw.currentDb, adjusted.currentDb)
        shifted(raw.averageDb, adjusted.averageDb)
        shifted(raw.maximumDb, adjusted.maximumDb)
        raw.curve.zip(adjusted.curve).forEach { (a, b) ->
            shifted(a.db, b.db)
            shifted(a.maximumDb, b.maximumDb)
            shifted(a.cumulativeAverageDb, b.cumulativeAverageDb)
            shifted(a.cumulativeMaximumDb, b.cumulativeMaximumDb)
        }
    }

    @Test fun `matching user calibration replaces nominal reference instead of adding to it`() {
        val calibration = Calibration(87.5, 123L, "input", 70.0)
        val reference = LevelReference.forInput(9, "input", calibration)
        assertEquals(LevelReferenceMode.USER_REFERENCE, reference.mode)
        assertEquals(87.5, reference.offsetDb, 0.0)
        assertEquals("估算 dB(A)", reference.unitLabel)
        val pcm = tone(700.0)
        val raw = measure(pcm, LevelReference.digital())
        val result = MeasurementEngine(calibration = calibration, startNs = 0, startWallMs = 0,
            levelReference = reference).process(pcm, pcm.size, 0)
        assertEquals(raw.averageDb!! + 87.5, result.averageDb!!, 1e-9)
        assertTrue(result.calibrated)
        assertEquals(LevelReference.androidNominal(9, "other"), LevelReference.forInput(9, "other", calibration))
    }

    @Test fun `legacy raw and calibration constructors retain level semantics`() {
        val pcm = tone(700.0)
        val raw = MeasurementEngine(startNs = 0, startWallMs = 0).process(pcm, pcm.size, 0)
        assertEquals(LevelReferenceMode.DIGITAL, raw.levelReference.mode)
        assertFalse(raw.levelReference.isAcoustic)
        assertEquals("dBFS", raw.levelReference.unitLabel)
        assertTrue(raw.averageDb!! < 0)
        val calibration = Calibration(90.0, 0, "input", 70.0)
        val calibrated = MeasurementEngine(calibration = calibration, startNs = 0, startWallMs = 0)
            .process(pcm, pcm.size, 0)
        assertEquals(LevelReferenceMode.USER_REFERENCE, calibrated.levelReference.mode)
        assertEquals(raw.averageDb!! + 90.0, calibrated.averageDb!!, 1e-9)
    }

    @Test fun `formatting distinguishes acoustic negatives from raw digital negatives`() {
        val acoustic = LevelReference.androidNominal(9, "input")
        assertEquals("＜0", levelText(-0.01, acoustic))
        assertEquals("0.0", levelText(0.0, acoustic))
        assertEquals("54.3", levelText(54.32, acoustic))
        assertEquals("-23.0", levelText(-23.01, LevelReference.digital()))
        for (invalid in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals("—", levelText(invalid, acoustic))
            assertEquals("—", levelText(invalid, LevelReference.digital()))
        }
    }

    @Test fun `unknown input source keeps a digital reference`() {
        assertEquals(LevelReferenceMode.DIGITAL, LevelReference.forInput(123, "input").mode)
    }
}
