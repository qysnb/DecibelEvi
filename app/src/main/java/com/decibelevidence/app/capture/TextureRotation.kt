package com.decibelevidence.app.capture

import kotlin.math.atan2
import kotlin.math.roundToInt

/** SurfaceTexture includes producer rotation in addition to its ordinary GL Y flip. */
internal fun additionalTextureRotation(matrix: FloatArray, requested: Int): Int {
    require(matrix.size == 16)
    val producer = (Math.toDegrees(atan2(-matrix[1].toDouble(), matrix[0].toDouble())) / 90.0).roundToInt() * 90
    return ((requested - producer) % 360 + 360) % 360
}
