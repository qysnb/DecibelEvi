package com.decibelevidence.app.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EvidenceMigrationDeviceTest {
    @Test fun migratesV1WithoutChangingLegacyMeasurementsCalibrationOrMedia() = runBlocking {
        verifyMigration(1)
    }
    @Test fun migratesV2WithoutChangingReferencesOrGalleryLinks() = runBlocking {
        verifyMigration(2)
    }
    private suspend fun verifyMigration(version: Int) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "migration_${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        val calibration = "{\"offsetDb\":94,\"inputKey\":\"legacy\"}"
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, startedAtMs INTEGER NOT NULL, startNs INTEGER NOT NULL, endedAtMs INTEGER, status TEXT NOT NULL, averageDb REAL, maximumDb REAL, calibrated INTEGER NOT NULL, calibrationJson TEXT NOT NULL, inputKey TEXT NOT NULL, durationMs INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE media (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, kind TEXT NOT NULL, path TEXT NOT NULL, startedAtMs INTEGER NOT NULL, endedAtMs INTEGER, status TEXT NOT NULL, sha256 TEXT, FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_media_sessionId ON media(sessionId)")
            db.execSQL("INSERT INTO sessions VALUES ('legacy',100,200,300,'完成',45.5,60.5,1,?,'legacy',200)", arrayOf(calibration))
            db.execSQL("INSERT INTO media VALUES ('photo','legacy','photo','/unchanged.jpg',100,300,'完成','hash')")
            if (version == 2) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN levelReferenceJson TEXT")
                db.execSQL("ALTER TABLE media ADD COLUMN galleryUri TEXT")
                db.execSQL("UPDATE sessions SET levelReferenceJson = '{}' WHERE id = 'legacy'")
                db.execSQL("UPDATE media SET galleryUri = 'content://media/legacy' WHERE id = 'photo'")
            }
            db.version = version
        }
        val db = Room.databaseBuilder(context, EvidenceDatabase::class.java, name)
            .addMigrations(EvidenceDatabase.MIGRATION_1_2, EvidenceDatabase.MIGRATION_2_3).build()
        try {
            val row = db.evidence().session("legacy")!!
            assertEquals(45.5, row.averageDb!!, 0.0)
            assertEquals(60.5, row.maximumDb!!, 0.0)
            assertEquals(200L, row.durationMs)
            assertTrue(row.calibrated)
            assertEquals(calibration, row.calibrationJson)
            assertEquals(if (version == 2) "{}" else null, row.levelReferenceJson)
            assertNull(row.displayName)
            val media = db.evidence().media("legacy").single()
            assertEquals("/unchanged.jpg", media.path)
            assertEquals("hash", media.sha256)
            assertEquals(if (version == 2) "content://media/legacy" else null, media.galleryUri)
            db.evidence().saveSession(row.copy(status = "中断"))
            assertEquals(1, db.evidence().media("legacy").size)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
