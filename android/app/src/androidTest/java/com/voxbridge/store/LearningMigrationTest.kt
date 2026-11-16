package com.voxbridge.store

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on Android: migration SQL plus Room schema validation, without model inference. */
@RunWith(AndroidJUnit4::class)
class LearningMigrationTest {
    @Test fun v1MigrationPreservesFeedbackAndAllowsOppositeDirections() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "learning-migration-test.db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, 0, null).use { old ->
                old.execSQL("""CREATE TABLE learning_examples (
                    normalizedSource TEXT NOT NULL PRIMARY KEY, approvedTarget TEXT NOT NULL,
                    correctedSource TEXT, sourceLanguage TEXT NOT NULL, targetLanguage TEXT NOT NULL,
                    sourceFrameJson TEXT, targetFrameJson TEXT, feedbackType TEXT NOT NULL,
                    approvalCount INTEGER NOT NULL, rejectionCount INTEGER NOT NULL, lastUsedAtMs INTEGER NOT NULL)""")
                old.execSQL("INSERT INTO learning_examples VALUES ('hello','xin chao',NULL,'EN','VI',NULL,NULL,'APPROVED',7,0,123)")
                old.version = 1
            }
            val db = Room.databaseBuilder(context, LearningDb::class.java, name)
                .addMigrations(LearningDb.MIGRATION_1_2).build()
            try {
                    val dao = db.dao()
                    val preserved = requireNotNull(dao.findExact("hello", "EN", "VI"))
                    assertEquals(7, preserved.approvalCount)
                    assertEquals(123L, preserved.lastUsedAtMs)
                    dao.upsert(preserved.copy(sourceLanguage = "VI", targetLanguage = "EN", approvedTarget = "hello again"))
                    assertEquals("xin chao", dao.findExact("hello", "EN", "VI")?.approvedTarget)
                    assertEquals("hello again", dao.findExact("hello", "VI", "EN")?.approvedTarget)
                    assertEquals(1, dao.recentCandidates("VI", "EN", 1).size)
                    dao.delete("hello", "VI", "EN")
                    assertNotNull(dao.findExact("hello", "EN", "VI"))
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
