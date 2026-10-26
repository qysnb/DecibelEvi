package com.decibelevidence.core

import java.util.Locale
import kotlin.math.log10

enum class LevelReferenceMode { DIGITAL, ANDROID_NOMINAL, USER_REFERENCE }

/** The single additive conversion from A-weighted PCM dBFS to the displayed level. */
data class LevelReference(
    val mode: LevelReferenceMode,
    val offsetDb: Double = 0.0,
    val inputKey: String = "",
    val source: String = "",
    val basis: String = "",
) {
    init { require(offsetDb.isFinite()) { "Level reference offset must be finite" } }

    val isAcoustic: Boolean get() = mode != LevelReferenceMode.DIGITAL
    val unitLabel: String get() = when (mode) {
        LevelReferenceMode.DIGITAL -> "dBFS"
        LevelReferenceMode.ANDROID_NOMINAL -> "估算 dB(A)"
        LevelReferenceMode.USER_REFERENCE -> "估算 dB(A)"
    }

    companion object {
        fun digital(): LevelReference = LevelReference(
            LevelReferenceMode.DIGITAL, source = "PCM16",
            basis = "A-weighted PCM RMS relative to digital full scale",
        )

        fun fromCalibration(calibration: Calibration): LevelReference = LevelReference(
            LevelReferenceMode.USER_REFERENCE, calibration.offsetDb, calibration.inputKey,
            source = "USER_REFERENCE",
            basis = "User reference ${calibration.referenceDb} dB(A), createdAtMs=${calibration.createdAtMs}",
        )

        /** Android audio-source integer values keep this core independent of Android APIs. */
        fun androidNominal(audioSource: Int, inputKey: String): LevelReference = when (audioSource) {
            9 -> LevelReference(
                LevelReferenceMode.ANDROID_NOMINAL, 94.0 - 20 * log10(520.0 / 32768.0), inputKey,
                source = "UNPROCESSED",
                basis = "Android nominal microphone sensitivity: 520 PCM16 RMS at 94 dB SPL, 1 kHz",
            )
            6 -> LevelReference(
                LevelReferenceMode.ANDROID_NOMINAL, 90.0 - 20 * log10(2500.0 / 32768.0), inputKey,
                source = "VOICE_RECOGNITION",
                basis = "Android nominal microphone sensitivity: 2500 PCM16 RMS at 90 dB SPL, 1 kHz",
            )
            else -> digital().copy(inputKey = inputKey)
        }

        fun forInput(audioSource: Int, inputKey: String, calibration: Calibration? = null): LevelReference =
            if (calibration != null && calibration.inputKey == inputKey) fromCalibration(calibration)
            else androidNominal(audioSource, inputKey)
    }
}

/** Presentation only: never changes stored measurement values or digital negative levels. */
fun levelText(value: Double?, reference: LevelReference): String = when {
    value == null || !value.isFinite() -> "—"
    reference.isAcoustic && value < 0 -> "＜0"
    else -> String.format(Locale.ROOT, "%.1f", value)
}
