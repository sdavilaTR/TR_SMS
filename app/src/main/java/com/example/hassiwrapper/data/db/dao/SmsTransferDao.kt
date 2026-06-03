package com.example.hassiwrapper.data.db.dao

import androidx.room.*
import com.example.hassiwrapper.data.db.entities.SmsTransferEntity
import com.example.hassiwrapper.data.db.entities.SmsTransferSpoolEntity

@Dao
interface SmsTransferDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: SmsTransferEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpools(spools: List<SmsTransferSpoolEntity>)

    @Query("SELECT * FROM sms_transfer WHERE project_id = :projectId ORDER BY created_at DESC")
    suspend fun getByProject(projectId: Int): List<SmsTransferEntity>

    @Query("SELECT * FROM sms_transfer WHERE synced = 0")
    suspend fun getUnsynced(): List<SmsTransferEntity>

    @Query("UPDATE sms_transfer SET synced = 1 WHERE transfer_id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT * FROM sms_transfer_spool WHERE transfer_id = :transferId")
    suspend fun getSpoolsByTransfer(transferId: Long): List<SmsTransferSpoolEntity>

    @Query("SELECT DISTINCT s.spool_id FROM sms_transfer_spool s INNER JOIN sms_transfer t ON t.transfer_id = s.transfer_id WHERE t.synced = 0")
    suspend fun getSpoolIdsInUnsyncedTransfers(): List<Long>

    @Query("DELETE FROM sms_transfer")
    suspend fun deleteAll()

    @Query("DELETE FROM sms_transfer_spool")
    suspend fun deleteAllSpools()
}
