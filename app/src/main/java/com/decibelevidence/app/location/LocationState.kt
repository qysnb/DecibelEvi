package com.decibelevidence.app.location

import com.decibelevidence.app.model.LocationSnapshot

/** Main-thread state: a geocode result belongs to one session and one accepted coordinate. */
internal class LocationState {
    var generation = 0L
        private set
    var current: LocationSnapshot? = null
        private set
    private var running = false

    fun start() { generation++; current = null; running = true }
    fun stop() { generation++; current = null; running = false }
    fun accept(sample: LocationSnapshot): Boolean {
        if (!running || !sample.hasCoordinates || sample.timeNs <= (current?.timeNs ?: Long.MIN_VALUE)) return false
        current = sample
        return true
    }
    fun resolve(requestGeneration: Long, sample: LocationSnapshot, name: String?): LocationSnapshot? {
        if (!running || generation != requestGeneration || current != sample || name.isNullOrBlank()) return null
        return sample.copy(placeName = name).also { current = it }
    }
}
