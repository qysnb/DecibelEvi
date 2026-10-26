package com.decibelevidence.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class EvidenceSession(
    @PrimaryKey val id: String,
    val startedAtMs: Long, val startNs: Long, val endedAtMs: Long? = null,
    val status: String = "进行中", val averageDb: Double? = null,
    val maximumDb: Double? = null, val calibrated: Boolean = false,
    val calibrationJson: String = "null", val inputKey: String = "",
    val durationMs: Long = 0, val levelReferenceJson: String? = null,
    val displayName: String? = null
)
@Entity(tableName = "media", foreignKeys = [ForeignKey(entity = EvidenceSession::class,
    parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")])
data class MediaArtifact(
    @PrimaryKey val id: String, val sessionId: String, val kind: String,
    val path: String, val startedAtMs: Long, val endedAtMs: Long? = null,
    val status: String = "写入中", val sha256: String? = null,
    val galleryUri: String? = null
)
@Dao
interface EvidenceDao {
    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC") fun observeSessions(): Flow<List<EvidenceSession>>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: String): EvidenceSession?
    @Query("SELECT * FROM sessions WHERE status IN ('进行中', '保存失败') OR id IN (SELECT sessionId FROM media WHERE status = '写入中')") suspend fun needingRecovery(): List<EvidenceSession>
    @Upsert suspend fun saveSession(row: EvidenceSession)
    @Query("UPDATE sessions SET displayName = :name WHERE id = :id") suspend fun rename(id: String, name: String?)
    @Query("SELECT * FROM media WHERE sessionId = :id ORDER BY startedAtMs") suspend fun media(id: String): List<MediaArtifact>
    @Query("SELECT * FROM media WHERE id = :id") suspend fun mediaArtifact(id: String): MediaArtifact?
    @Query("UPDATE media SET galleryUri = :uri WHERE id = :id") suspend fun setGalleryUri(id: String, uri: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMedia(row: MediaArtifact)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: String)
}
@Database(entities = [EvidenceSession::class, MediaArtifact::class], version = 3, exportSchema = false)
abstract class EvidenceDatabase : RoomDatabase() {
    abstract fun evidence(): EvidenceDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN levelReferenceJson TEXT")
                db.execSQL("ALTER TABLE media ADD COLUMN galleryUri TEXT")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN displayName TEXT")
            }
        }
    }
}
