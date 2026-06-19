package com.example.hassiwrapper.data.db.dao

import androidx.room.*
import com.example.hassiwrapper.data.db.entities.SmsOutboxEntity
import com.example.hassiwrapper.data.db.entities.SmsIdMapEntity

@Dao
interface SmsOutboxDao {

    // ── Outbox queue ──────────────────────────────────────────────────────────

    @Insert
    suspend fun insert(op: SmsOutboxEntity): Long

    /** Pending ops in insertion (op_id) order — the drain order. */
    @Query("SELECT * FROM sms_outbox WHERE status = 'PENDING' ORDER BY op_id ASC")
    suspend fun getPending(): List<SmsOutboxEntity>

    @Query("SELECT COUNT(*) FROM sms_outbox WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("UPDATE sms_outbox SET status = 'DONE' WHERE op_id = :opId")
    suspend fun markDone(opId: Long)

    @Query("UPDATE sms_outbox SET status = 'FAILED', attempts = attempts + 1, last_error = :error WHERE op_id = :opId")
    suspend fun markFailed(opId: Long, error: String?)

    @Query("UPDATE sms_outbox SET attempts = attempts + 1, last_error = :error WHERE op_id = :opId")
    suspend fun recordAttempt(opId: Long, error: String?)

    /**
     * Local entity ids with a PENDING DELETE op, used by the download-merge to hide
     * rows the user deleted offline so the server copy doesn't resurrect them.
     */
    @Query("SELECT local_entity_id FROM sms_outbox WHERE status = 'PENDING' AND op_type = 'DELETE' AND entity_type = :entityType")
    suspend fun pendingDeleteIds(entityType: String): List<Long>

    @Query("DELETE FROM sms_outbox WHERE status = 'DONE'")
    suspend fun pruneDone()

    // ── Local→server id reconciliation ────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMapping(mapping: SmsIdMapEntity)

    @Query("SELECT server_id FROM sms_id_map WHERE entity_type = :entityType AND local_id = :localId")
    suspend fun serverIdFor(entityType: String, localId: Long): Long?
}
