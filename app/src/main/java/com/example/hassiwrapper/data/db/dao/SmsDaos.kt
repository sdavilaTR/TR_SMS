package com.example.hassiwrapper.data.db.dao

import androidx.room.*
import com.example.hassiwrapper.data.db.entities.*

@Dao
interface SmsAreaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(areas: List<SmsAreaEntity>)

    @Query("SELECT * FROM sms_area WHERE project_id = :projectId AND is_active = 1 ORDER BY full_path ASC")
    suspend fun getByProject(projectId: Int): List<SmsAreaEntity>

    @Query("SELECT * FROM sms_area WHERE area_id = :id")
    suspend fun getById(id: Long): SmsAreaEntity?

    @Query("SELECT COUNT(*) FROM sms_area WHERE project_id = :projectId")
    suspend fun countByProject(projectId: Int): Int

    @Query("DELETE FROM sms_area WHERE project_id = :projectId")
    suspend fun deleteByProject(projectId: Int)

    @Query("DELETE FROM sms_area")
    suspend fun deleteAll()
}

@Dao
interface SmsBoreSizeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsBoreSizeEntity>)

    @Query("SELECT * FROM sms_bore_size WHERE is_active = 1 ORDER BY sort_order ASC")
    suspend fun getAll(): List<SmsBoreSizeEntity>

    @Query("SELECT * FROM sms_bore_size WHERE bore_size_id = :id")
    suspend fun getById(id: Int): SmsBoreSizeEntity?

    @Query("DELETE FROM sms_bore_size")
    suspend fun deleteAll()
}

@Dao
interface SmsIncompleteStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsIncompleteStatusEntity>)

    @Query("SELECT * FROM sms_incomplete_status WHERE is_active = 1 ORDER BY sort_order ASC")
    suspend fun getAll(): List<SmsIncompleteStatusEntity>

    @Query("SELECT * FROM sms_incomplete_status WHERE incomplete_status_id = :id")
    suspend fun getById(id: Int): SmsIncompleteStatusEntity?

    @Query("DELETE FROM sms_incomplete_status")
    suspend fun deleteAll()
}

@Dao
interface SmsIsoTypeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsIsoTypeEntity>)

    @Query("SELECT * FROM sms_iso_type WHERE is_active = 1 ORDER BY sort_order ASC")
    suspend fun getAll(): List<SmsIsoTypeEntity>

    @Query("SELECT * FROM sms_iso_type WHERE iso_type_id = :id")
    suspend fun getById(id: Int): SmsIsoTypeEntity?

    @Query("DELETE FROM sms_iso_type")
    suspend fun deleteAll()
}

