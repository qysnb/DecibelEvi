package com.decibelevidence.app.audio

/** Stop on an unusable capture clock rather than silently trimming stale audio from all videos. */
internal fun audioTimingError(sampleEndNs: Long, deliveredNs: Long): String? = when {
    deliveredNs - sampleEndNs > 1_000_000_000L -> "麦克风采样时间已落后超过1秒，请结束后重新取证"
    sampleEndNs - deliveredNs > 250_000_000L -> "麦克风时间戳异常，本次取证已停止"
    else -> null
}
