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

    @Query("SELECT * FROM sms_transfer WHERE transfer_id = :id")
    suspend fun getById(id: Long): SmsTransferEntity?

    @Query("SELECT * FROM sms_transfer WHERE vehicle_id = :vehicleId AND project_id = :projectId AND transfer_type = 'SEND'")
    suspend fun getSendByVehicle(vehicleId: Long, projectId: Int): List<SmsTransferEntity>

    /** Used to clean up dangling SEND transfer records when a PL is deleted before being received. */
    @Query("SELECT * FROM sms_transfer WHERE packing_list_id = :packingListId AND transfer_type = 'SEND'")
    suspend fun getSendByPackingList(packingListId: Long): List<SmsTransferEntity>

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

    /**
     * Un movimiento registrado en ESTE terminal, atado al viaje concreto (la packing list) en el
     * que ocurrió.
     *
     * El par (spool, packing list) no es un detalle: es lo que hace que el registro local **caduque
     * solo**. Un proceso NO lo termina el mismo terminal que lo empieza — el de taller envía y el
     * de laydown recibe, porque están en zonas distintas —, así que el registro local de cualquiera
     * de los dos es siempre una verdad a medias. Atado al viaje, deja de estorbar en cuanto empieza
     * el siguiente: si el spool entra en otra lista, este par ya no casa y manda el dato compartido.
     *
     * Antes esto era "todo spool que este terminal haya tocado alguna vez", sin más. Con eso, el
     * terminal que hubiera recibido un spool en laydown seguía dando ese spool por parado para
     * siempre, y no se enteraba nunca de que otro terminal lo había vuelto a mandar a site.
     */
    @Query("""
        SELECT DISTINCT ts.spool_id AS spool_id, t.packing_list_id AS packing_list_id
        FROM sms_transfer_spool ts
        INNER JOIN sms_transfer t ON t.transfer_id = ts.transfer_id
        WHERE t.transfer_type = 'RECEIVE'
    """)
    suspend fun getReceivedHere(): List<SpoolPlPair>

    @Query("DELETE FROM sms_transfer")
    suspend fun deleteAll()

    @Query("DELETE FROM sms_transfer_spool")
    suspend fun deleteAllSpools()
}

/** Un spool dentro de un viaje concreto. Ver [SmsTransferDao.getReceivedHere]. */
data class SpoolPlPair(val spool_id: Long, val packing_list_id: Long)
