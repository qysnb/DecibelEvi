package com.decibelevidence.core

import java.util.Collections

/**
 * Freezes photo statistics from complete one-second bins inside the 60 seconds before capture.
 * A bin crossing either window boundary is excluded: partial seconds cannot be reconstructed
 * from the stored aggregate. Missing bins remain missing and never contribute silence.
 *
 * Curve indices retain their original measurement origin, [measurementStartNs], so callers
 * must retain that origin when plotting or exporting timestamps. Cumulative values are
 * recomputed within this photo window; they do not carry the measurement's lifetime totals.
 * The current level is usable only when this snapshot precedes capture by at most 250 ms.
 */
fun MeasurementSnapshot.forPhotoWindow(
    measurementStartNs: Long,
    capturedAtNs: Long,
): MeasurementSnapshot {
    val windowStartNs = capturedAtNs - PHOTO_WINDOW_NS
    val levels = mutableListOf<Double>()
    var maximum: Double? = null
    val points = curve.filter { point ->
        val binStartNs = measurementStartNs + point.elapsedSeconds * PHOTO_SECOND_NS
        point.elapsedSeconds >= 0 && binStartNs >= windowStartNs &&
            binStartNs + PHOTO_SECOND_NS <= capturedAtNs
    }.map { point ->
        point.db?.let(levels::add)
        point.maximumDb?.let { maximum = maxOf(maximum ?: it, it) }
        point.copy(
            cumulativeAverageDb = energyAverageDb(levels),
            cumulativeMaximumDb = maximum,
        )
    }
    val ageNs = capturedAtNs - timeNs
    val currentValid = valid && currentDb != null && ageNs in 0..PHOTO_CURRENT_AGE_NS
    return copy(
        timeNs = capturedAtNs,
        wallTimeMs = wallTimeMs + ageNs / 1_000_000,
        currentDb = currentDb.takeIf { currentValid },
        averageDb = energyAverageDb(levels),
        maximumDb = maximum,
        valid = currentValid,
        curve = Collections.unmodifiableList(points),
    )
}

private const val PHOTO_SECOND_NS = 1_000_000_000L
private const val PHOTO_WINDOW_NS = 60 * PHOTO_SECOND_NS
private const val PHOTO_CURRENT_AGE_NS = 250_000_000L
