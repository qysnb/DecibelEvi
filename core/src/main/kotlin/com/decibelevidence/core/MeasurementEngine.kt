package com.decibelevidence.core

import java.util.Collections
import kotlin.math.*

data class Calibration(val offsetDb: Double, val createdAtMs: Long, val inputKey: String, val referenceDb: Double)
/** Cumulative fields include only samples observed up to this bin's exclusive end boundary.
 * Missing bins retain the previous observed cumulative statistics; their own levels are null.
 */
data class CurvePoint(
    val elapsedSeconds: Long,
    val db: Double?,
    val maximumDb: Double?,
    val cumulativeAverageDb: Double? = null,
    val cumulativeMaximumDb: Double? = null,
)
data class MeasurementSnapshot(val timeNs: Long, val wallTimeMs: Long, val currentDb: Double?, val averageDb: Double?, val maximumDb: Double?, val calibrated: Boolean, val valid: Boolean, val curve: List<CurvePoint>, val levelReference: LevelReference = LevelReference.digital())

/** Converts a nonnegative sample count without overflowing the intermediate product.
 * Throws if the final duration itself cannot fit into a Long number of nanoseconds.
 */
fun samplesToNanos(samples: Long, sampleRate: Int): Long {
    require(samples >= 0 && sampleRate > 0)
    val wholeSeconds = samples / sampleRate
    val remainingSamples = samples % sampleRate
    return Math.addExact(
        Math.multiplyExact(wholeSeconds, 1_000_000_000L),
        remainingSamples * 1_000_000_000L / sampleRate,
    )
}

/** Mean of equal-duration levels in the energy domain. Non-finite input is rejected. */
fun energyAverageDb(levelsDb: Iterable<Double>): Double? {
    val levels = levelsDb.toList()
    if (levels.isEmpty()) return null
    require(levels.all { it.isFinite() })
    val highest = levels.max()
    return highest + 10 * log10(levels.sumOf { 10.0.pow((it - highest) / 10) } / levels.size)
}

fun calibrationFromRawMean(rawMeanDb: Double, referenceDb: Double, createdAtMs: Long, inputKey: String): Calibration {
    require(rawMeanDb.isFinite() && referenceDb.isFinite())
    require(inputKey.isNotBlank())
    return Calibration(referenceDb - rawMeanDb, createdAtMs, inputKey, referenceDb)
}

/**
 * Streaming mono PCM16 A-weighted estimator. Single-thread confined; synchronize externally.
 * The reference supplies exactly one offset from dBFS relative to RMS 1.0. Android nominal
 * references are estimates; a user reference replaces the nominal offset. The legacy default
 * remains digital unless a calibration is supplied. Only the numerical floor is clamped (-160 dBFS).
 * Fast uses a sample-wise 125 ms exponential energy detector; the session mean integrates
 * actual samples only. Missing data resets filter/detector state and never becomes silence.
 * Curve points represent [elapsedSeconds, elapsedSeconds + 1); incomplete seconds have null
 * levels. A new engine starts a fresh session. No existing snapshot is mutated.
 */
