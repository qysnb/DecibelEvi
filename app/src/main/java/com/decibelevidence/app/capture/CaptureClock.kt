package com.decibelevidence.app.capture

/** REALTIME shares the audio clock. Unknown clocks use the minimum observed delivery offset:
 * a delayed first frame must not permanently shift later frames into the future. */
internal class CaptureClock(private val realtime: Boolean) {
    private var offset: Long? = null
    private var previous = Long.MIN_VALUE
    fun map(sensorNs: Long, arrivalNs: Long): Long {
        val candidate = if (realtime && sensorNs > 0L) sensorNs else {
            val observed = arrivalNs - sensorNs
            offset = minOf(offset ?: observed, observed)
            sensorNs + offset!!
        }
        val result = if (previous == Long.MIN_VALUE) candidate else maxOf(candidate, previous + 1)
        previous = result
        return result
    }
}