@Dao
interface SmsPackingListDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsPackingListEntity>)

    @Query("SELECT * FROM sms_packing_list WHERE project_id = :projectId AND is_active = 1 ORDER BY packing_date DESC")
    suspend fun getByProject(projectId: Int): List<SmsPackingListEntity>

    @Query("SELECT * FROM sms_packing_list WHERE vehicle_id = :vehicleId AND is_active = 1 ORDER BY packing_date DESC")
    suspend fun getByVehicle(vehicleId: Long): List<SmsPackingListEntity>

    @Query("SELECT * FROM sms_packing_list WHERE vehicle_plate = :plate AND is_active = 1 ORDER BY packing_date DESC")
    suspend fun getByVehiclePlate(plate: String): List<SmsPackingListEntity>

    @Query("SELECT DISTINCT pl.* FROM sms_packing_list pl INNER JOIN sms_spool s ON s.packing_list_id = pl.packing_list_id WHERE pl.vehicle_id = :vehicleId AND s.in_transit = 1 AND pl.is_active = 1 ORDER BY pl.packing_date DESC")
    suspend fun getWithInTransitSpoolsByVehicle(vehicleId: Long): List<SmsPackingListEntity>

    @Query("SELECT * FROM sms_packing_list WHERE packing_list_id = :id")
    suspend fun getById(id: Long): SmsPackingListEntity?

    @Query("SELECT COUNT(*) FROM sms_packing_list WHERE project_id = :projectId")
    suspend fun countByProject(projectId: Int): Int

    @Query("SELECT MAX(packing_list_id) FROM sms_packing_list")
    suspend fun getMaxId(): Long?

    @Query("SELECT MIN(packing_list_id) FROM sms_packing_list")
    suspend fun getMinId(): Long?

    @Query("SELECT COUNT(*) FROM sms_packing_list WHERE project_id = :projectId AND packing_list_name LIKE :prefix || '%'")
    suspend fun countByNamePrefix(projectId: Int, prefix: String): Int

    @Query("SELECT COUNT(*) FROM sms_packing_list WHERE project_id = :projectId AND position_id = :positionId AND vehicle_id = :vehicleId")
    suspend fun countByProjectPositionVehicle(projectId: Int, positionId: Int, vehicleId: Long): Int

    @Query("SELECT COUNT(*) FROM sms_packing_list WHERE project_id = :projectId AND position_id = :positionId AND vehicle_id IS NULL")
    suspend fun countByProjectPositionNoVehicle(projectId: Int, positionId: Int): Int

    @Query("SELECT * FROM sms_packing_list WHERE project_id = :projectId AND ready_to_send = 1 AND is_active = 1 ORDER BY packing_date DESC")
    suspend fun getReadyToSend(projectId: Int): List<SmsPackingListEntity>

    @Query("SELECT * FROM sms_packing_list WHERE project_id = :projectId AND position_id = :positionId AND ready_to_send = 1 AND is_active = 1 ORDER BY packing_date DESC")
    suspend fun getReadyToSendByPosition(projectId: Int, positionId: Int): List<SmsPackingListEntity>

    @Query("UPDATE sms_packing_list SET ready_to_send = :value WHERE packing_list_id = :id")
    suspend fun setReadyToSend(id: Long, value: Boolean)

    @Query("UPDATE sms_packing_list SET position_id = :positionId, position = :positionCode, synced = 0 WHERE packing_list_id = :id")
    suspend fun updatePosition(id: Long, positionId: Int?, positionCode: String?)

    @Query("UPDATE sms_packing_list SET vehicle_id = :vehicleId, vehicle_plate = :vehiclePlate WHERE packing_list_id = :id")
    suspend fun setVehicle(id: Long, vehicleId: Long, vehiclePlate: String)

    @Query("SELECT * FROM sms_packing_list WHERE synced = 0")
    suspend fun getUnsynced(): List<SmsPackingListEntity>

    @Query("UPDATE sms_packing_list SET synced = 1 WHERE packing_list_id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM sms_packing_list WHERE packing_list_id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sms_packing_list WHERE project_id = :projectId")
    suspend fun deleteByProject(projectId: Int)

    @Query("DELETE FROM sms_packing_list WHERE project_id = :projectId AND synced = 1")
    suspend fun deleteSyncedByProject(projectId: Int)

    @Query("DELETE FROM sms_packing_list WHERE is_active = 0")
    suspend fun deleteInactive()

    @Query("DELETE FROM sms_packing_list")
    suspend fun deleteAll()
}

@Dao
interface SmsPackingListSpoolDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsPackingListSpoolEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SmsPackingListSpoolEntity)

    @Query("SELECT * FROM sms_packing_list_spool WHERE packing_list_id = :packingListId ORDER BY sequence_number ASC")
    suspend fun getByPackingList(packingListId: Long): List<SmsPackingListSpoolEntity>

    @Query("SELECT spool_id FROM sms_packing_list_spool WHERE packing_list_id = :packingListId")
    suspend fun getSpoolIdsByPackingList(packingListId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM sms_packing_list_spool WHERE packing_list_id = :packingListId")
    suspend fun countByPackingList(packingListId: Long): Int

    @Query("DELETE FROM sms_packing_list_spool WHERE packing_list_id = :packingListId")
    suspend fun deleteByPackingList(packingListId: Long)

    @Query("SELECT MAX(packing_list_spool_id) FROM sms_packing_list_spool")
    suspend fun getMaxId(): Long?

    @Query("DELETE FROM sms_packing_list_spool WHERE spool_id = :spoolId")
    suspend fun deleteBySpoolId(spoolId: Long)

    @Query("DELETE FROM sms_packing_list_spool")
    suspend fun deleteAll()
}