class MeasurementEngine(
    val sampleRate: Int = 48000,
    private val calibration: Calibration? = null,
    private val startNs: Long,
    private val startWallMs: Long,
    val levelReference: LevelReference = calibration?.let(LevelReference::fromCalibration) ?: LevelReference.digital(),
) {
    init {
        require(sampleRate >= 8000) { "Sample rate must be at least 8000 Hz" }
        require(calibration == null || calibration.offsetDb.isFinite())
    }

    private val filter = AWeightingFilter(sampleRate)
    private val fastAlpha = exp(-1.0 / (sampleRate * .125))
    private var fastEnergy = 0.0
    private var maximumEnergy: Double? = null
    private var totalEnergy = 0.0
    private var totalSamples = 0L
    private var streamAnchorNs = startNs
    private var streamSamples = 0L
    private var lastEndNs: Long? = null
    private var publishedNs = startNs
    private var lastReadValid = false
    private var binIndex = 0L
    private var binSamples = 0
    private var binEnergy = 0.0
    private var binMaximum = 0.0
    private val history = ArrayDeque<CurvePoint>()

    fun process(pcm: ShortArray, count: Int, firstSampleNs: Long): MeasurementSnapshot {
        val previousEnd = lastEndNs
        val toleranceNs = 2_000_000_000L / sampleRate
        if (count <= 0 || count > pcm.size || firstSampleNs < startNs ||
            (previousEnd != null && firstSampleNs < previousEnd - toleranceNs)) {
            lastReadValid = false
            return snapshot(maxOf(publishedNs, firstSampleNs))
        }
        if (previousEnd == null || firstSampleNs > previousEnd + toleranceNs) {
            filter.reset()
            fastEnergy = 0.0
            streamAnchorNs = firstSampleNs
            streamSamples = 0
        }
        for (i in 0 until count) {
            val sampleNs = streamAnchorNs + samplesToNanos(streamSamples + i, sampleRate)
            val index = (sampleNs - startNs) / SECOND_NS
            advanceToBin(index)
            val weighted = filter.process(pcm[i] / 32768.0)
            val energy = weighted * weighted
            fastEnergy = fastAlpha * fastEnergy + (1 - fastAlpha) * energy
            totalEnergy += energy
            totalSamples++
            maximumEnergy = maxOf(maximumEnergy ?: 0.0, fastEnergy)
            binEnergy += energy
            binSamples++
            binMaximum = maxOf(binMaximum, fastEnergy)
        }
        streamSamples += count
        val endNs = streamAnchorNs + samplesToNanos(streamSamples, sampleRate)
        lastEndNs = endNs
        if (endNs - startNs >= (binIndex + 1) * SECOND_NS) advanceToBin(binIndex + 1)
        lastReadValid = true
        return snapshot(endNs)
    }

    fun snapshot(atNs: Long): MeasurementSnapshot {
        publishedNs = maxOf(publishedNs, atNs, startNs)
        val end = lastEndNs
        val valid = lastReadValid && end != null && publishedNs - end <= CURRENT_AGE_NS
        val lastCompleted = (publishedNs - startNs) / SECOND_NS - 1
        while (history.isNotEmpty() && history.first().elapsedSeconds < lastCompleted - 59) history.removeFirst()
        return MeasurementSnapshot(
            timeNs = publishedNs,
            wallTimeMs = startWallMs + (publishedNs - startNs) / 1_000_000,
            currentDb = if (valid) level(fastEnergy) else null,
            averageDb = if (totalSamples > 0) level(totalEnergy / totalSamples) else null,
            maximumDb = maximumEnergy?.let(::level),
            calibrated = levelReference.mode == LevelReferenceMode.USER_REFERENCE,
            valid = valid,
            curve = Collections.unmodifiableList(history.toList()),
            levelReference = levelReference,
        )
    }

    /** Call only after PCM has stopped. Seal elapsed missing seconds, never pad the final partial second. */
    fun finish(atNs: Long): MeasurementSnapshot {
        advanceToBin((maxOf(atNs, publishedNs, startNs) - startNs) / SECOND_NS)
        return snapshot(atNs)
    }

    private fun advanceToBin(target: Long) {
        if (target <= binIndex) return
        val complete = binSamples == sampleRate
        val cumulativeAverage = if (totalSamples > 0) level(totalEnergy / totalSamples) else null
        val cumulativeMaximum = maximumEnergy?.let(::level)
        history.addLast(CurvePoint(
            binIndex,
            if (complete) level(binEnergy / binSamples) else null,
            if (complete) level(binMaximum) else null,
            cumulativeAverage,
            cumulativeMaximum,
        ))
        // Bound the work and storage even after a long interruption.
        for (missing in maxOf(binIndex + 1, target - 60) until target) {
            history.addLast(CurvePoint(missing, null, null, cumulativeAverage, cumulativeMaximum))
        }
        while (history.size > 60) history.removeFirst()
        binIndex = target
        binSamples = 0
        binEnergy = 0.0
        binMaximum = 0.0
    }

    private fun level(energy: Double): Double = 10 * log10(maxOf(energy, 1e-16)) + levelReference.offsetDb

    private companion object {
        const val SECOND_NS = 1_000_000_000L
        const val CURRENT_AGE_NS = 250_000_000L
    }
}

/**
 * Bilinear transform of the analog A network: four zeros at s=0 and real poles at
 * -2*pi*[20.598997,20.598997,107.65265,737.86223,12194.217,12194.217].
 * Three stable second-order sections avoid a numerically fragile sixth-order polynomial.
 * Gain is normalized at digital 1 kHz. Plain bilinear frequency warping means the digital
 * 48 kHz response at 10 kHz is about -3.7 dB, versus the analog network's -2.5 dB.
 * This implementation is an estimate and makes no IEC instrument compliance claim.
 */
internal class AWeightingFilter(sampleRate: Int) {
    private val sections: List<Section>
    private val gain: Double

    init {
        fun pole(f: Double): Double {
            val analog = 2 * PI * f
            return (2 * sampleRate - analog) / (2 * sampleRate + analog)
        }
        val low = pole(20.598997)
        val high = pole(12194.217)
        sections = listOf(
            Section(-2.0, 1.0, -2 * low, low * low),
            Section(-2.0, 1.0, -(pole(107.65265) + pole(737.86223)), pole(107.65265) * pole(737.86223)),
            Section(2.0, 1.0, -2 * high, high * high),
        )
        val w = 2 * PI * 1000 / sampleRate
        gain = 1 / sections.fold(1.0) { magnitude, section -> magnitude * section.magnitude(w) }
    }

    fun process(sample: Double): Double {
        var output = sample * gain
        for (section in sections) output = section.process(output)
        return output
    }

    fun reset() = sections.forEach { it.reset() }

    private class Section(private val b1: Double, private val b2: Double, private val a1: Double, private val a2: Double) {
        private var z1 = 0.0
        private var z2 = 0.0
        fun process(input: Double): Double {
            val output = input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b2 * input - a2 * output
            return output
        }
        fun reset() { z1 = 0.0; z2 = 0.0 }
        fun magnitude(w: Double): Double {
            fun power(c1: Double, c2: Double): Double =
                (1 + c1 * cos(w) + c2 * cos(2 * w)).pow(2) + (c1 * sin(w) + c2 * sin(2 * w)).pow(2)
            return sqrt(power(b1, b2) / power(a1, a2))
        }
    }
}
