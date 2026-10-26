package com.decibelevidence.app.model

import com.decibelevidence.core.MeasurementSnapshot
import com.decibelevidence.core.LevelReference

data class LocationSnapshot(
    val latitude: Double?, val longitude: Double?, val accuracyMeters: Float?,
    val timeNs: Long, val wallTimeMs: Long, val placeName: String? = null,
    val approximate: Boolean = false
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null &&
        latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    fun status(atNs: Long): String = when {
        !hasCoordinates || timeNs > atNs -> "未定位"
        atNs - timeNs > 30_000_000_000L -> "旧定位"
        approximate -> "大致位置"
        else -> "已定位"
    }
}

data class OverlayState(
    val sessionId: String = "", val sessionStartNs: Long = 0,
    val recordingStartNs: Long? = null, val measurement: MeasurementSnapshot? = null,
    val location: LocationSnapshot? = null, val wallTimeMs: Long = System.currentTimeMillis(),
    val levelReference: LevelReference? = null,
    val graphStartNs: Long = sessionStartNs, val isPhoto: Boolean = false
)