@Dao
interface SmsPositionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsPositionEntity>)

    @Query("SELECT * FROM sms_position WHERE is_active = 1 ORDER BY sort_order ASC")
    suspend fun getAll(): List<SmsPositionEntity>

    @Query("SELECT * FROM sms_position WHERE position_id = :id")
    suspend fun getById(id: Int): SmsPositionEntity?

    @Query("SELECT * FROM sms_position WHERE UPPER(code) = UPPER(:code) LIMIT 1")
    suspend fun getByCode(code: String): SmsPositionEntity?

    @Query("DELETE FROM sms_position")
    suspend fun deleteAll()
}

@Dao
interface SmsSubPositionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsSubPositionEntity>)

    @Query("SELECT * FROM sms_sub_position WHERE project_id = :projectId AND is_active = 1 ORDER BY full_path ASC")
    suspend fun getByProject(projectId: Int): List<SmsSubPositionEntity>

    @Query("SELECT * FROM sms_sub_position WHERE project_id = :projectId AND position_id = :positionId AND is_active = 1 ORDER BY full_path ASC")
    suspend fun getByPosition(projectId: Int, positionId: Int): List<SmsSubPositionEntity>

    @Query("SELECT * FROM sms_sub_position WHERE sub_position_id = :id")
    suspend fun getById(id: Long): SmsSubPositionEntity?

    @Query("DELETE FROM sms_sub_position WHERE project_id = :projectId")
    suspend fun deleteByProject(projectId: Int)
}

@Dao
interface SmsSpecDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsSpecEntity>)

    @Query("SELECT * FROM sms_spec WHERE project_id = :projectId AND is_active = 1 ORDER BY code ASC")
    suspend fun getByProject(projectId: Int): List<SmsSpecEntity>

    @Query("SELECT * FROM sms_spec WHERE spec_id = :id")
    suspend fun getById(id: Long): SmsSpecEntity?

    @Query("DELETE FROM sms_spec WHERE project_id = :projectId")
    suspend fun deleteByProject(projectId: Int)

    @Query("DELETE FROM sms_spec")
    suspend fun deleteAll()
}

/** Spool count per raw location combo (packing list, zone, position, sub-position).
 *  Few distinct combos exist per project, so the caller resolves each combo to a
 *  position code in Kotlin instead of paying per-row SQL subqueries over 100k+ rows. */
data class SpoolComboCount(val plId: Long?, val zone: String?, val positionId: Int?, val subId: Long?, val cnt: Int)

/**
 * SQL twin of CreateSpoolFragment.spoolPositionCode() — resolves a spool's position code
 * (WORKSHOP/LAYDOWN/SITE) with the same 3-level fallback, uppercased:
 * 1. its packing list's position string, 2. zone exact/prefix match against sms_position
 * codes, 3. the spool's own position_id. Keep both in sync.
 */
private const val SPOOL_RESOLVED_POSITION = """
    COALESCE(
        NULLIF(UPPER((SELECT pl.position FROM sms_packing_list pl WHERE pl.packing_list_id = s.packing_list_id)), ''),
        (SELECT UPPER(p.code) FROM sms_position p
           WHERE UPPER(s.zone) = UPPER(p.code) OR UPPER(s.zone) LIKE UPPER(p.code) || '/%'
           LIMIT 1),
        (SELECT UPPER(p2.code) FROM sms_position p2 WHERE p2.position_id = s.position_id)
    )
"""

private const val SPOOL_LIST_FILTER = """
    s.project_id = :projectId AND s.is_active = 1
    AND (:positionCode IS NULL OR $SPOOL_RESOLVED_POSITION = UPPER(:positionCode))
    AND (:subPositionId IS NULL OR s.sub_position_id = :subPositionId)
    AND (:query = '' OR s.spool_code LIKE '%' || :query || '%'
         OR s.spool_suffix LIKE '%' || :query || '%'
         OR (s.spool_code || IFNULL(s.spool_suffix, '')) LIKE '%' || :query || '%'
         OR s.line_code LIKE '%' || :query || '%'
         OR s.zone LIKE '%' || :query || '%'
         OR EXISTS(SELECT 1 FROM sms_packing_list plq
                   WHERE plq.packing_list_id = s.packing_list_id
                     AND plq.packing_list_name LIKE '%' || :query || '%'))
"""

