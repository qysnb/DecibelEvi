package com.decibelevidence.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.decibelevidence.core.LevelReference
import com.decibelevidence.core.Calibration
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EvidenceMetadataDeviceTest {
    @Test fun preservesLegacyUnitsAndExportsExplicitReferenceForNewRecords() = runBlocking {
        val repo = EvidenceRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val nominal = LevelReference.androidNominal(9, "built-in")
        val calibration = Calibration(94.0, 100L, "built-in", 70.0)
        val user = LevelReference.fromCalibration(calibration)
        val cases = listOf(
            Triple(null, false, "A-weighted dBFS"), Triple(null, true, "estimated dB(A)"),
            Triple(LevelReferenceJson.encode(nominal), false, "estimated dB(A)"),
            Triple(LevelReferenceJson.encode(user), true, "estimated dB(A)"),
            Triple(LevelReferenceJson.encode(LevelReference.digital()), false, "A-weighted dBFS")
        )
        for ((reference, calibrated, unit) in cases) {
            val calibrationJson = if (calibrated) CalibrationStore.json(calibration).toString() else "null"
            val row = EvidenceSession("metadata_test_${UUID.randomUUID()}", 100, 200, status = "完成",
                calibrated = calibrated, calibrationJson = calibrationJson, levelReferenceJson = reference)
            try {
                repo.create(row)
                repo.export(row.id)
                val metadata = JSONObject(File(repo.directory(row.id), "metadata.json").readText())
                assertEquals(if (reference == null) 1 else 2, metadata.getInt("formatVersion"))
                assertEquals(unit, metadata.getString("unit"))
                assertEquals(calibrated, metadata.getBoolean("calibrated"))
                if (reference == null) assertFalse(metadata.has("levelReference"))
                else assertEquals(JSONObject(reference).getString("mode"), metadata.getJSONObject("levelReference").getString("mode"))
                if (!calibrated) assertTrue(metadata.isNull("calibration"))
                assertEquals("elapsed_second,wall_time_iso,second_energy_db,second_fast_max_db,cumulative_energy_db,cumulative_fast_max_db,calibrated,valid",
                    File(repo.directory(row.id), "measurements.csv").readLines().first())
            } finally { repo.delete(row.id) }
        }
    }
}
