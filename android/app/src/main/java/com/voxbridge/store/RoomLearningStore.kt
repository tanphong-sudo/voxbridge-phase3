package com.voxbridge.store

import android.content.Context
import androidx.room.Room
import com.voxbridge.core.learn.LearningExample
import com.voxbridge.core.learn.LearningStore
import com.voxbridge.core.model.Language
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom

/**
 * On-device, encrypted learning store — the Android binding of the core [LearningStore] port.
 *
 * The core's LocalMemoryRetriever/FeedbackPromoter logic (the Local Learning Loop, read/write-path
 * tested in self-verify) depends ONLY on this port, so wiring it here lights up the
 * MEMORY_TRANSLATION path with no change to the verified core.
 *
 * Fully implemented: Room (see [LearningDb]) opened through SQLCipher's `SupportFactory(byte[])`
 * (verified API), so the DB file is encrypted at rest — never uploaded (MODEL_MANIFEST §5, FR-15).
 * The passphrase is generated once and held in [SecureKey] (EncryptedSharedPreferences-backed), not
 * hard-coded.
 *
 * Robustness: every DB call is wrapped so a store fault degrades to "no memory" rather than
 * crashing `decide()` — matching the core's "store fault degrades to MT" guarantee. The engine then
 * simply falls through to neural MT (the safe default).
 */
class RoomLearningStore(context: Context) : LearningStore, AutoCloseable {

    private val db: LearningDb = run {
        val passphrase = SecureKey.getOrCreateDbKey(context)
        val factory = SupportFactory(passphrase)
        Room.databaseBuilder(context.applicationContext, LearningDb::class.java, "voxbridge_learning.db")
            .openHelperFactory(factory)
            .addMigrations(LearningDb.MIGRATION_1_2)
            .build()
    }
    private val dao = db.dao()

    override fun all(): List<LearningExample> =
        runCatching { dao.getAll().map { LearningMapper.toDomain(it) } }.getOrDefault(emptyList())

    override fun findExact(key: String, source: Language, target: Language): LearningExample? =
        runCatching { dao.findExact(key, source.name, target.name)?.let(LearningMapper::toDomain) }.getOrNull()

    override fun candidates(key: String, source: Language, target: Language, limit: Int): List<LearningExample> =
        runCatching {
            val count = limit.coerceIn(0, 256)
            if (count == 0) return@runCatching emptyList()
            val exact = dao.findExact(key, source.name, target.name)?.takeIf { it.rejectionCount == 0 }
            val recent = dao.recentCandidates(source.name, target.name, count)
            (listOfNotNull(exact) + recent.filter { it.normalizedSource != exact?.normalizedSource })
                .take(count).map(LearningMapper::toDomain)
        }.getOrDefault(emptyList())

    /**
     * Persist a promoted example (called from FeedbackPromoter Promote/HintOnly/Demoted outcomes).
     * Returns whether the write actually landed — the caller surfaces a failure instead of
     * reporting "Saved" for an example the encrypted DB silently refused.
     */
    fun upsert(example: LearningExample): Boolean =
        runCatching { dao.upsert(LearningMapper.toEntity(example)) }.isSuccess

    override fun close() = runCatching { db.close() }.getOrDefault(Unit)
}

/**
 * 32-byte DB passphrase, generated once and stored in EncryptedSharedPreferences (Android Keystore
 * master key). No secret is hard-coded or committed; the key never leaves the device.
 */
internal object SecureKey {
    private const val PREFS = "voxbridge_secure"
    private const val KEY = "db_passphrase_b64"

    fun getOrCreateDbKey(context: Context): ByteArray {
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            context,
            PREFS,
            androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.getString(KEY, null)?.let { return android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(KEY, android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)).apply()
        return key
    }
}