@Dao
interface SmsSpoolDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(spools: List<SmsSpoolEntity>)

    @Query("""
        SELECT s.packing_list_id AS plId, s.zone AS zone, s.position_id AS positionId,
               s.sub_position_id AS subId, COUNT(*) AS cnt
        FROM sms_spool s
        WHERE s.project_id = :projectId AND s.is_active = 1
        GROUP BY plId, zone, positionId, subId
    """)
    suspend fun countByLocationCombo(projectId: Int): List<SpoolComboCount>

    @Query("""
        SELECT s.* FROM sms_spool s
        WHERE $SPOOL_LIST_FILTER
        ORDER BY s.spool_code ASC, s.spool_suffix ASC
        LIMIT :limit
    """)
    suspend fun getFilteredPage(projectId: Int, positionCode: String?, subPositionId: Long?, query: String, limit: Int): List<SmsSpoolEntity>

    @Query("SELECT COUNT(*) FROM sms_spool s WHERE $SPOOL_LIST_FILTER")
    suspend fun countFiltered(projectId: Int, positionCode: String?, subPositionId: Long?, query: String): Int

    @Query("SELECT * FROM sms_spool WHERE project_id = :projectId AND is_active = 1 ORDER BY spool_code ASC, spool_suffix ASC")
    suspend fun getByProject(projectId: Int): List<SmsSpoolEntity>

    @Query("SELECT * FROM sms_spool WHERE project_id = :projectId AND is_active = 1 AND packing_list_id IN (SELECT packing_list_id FROM sms_packing_list WHERE UPPER(position) = UPPER(:location)) ORDER BY spool_code ASC, spool_suffix ASC")
    suspend fun getByProjectAndZone(projectId: Int, location: String): List<SmsSpoolEntity>

    @Query("SELECT * FROM sms_spool WHERE project_id = :projectId AND packing_list_id IN (SELECT packing_list_id FROM sms_packing_list WHERE UPPER(position) = UPPER(:location)) ORDER BY spool_code ASC, spool_suffix ASC")
    suspend fun getByProjectIgnoreActiveAndZone(projectId: Int, location: String): List<SmsSpoolEntity>

    @Query("SELECT COUNT(*) FROM sms_spool WHERE project_id = :projectId AND is_active = 1 AND packing_list_id IN (SELECT packing_list_id FROM sms_packing_list WHERE UPPER(position) = UPPER(:location))")
    suspend fun countActiveByProjectAndZone(projectId: Int, location: String): Int

    @Query("SELECT * FROM sms_spool WHERE spool_id = :id")
    suspend fun getById(id: Long): SmsSpoolEntity?

    @Query("SELECT * FROM sms_spool WHERE spool_code = :code ORDER BY spool_suffix ASC")
    suspend fun getByCode(code: String): List<SmsSpoolEntity>

    @Query("SELECT * FROM sms_spool WHERE packing_list_id = :packingListId ORDER BY spool_code ASC, spool_suffix ASC")
    suspend fun getByPackingList(packingListId: Long): List<SmsSpoolEntity>

    @Query("""
        SELECT * FROM sms_spool
        WHERE project_id = :projectId
        AND (spool_code LIKE '%' || :query || '%'
            OR spool_suffix LIKE '%' || :query || '%'
            OR line_code LIKE '%' || :query || '%')
        ORDER BY spool_code ASC
    """)
    suspend fun search(projectId: Int, query: String): List<SmsSpoolEntity>

    @Query("SELECT COUNT(*) FROM sms_spool WHERE project_id = :projectId")
    suspend fun countByProject(projectId: Int): Int

    @Query("SELECT * FROM sms_spool WHERE project_id = :projectId AND packing_list_id IS NULL AND is_active = 1 ORDER BY spool_code ASC, spool_suffix ASC")
    suspend fun getWithoutPackingList(projectId: Int): List<SmsSpoolEntity>

    @Query("SELECT COUNT(*) FROM sms_spool WHERE project_id = :projectId AND is_active = 1")
    suspend fun countActiveByProject(projectId: Int): Int

    @Query("SELECT COUNT(*) FROM sms_spool")
    suspend fun countAll(): Int

    @Query("SELECT DISTINCT project_id FROM sms_spool")
    suspend fun distinctProjectIds(): List<Int>

    @Query("SELECT * FROM sms_spool WHERE project_id = :projectId ORDER BY spool_code ASC, spool_suffix ASC")
    suspend fun getByProjectIgnoreActive(projectId: Int): List<SmsSpoolEntity>

    @Query("DELETE FROM sms_spool WHERE spool_id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sms_spool WHERE spool_id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM sms_spool WHERE project_id = :projectId")
    suspend fun deleteByProject(projectId: Int)

    @Query("DELETE FROM sms_spool WHERE is_active = 0")
    suspend fun deleteInactive()

    @Query("DELETE FROM sms_spool")
    suspend fun deleteAll()

    @Query("SELECT MAX(spool_id) FROM sms_spool")
    suspend fun getMaxId(): Long?

    @Query("SELECT MIN(spool_id) FROM sms_spool")
    suspend fun getMinId(): Long?

    @Query("SELECT COUNT(*) FROM sms_spool WHERE project_id = :projectId AND spool_code = :spoolCode")
    suspend fun countByProjectAndCode(projectId: Int, spoolCode: String): Int

    @Query("SELECT DISTINCT train FROM sms_spool WHERE project_id = :projectId AND train IS NOT NULL AND train != '' ORDER BY train ASC")
    suspend fun getDistinctTrains(projectId: Int): List<String>

    @Query("SELECT DISTINCT spool_code FROM sms_spool WHERE project_id = :projectId AND spool_code != ''")
    suspend fun getDistinctSpoolCodes(projectId: Int): List<String>

    @Query("SELECT COUNT(*) FROM sms_spool WHERE packing_list_id = :packingListId")
    suspend fun countByPackingList(packingListId: Long): Int

    @Query("SELECT * FROM sms_spool WHERE synced = 0")
    suspend fun getUnsynced(): List<SmsSpoolEntity>

    /** Spools modified offline via QR relocation / RECEIVE: position or sub-position set locally
     *  but not yet uploaded to the server as a status-flags PUT. Only positive IDs — temp local
     *  IDs (offline-created spools) are handled by the outbox, not status-flags. */
    @Query("SELECT * FROM sms_spool WHERE synced = 0 AND spool_id > 0 AND position_id IS NOT NULL")
    suspend fun getUnsyncedRelocated(): List<SmsSpoolEntity>

    @Query("UPDATE sms_spool SET synced = 1 WHERE spool_id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("UPDATE sms_spool SET spool_id = :serverId, synced = 1 WHERE spool_id = :localId")
    suspend fun remapAndSync(localId: Long, serverId: Long)

    @Query("DELETE FROM sms_spool WHERE project_id = :projectId AND synced = 1")
    suspend fun deleteSyncedByProject(projectId: Int)

    @Query("UPDATE sms_spool SET packing_list_id = :packingListId, synced = 0 WHERE spool_id = :spoolId")
    suspend fun updatePackingList(spoolId: Long, packingListId: Long?)

    @Query("SELECT * FROM sms_spool WHERE project_id = :projectId AND UPPER(spool_code) = UPPER(:code) AND UPPER(COALESCE(spool_suffix,'')) = UPPER(:suffix) LIMIT 1")
    suspend fun findByCodeAndSuffix(projectId: Int, code: String, suffix: String): SmsSpoolEntity?

    @Query("SELECT * FROM sms_spool WHERE project_id = :projectId AND UPPER(spool_code) = UPPER(:code) LIMIT 1")
    suspend fun findByCode(projectId: Int, code: String): SmsSpoolEntity?

    @Query("UPDATE sms_spool SET in_transit = :inTransit, synced = 0 WHERE spool_id = :spoolId")
    suspend fun updateInTransit(spoolId: Long, inTransit: Boolean)

    @Query("UPDATE sms_spool SET zone = :zone, assigned_unit = :assignedUnit, in_transit = 0, synced = 0 WHERE spool_id = :spoolId")
    suspend fun updateZoneAndUnit(spoolId: Long, zone: String?, assignedUnit: String?)

    @Query("UPDATE sms_spool SET position_id = :positionId, synced = 0 WHERE spool_id = :spoolId")
    suspend fun updatePosition(spoolId: Long, positionId: Int?)

    @Query("UPDATE sms_spool SET sub_position_id = :subPositionId, synced = 0 WHERE spool_id = :spoolId")
    suspend fun updateSubPosition(spoolId: Long, subPositionId: Long?)

    @Query("UPDATE sms_spool SET area_id = :areaId, zone = :zone, synced = 0 WHERE spool_id = :spoolId")
    suspend fun updateArea(spoolId: Long, areaId: Long?, zone: String?)

    @Query("UPDATE sms_spool SET unit_id = :unitId, assigned_unit = :assignedUnit, synced = 0 WHERE spool_id = :spoolId")
    suspend fun updateUnit(spoolId: Long, unitId: Int?, assignedUnit: String?)

    @Query("SELECT * FROM sms_spool WHERE packing_list_id = :packingListId AND in_transit = 1")
    suspend fun getInTransitByPackingList(packingListId: Long): List<SmsSpoolEntity>

    @Query("SELECT COUNT(*) FROM sms_spool WHERE project_id = :projectId AND in_transit = 1")
    suspend fun countInTransitByProject(projectId: Int): Int
}

