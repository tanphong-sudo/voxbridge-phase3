package com.voxbridge.store

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Index
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Query
import androidx.room.RoomDatabase
import com.voxbridge.core.learn.FeedbackType
import com.voxbridge.core.learn.LearningExample
import com.voxbridge.core.model.Language
import com.voxbridge.core.safety.HazardType
import com.voxbridge.core.safety.SafetyAction
import com.voxbridge.core.safety.SafetyFrame
import com.voxbridge.core.safety.SafetyObject
import com.voxbridge.core.safety.Urgency
import org.json.JSONObject

/**
 * Room persistence for the Local Learning Loop. Fully implemented (no model needed) — this is plain
 * Android storage. The encryption (SQLCipher) is wired in [RoomLearningStore].
 *
 * The direction plus `normalizedSource` is the key: one promoted example per utterance/direction, so a repeat
 * approval upserts (raising approvalCount) rather than duplicating. The SafetyFrames are stored as
 * compact JSON via [SafetyFrameJson] so the retriever's frame-similarity scoring survives a reload.
 */
@Entity(tableName = "learning_examples",
    primaryKeys = ["normalizedSource", "sourceLanguage", "targetLanguage"],
    indices = [Index(value = ["sourceLanguage", "targetLanguage", "rejectionCount", "lastUsedAtMs"])])
data class LearningExampleEntity(
    val normalizedSource: String,
    val approvedTarget: String,
    val correctedSource: String?,
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceFrameJson: String?,
    val targetFrameJson: String?,
    val feedbackType: String,
    val approvalCount: Int,
    val rejectionCount: Int,
    val lastUsedAtMs: Long,
)

@Dao
interface LearningDao {
    @Query("SELECT * FROM learning_examples")
    fun getAll(): List<LearningExampleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: LearningExampleEntity)

    @Query("SELECT * FROM learning_examples WHERE normalizedSource = :key AND sourceLanguage = :source AND targetLanguage = :target LIMIT 1")
    fun findExact(key: String, source: String, target: String): LearningExampleEntity?

    @Query("SELECT * FROM learning_examples WHERE sourceLanguage = :source AND targetLanguage = :target AND rejectionCount = 0 ORDER BY lastUsedAtMs DESC LIMIT :limit")
    fun recentCandidates(source: String, target: String, limit: Int): List<LearningExampleEntity>

    @Query("DELETE FROM learning_examples WHERE normalizedSource = :key AND sourceLanguage = :source AND targetLanguage = :target")
    fun delete(key: String, source: String, target: String)
}

@Database(entities = [LearningExampleEntity::class], version = 2, exportSchema = true)
abstract class LearningDb : RoomDatabase() {
    abstract fun dao(): LearningDao

    companion object {
        /** Copy every existing row; never discard the user's approved translations. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE learning_examples_v2 (
                    normalizedSource TEXT NOT NULL, approvedTarget TEXT NOT NULL, correctedSource TEXT,
                    sourceLanguage TEXT NOT NULL, targetLanguage TEXT NOT NULL, sourceFrameJson TEXT,
                    targetFrameJson TEXT, feedbackType TEXT NOT NULL, approvalCount INTEGER NOT NULL,
                    rejectionCount INTEGER NOT NULL, lastUsedAtMs INTEGER NOT NULL,
                    PRIMARY KEY(normalizedSource, sourceLanguage, targetLanguage))""")
                db.execSQL("INSERT INTO learning_examples_v2 SELECT * FROM learning_examples")
                db.execSQL("DROP TABLE learning_examples")
                db.execSQL("ALTER TABLE learning_examples_v2 RENAME TO learning_examples")
                db.execSQL("CREATE INDEX index_learning_examples_sourceLanguage_targetLanguage_rejectionCount_lastUsedAtMs ON learning_examples(sourceLanguage, targetLanguage, rejectionCount, lastUsedAtMs)")
            }
        }
    }
}

/** Maps the Room entity to/from the core [LearningExample] (domain) type. */
internal object LearningMapper {
    fun toDomain(e: LearningExampleEntity) = LearningExample(
        normalizedSource = e.normalizedSource,
        approvedTarget = e.approvedTarget,
        correctedSource = e.correctedSource,
        sourceLanguage = Language.valueOf(e.sourceLanguage),
        targetLanguage = Language.valueOf(e.targetLanguage),
        sourceFrame = e.sourceFrameJson?.let { SafetyFrameJson.decode(it) },
        targetFrame = e.targetFrameJson?.let { SafetyFrameJson.decode(it) },
        feedbackType = FeedbackType.valueOf(e.feedbackType),
        approvalCount = e.approvalCount,
        rejectionCount = e.rejectionCount,
        lastUsedAtMs = e.lastUsedAtMs,
    )

    fun toEntity(x: LearningExample) = LearningExampleEntity(
        normalizedSource = x.normalizedSource,
        approvedTarget = x.approvedTarget,
        correctedSource = x.correctedSource,
        sourceLanguage = x.sourceLanguage.name,
        targetLanguage = x.targetLanguage.name,
        sourceFrameJson = x.sourceFrame?.let { SafetyFrameJson.encode(it) },
        targetFrameJson = x.targetFrame?.let { SafetyFrameJson.encode(it) },
        feedbackType = x.feedbackType.name,
        approvalCount = x.approvalCount,
        rejectionCount = x.rejectionCount,
        lastUsedAtMs = x.lastUsedAtMs,
    )
}

/** Compact, dependency-light JSON round-trip for SafetyFrame (enums by name, nullables omitted). */
internal object SafetyFrameJson {
    fun encode(f: SafetyFrame): String = JSONObject().apply {
        put("language", f.language.name)
        f.action?.let { put("action", it.name) }
        f.objectType?.let { put("objectType", it.name) }
        f.objectId?.let { put("objectId", it) }
        f.location?.let { put("location", it) }
        f.negation?.let { put("negation", it) }
        f.urgency?.let { put("urgency", it.name) }
        f.hazard?.let { put("hazard", it.name) }
        f.quantity?.let { put("quantity", it) }
        put("confidence", f.confidence)
        f.activationEligible?.let { put("activationEligible", it) }
    }.toString()

    fun decode(s: String): SafetyFrame {
        val j = JSONObject(s)
        return SafetyFrame(
            language = Language.valueOf(j.getString("language")),
            action = j.optString("action").ifEmpty { null }?.let { SafetyAction.valueOf(it) },
            objectType = j.optString("objectType").ifEmpty { null }?.let { SafetyObject.valueOf(it) },
            objectId = j.optString("objectId").ifEmpty { null },
            location = j.optString("location").ifEmpty { null },
            negation = if (j.has("negation")) j.getBoolean("negation") else null,
            urgency = j.optString("urgency").ifEmpty { null }?.let { Urgency.valueOf(it) },
            hazard = j.optString("hazard").ifEmpty { null }?.let { HazardType.valueOf(it) },
            quantity = j.optString("quantity").ifEmpty { null },
            confidence = j.optDouble("confidence", 0.0),
            activationEligible = if (j.has("activationEligible")) j.getBoolean("activationEligible") else null,
        )
    }
}
