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

    @Query("SELECT * FROM sms_transfer WHERE vehicle_id = :vehicleId AND project_id = :projectId AND transfer_type = 'SEND'")
    suspend fun getSendByVehicle(vehicleId: Long, projectId: Int): List<SmsTransferEntity>

    @Query("DELETE FROM sms_transfer_spool WHERE transfer_id IN (:ids)")
    suspend fun deleteSpoolsByTransferIds(ids: List<Long>)

    @Query("DELETE FROM sms_transfer WHERE transfer_id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM sms_transfer WHERE synced = 0")
    suspend fun getUnsynced(): List<SmsTransferEntity>

    @Query("UPDATE sms_transfer SET synced = 1 WHERE transfer_id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /** Fixes up transfers that reference a vehicle by its negative temp id once that vehicle's CREATE lands. */
    @Query("UPDATE sms_transfer SET vehicle_id = :serverId WHERE vehicle_id = :localId")
    suspend fun remapVehicleId(localId: Long, serverId: Long)

    /** Fixes up transfers that reference a PL by its negative temp id once that PL's CREATE lands. */
    @Query("UPDATE sms_transfer SET packing_list_id = :serverId WHERE packing_list_id = :localId")
    suspend fun remapPackingListId(localId: Long, serverId: Long)

    /** Fixes up transfer-spool rows that reference a spool by its negative temp id once that spool's CREATE lands. */
    @Query("UPDATE sms_transfer_spool SET spool_id = :serverId WHERE spool_id = :localId")
    suspend fun remapSpoolId(localId: Long, serverId: Long)

    @Query("SELECT * FROM sms_transfer_spool WHERE transfer_id = :transferId")
    suspend fun getSpoolsByTransfer(transferId: Long): List<SmsTransferSpoolEntity>

    @Query("SELECT DISTINCT s.spool_id FROM sms_transfer_spool s INNER JOIN sms_transfer t ON t.transfer_id = s.transfer_id WHERE t.synced = 0")
    suspend fun getSpoolIdsInUnsyncedTransfers(): List<Long>

    @Query("""
        SELECT DISTINCT ts.spool_id FROM sms_transfer_spool ts
        INNER JOIN sms_transfer t ON t.transfer_id = ts.transfer_id
        WHERE t.transfer_type = 'SEND'
        AND ts.spool_id NOT IN (
            SELECT ts2.spool_id FROM sms_transfer_spool ts2
            INNER JOIN sms_transfer t2 ON t2.transfer_id = ts2.transfer_id
            WHERE t2.transfer_type = 'RECEIVE'
        )
    """)
    suspend fun getSpoolIdsInSentNotReceived(): List<Long>

    @Query("DELETE FROM sms_transfer")
    suspend fun deleteAll()

    @Query("DELETE FROM sms_transfer_spool")
    suspend fun deleteAllSpools()
}