@Dao
interface SmsSpoolEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<SmsSpoolEventEntity>)

    @Query("SELECT * FROM sms_spool_event WHERE spool_id = :spoolId ORDER BY event_date DESC")
    suspend fun getBySpool(spoolId: Long): List<SmsSpoolEventEntity>

    @Query("DELETE FROM sms_spool_event WHERE spool_id = :spoolId")
    suspend fun deleteBySpool(spoolId: Long)

    @Query("DELETE FROM sms_spool_event")
    suspend fun deleteAll()
}

@Dao
interface SmsSpoolPropertyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsSpoolPropertyEntity>)

    @Query("SELECT * FROM sms_spool_property WHERE spool_id = :spoolId")
    suspend fun getBySpool(spoolId: Long): SmsSpoolPropertyEntity?

    @Query("DELETE FROM sms_spool_property WHERE spool_id = :spoolId")
    suspend fun deleteBySpool(spoolId: Long)

    @Query("DELETE FROM sms_spool_property")
    suspend fun deleteAll()
}

@Dao
interface SmsSpoolStatusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsSpoolStatusEntity>)

    @Query("SELECT * FROM sms_spool_status WHERE is_active = 1 ORDER BY sort_order ASC")
    suspend fun getAll(): List<SmsSpoolStatusEntity>

    @Query("SELECT * FROM sms_spool_status WHERE status_id = :id")
    suspend fun getById(id: Int): SmsSpoolStatusEntity?

    @Query("SELECT * FROM sms_spool_status WHERE UPPER(code) = UPPER(:code) LIMIT 1")
    suspend fun getByCode(code: String): SmsSpoolStatusEntity?

    @Query("DELETE FROM sms_spool_status")
    suspend fun deleteAll()
}

@Dao
interface SmsSpoolStatusFlagsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsSpoolStatusFlagsEntity>)

    @Query("SELECT * FROM sms_spool_status_flags WHERE spool_id = :spoolId")
    suspend fun getBySpool(spoolId: Long): SmsSpoolStatusFlagsEntity?

    @Query("SELECT * FROM sms_spool_status_flags WHERE hold = 1")
    suspend fun getOnHold(): List<SmsSpoolStatusFlagsEntity>

    @Query("SELECT * FROM sms_spool_status_flags WHERE damaged = 1")
    suspend fun getDamaged(): List<SmsSpoolStatusFlagsEntity>

    @Query("UPDATE sms_spool_status_flags SET spool_id = :serverId WHERE spool_id = :localId")
    suspend fun remapSpoolId(localId: Long, serverId: Long)

    @Query("DELETE FROM sms_spool_status_flags WHERE spool_id = :spoolId")
    suspend fun deleteBySpool(spoolId: Long)

    @Query("DELETE FROM sms_spool_status_flags")
    suspend fun deleteAll()
}

@Dao
interface SmsSubcontractorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsSubcontractorEntity>)

    @Query("SELECT * FROM sms_subcontractor WHERE project_id = :projectId AND is_active = 1 ORDER BY name ASC")
    suspend fun getByProject(projectId: Int): List<SmsSubcontractorEntity>

    @Query("SELECT * FROM sms_subcontractor WHERE subcontractor_id = :id")
    suspend fun getById(id: Long): SmsSubcontractorEntity?

    @Query("DELETE FROM sms_subcontractor WHERE project_id = :projectId")
    suspend fun deleteByProject(projectId: Int)

    @Query("DELETE FROM sms_subcontractor")
    suspend fun deleteAll()
}

@Dao
interface SmsUnitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsUnitEntity>)

    @Query("SELECT * FROM sms_unit WHERE is_active = 1 ORDER BY sort_order ASC")
    suspend fun getAll(): List<SmsUnitEntity>

    @Query("SELECT * FROM sms_unit WHERE unit_id = :id")
    suspend fun getById(id: Int): SmsUnitEntity?

    @Query("DELETE FROM sms_unit")
    suspend fun deleteAll()
}

@Dao
interface SmsVehicleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SmsVehicleEntity>)

    @Query("SELECT * FROM sms_vehicle WHERE project_id = :projectId ORDER BY license_plate ASC")
    suspend fun getByProject(projectId: Int): List<SmsVehicleEntity>

    @Query("SELECT * FROM sms_vehicle WHERE vehicle_id = :id")
    suspend fun getById(id: Long): SmsVehicleEntity?

    @Query("SELECT * FROM sms_vehicle WHERE UPPER(license_plate) = UPPER(:plate) LIMIT 1")
    suspend fun getByLicensePlate(plate: String): SmsVehicleEntity?

    @Query("SELECT COUNT(*) FROM sms_vehicle WHERE project_id = :projectId")
    suspend fun countByProject(projectId: Int): Int

    @Query("SELECT MAX(vehicle_id) FROM sms_vehicle")
    suspend fun getMaxId(): Long?

    @Query("SELECT MIN(vehicle_id) FROM sms_vehicle")
    suspend fun getMinId(): Long?

    @Query("SELECT * FROM sms_vehicle WHERE synced = 0")
    suspend fun getUnsynced(): List<SmsVehicleEntity>

    @Query("UPDATE sms_vehicle SET synced = 1 WHERE vehicle_id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT * FROM sms_vehicle WHERE route_synced = 0")
    suspend fun getUnsyncedRouteState(): List<SmsVehicleEntity>

    @Query("UPDATE sms_vehicle SET route_synced = 1 WHERE vehicle_id IN (:ids)")
    suspend fun markRouteStateSynced(ids: List<Long>)

    @Query("SELECT * FROM sms_vehicle WHERE on_route = 1 ORDER BY license_plate ASC")
    suspend fun getOnRoute(): List<SmsVehicleEntity>

    @Query("UPDATE sms_vehicle SET on_route = 1, destination = :destinationId, synced = 0, route_synced = 0 WHERE vehicle_id = :id")
    suspend fun setOnRoute(id: Long, destinationId: Int?)

    @Query("UPDATE sms_vehicle SET on_route = 0, destination = NULL, synced = 0, route_synced = 0 WHERE vehicle_id = :id")
    suspend fun setOffRoute(id: Long)

    @Query("DELETE FROM sms_vehicle WHERE vehicle_id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sms_vehicle WHERE project_id = :projectId")
    suspend fun deleteByProject(projectId: Int)

    @Query("DELETE FROM sms_vehicle")
    suspend fun deleteAll()
}

@Dao
interface SmsVehicleLoadingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SmsVehicleLoadingEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpools(spools: List<SmsVehicleLoadingSpoolEntity>)

    @Query("SELECT * FROM sms_vehicle_loading WHERE vehicle_id = :vehicleId AND project_id = :projectId")
    suspend fun getByVehicle(vehicleId: Long, projectId: Int): List<SmsVehicleLoadingEntity>

    @Query("SELECT * FROM sms_vehicle_loading WHERE synced = 0")
    suspend fun getUnsynced(): List<SmsVehicleLoadingEntity>

    @Query("SELECT * FROM sms_vehicle_loading_spool WHERE loading_id = :loadingId")
    suspend fun getSpoolsByLoading(loadingId: Long): List<SmsVehicleLoadingSpoolEntity>

    @Query("UPDATE sms_vehicle_loading SET synced = 1 WHERE loading_id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM sms_vehicle_loading WHERE loading_id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sms_vehicle_loading")
    suspend fun deleteAll()

    @Query("DELETE FROM sms_vehicle_loading_spool WHERE loading_id = :loadingId")
    suspend fun deleteSpoolsByLoading(loadingId: Long)

    @Query("DELETE FROM sms_vehicle_loading_spool")
    suspend fun deleteAllSpools()
}

@Dao
interface SmsAuditLogDao {
    @Insert
    suspend fun insert(log: SmsAuditLogEntity): Long

    @Query("SELECT * FROM sms_audit_log WHERE project_id = :projectId ORDER BY timestamp DESC LIMIT 300")
    suspend fun getByProject(projectId: Int): List<SmsAuditLogEntity>

    @Query("DELETE FROM sms_audit_log WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM sms_audit_log")
    suspend fun deleteAll()
}

@Dao
interface SmsIncidentDao {
    @Insert
    suspend fun insert(item: SmsIncidentEntity): Long

    @Query("SELECT * FROM sms_incident WHERE project_id = :projectId ORDER BY event_date DESC")
    suspend fun getByProject(projectId: Int): List<SmsIncidentEntity>

    @Query("SELECT * FROM sms_incident WHERE id = :id")
    suspend fun getById(id: Long): SmsIncidentEntity?

    @Query("SELECT COUNT(*) FROM sms_incident WHERE project_id = :projectId AND severity = 'CRITICAL' AND (status IS NULL OR UPPER(status) != 'CLOSED')")
    suspend fun getCriticalCount(projectId: Int): Int

    @Query("SELECT * FROM sms_incident WHERE synced = 0")
    suspend fun getUnsynced(): List<SmsIncidentEntity>

    @Query("UPDATE sms_incident SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("UPDATE sms_incident SET server_id = :serverId WHERE id = :id")
    suspend fun setServerId(id: Long, serverId: Long)

    @Query("SELECT * FROM sms_incident WHERE photo_path IS NOT NULL AND photo_synced = 0 AND server_id IS NOT NULL")
    suspend fun getPendingPhotoUploads(): List<SmsIncidentEntity>

    @Query("UPDATE sms_incident SET photo_synced = 1 WHERE id = :id")
    suspend fun markPhotoSynced(id: Long)

    @Query("UPDATE sms_incident SET status = 'CLOSED', closed_by = :closedBy, closed_at = :closedAt, synced = 0 WHERE id = :id")
    suspend fun close(id: Long, closedBy: String?, closedAt: String)

    @Query("DELETE FROM sms_incident WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface SmsSpoolLocationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SmsSpoolLocationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<SmsSpoolLocationEntity>)

    /** Returns locations for a spool, newest first, capped at 2. */
    @Query("SELECT * FROM sms_spool_location WHERE spool_id = :spoolId ORDER BY captured_at DESC LIMIT 2")
    suspend fun getBySpool(spoolId: Long): List<SmsSpoolLocationEntity>

    @Query("SELECT * FROM sms_spool_location WHERE synced = 0")
    suspend fun getUnsynced(): List<SmsSpoolLocationEntity>

    @Query("UPDATE sms_spool_location SET synced = 1 WHERE location_id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    /** Deletes the oldest location row for a spool when count exceeds 2. */
    @Query("""
        DELETE FROM sms_spool_location
        WHERE location_id = (
            SELECT location_id FROM sms_spool_location
            WHERE spool_id = :spoolId
            ORDER BY captured_at ASC
            LIMIT 1
        ) AND (SELECT COUNT(*) FROM sms_spool_location WHERE spool_id = :spoolId) > 2
    """)
    suspend fun pruneOldest(spoolId: Long)

    @Query("DELETE FROM sms_spool_location WHERE spool_id = :spoolId")
    suspend fun deleteBySpool(spoolId: Long)

    @Query("DELETE FROM sms_spool_location")
    suspend fun deleteAll()
}
