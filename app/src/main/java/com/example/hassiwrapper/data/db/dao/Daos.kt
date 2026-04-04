package com.example.hassiwrapper.data.db.dao

import androidx.room.*
import com.example.hassiwrapper.data.db.entities.*

@Dao
interface ConfigDao {
    @Query("SELECT value FROM config WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: ConfigEntity)

    @Query("DELETE FROM config WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(projects: List<ProjectEntity>)

    @Query("SELECT * FROM projects")
    suspend fun getAll(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE project_id = :id")
    suspend fun getById(id: Int): ProjectEntity?

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int

    @Query("DELETE FROM projects")
    suspend fun deleteAll()
}

@Dao
interface ZoneDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(zones: List<ZoneEntity>)

    @Query("SELECT * FROM zones")
    suspend fun getAll(): List<ZoneEntity>

    @Query("SELECT * FROM zones WHERE project_id = :projectId")
    suspend fun getByProject(projectId: Int): List<ZoneEntity>

    @Query("SELECT * FROM zones WHERE zone_id = :id")
    suspend fun getById(id: Int): ZoneEntity?

    @Query("SELECT COUNT(*) FROM zones")
    suspend fun count(): Int

    @Query("DELETE FROM zones")
    suspend fun deleteAll()
}

@Dao
interface ContractorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(contractors: List<ContractorEntity>)

    @Query("SELECT * FROM contractors")
    suspend fun getAll(): List<ContractorEntity>

    @Query("SELECT * FROM contractors WHERE contractor_id = :id")
    suspend fun getById(id: Int): ContractorEntity?

    @Query("SELECT COUNT(*) FROM contractors")
    suspend fun count(): Int

    @Query("DELETE FROM contractors")
    suspend fun deleteAll()
}

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(persons: List<PersonEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: PersonEntity)

    @Query("SELECT * FROM persons WHERE unique_id_value = :uuid")
    suspend fun getByUuid(uuid: String): PersonEntity?

    @Query("SELECT * FROM persons WHERE badge_number = :badge LIMIT 1")
    suspend fun getByBadge(badge: String): PersonEntity?

    @Query("SELECT * FROM persons")
    suspend fun getAll(): List<PersonEntity>

    @Query("SELECT * FROM persons WHERE given_name LIKE '%' || :query || '%' OR family_name LIKE '%' || :query || '%' OR badge_number LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<PersonEntity>

    @Query("SELECT unique_id_value FROM persons")
    suspend fun getAllUuids(): List<String>

    @Query("SELECT COUNT(*) FROM persons")
    suspend fun count(): Int

    @Query("DELETE FROM persons")
    suspend fun deleteAll()

    @Query("UPDATE persons SET photo_url = :photoUrl WHERE unique_id_value = :uuid")
    suspend fun updatePhotoUrl(uuid: String, photoUrl: String?)
}

@Dao
interface PendingPhotoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: PendingPhotoEntity)

    @Query("SELECT * FROM pending_photos")
    suspend fun getAll(): List<PendingPhotoEntity>

    @Query("SELECT COUNT(*) FROM pending_photos")
    suspend fun count(): Int

    @Query("DELETE FROM pending_photos WHERE unique_id_value = :uuid")
    suspend fun delete(uuid: String)

    @Query("DELETE FROM pending_photos")
    suspend fun deleteAll()
}

@Dao
interface AccessPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<AccessPointEntity>)

    @Query("SELECT * FROM access_points WHERE access_point_id = :id")
    suspend fun getById(id: Int): AccessPointEntity?

    @Query("DELETE FROM access_points")
    suspend fun deleteAll()
}

@Dao
interface CryptoKeyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(keys: List<CryptoKeyEntity>)

    @Query("SELECT * FROM crypto_keys WHERE crypto_key_id = :id")
    suspend fun getById(id: Int): CryptoKeyEntity?

    @Query("SELECT * FROM crypto_keys WHERE project_id = :projectId AND key_type = :keyType AND is_active = 1 LIMIT 1")
    suspend fun getActiveKey(projectId: Int?, keyType: String): CryptoKeyEntity?
}

@Dao
interface RevokedTokenDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tokens: List<RevokedTokenEntity>)

    @Query("SELECT * FROM revoked_tokens WHERE cti = :cti")
    suspend fun getByCti(cti: String): RevokedTokenEntity?

    @Query("DELETE FROM revoked_tokens")
    suspend fun deleteAll()
}

data class AccessLogWithPerson(
    @androidx.room.Embedded val log: AccessLogEntity,
    @androidx.room.ColumnInfo(name = "given_name") val givenName: String?,
    @androidx.room.ColumnInfo(name = "family_name") val familyName: String?,
    @androidx.room.ColumnInfo(name = "badge_number") val badgeNumber: String?
)

@Dao
interface AccessLogDao {
    @Insert
    suspend fun insert(log: AccessLogEntity): Long

    @Query("SELECT * FROM access_logs WHERE synced = 0")
    suspend fun getPending(): List<AccessLogEntity>

    @Query("UPDATE access_logs SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT * FROM access_logs ORDER BY event_time DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<AccessLogEntity>

    @Query("""
        SELECT a.*, p.given_name, p.family_name, p.badge_number
        FROM access_logs a
        LEFT JOIN persons p ON a.unique_id_value = p.unique_id_value
        ORDER BY a.event_time DESC LIMIT :limit
    """)
    suspend fun getRecentWithPerson(limit: Int = 50): List<AccessLogWithPerson>

    @Query("""
        SELECT a.*, p.given_name, p.family_name, p.badge_number
        FROM access_logs a
        LEFT JOIN persons p ON a.unique_id_value = p.unique_id_value
        WHERE a.synced = :synced
        ORDER BY a.event_time DESC LIMIT :limit
    """)
    suspend fun getRecentWithPersonFiltered(synced: Boolean, limit: Int = 200): List<AccessLogWithPerson>

    @Query("SELECT COUNT(*) FROM access_logs WHERE synced = 0")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM access_logs WHERE event_time >= :startOfDay")
    suspend fun getTodayCount(startOfDay: String): Int

    @Query("SELECT * FROM access_logs WHERE unique_id_value = :uuid ORDER BY event_time DESC LIMIT 1")
    suspend fun getLastByPerson(uuid: String): AccessLogEntity?
}

@Dao
interface IncidentDao {
    @Insert
    suspend fun insert(incident: IncidentEntity): Long

    @Query("SELECT * FROM incidents WHERE synced = 0")
    suspend fun getPending(): List<IncidentEntity>

    @Query("UPDATE incidents SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT * FROM incidents WHERE status = 'OPEN' ORDER BY event_time DESC")
    suspend fun getUnresolved(): List<IncidentEntity>

    @Query("SELECT * FROM incidents ORDER BY event_time DESC")
    suspend fun getAll(): List<IncidentEntity>

    @Query("SELECT * FROM incidents WHERE unique_id_value = :uuid ORDER BY event_time DESC")
    suspend fun getByWorker(uuid: String): List<IncidentEntity>

    @Query("SELECT COUNT(*) FROM incidents WHERE status = 'OPEN'")
    suspend fun getUnresolvedCount(): Int

    @Query("SELECT COUNT(*) FROM incidents WHERE synced = 0")
    suspend fun getPendingCount(): Int

    @Update
    suspend fun update(incident: IncidentEntity)
}

@Dao
interface HseObservationDao {
    @Insert
    suspend fun insert(observation: HseObservationEntity): Long

    @Query("SELECT * FROM hse_observations WHERE synced = 0")
    suspend fun getPending(): List<HseObservationEntity>

    @Query("UPDATE hse_observations SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT * FROM hse_observations ORDER BY observation_date DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 20): List<HseObservationEntity>

    @Query("SELECT COUNT(*) FROM hse_observations WHERE synced = 0")
    suspend fun getPendingCount(): Int
}

@Dao
interface WorkSessionDao {
    @Insert
    suspend fun insert(session: WorkSessionEntity): Long

    @Query("SELECT * FROM work_sessions WHERE unique_id_value = :uuid AND session_date = :date AND clock_out IS NULL LIMIT 1")
    suspend fun getOpenSession(uuid: String, date: String): WorkSessionEntity?

    @Query("UPDATE work_sessions SET clock_out = :clockOut, last_exit_log_id = :exitLogId WHERE id = :sessionId")
    suspend fun closeSession(sessionId: Long, clockOut: String, exitLogId: Long)

    @Query("SELECT * FROM work_sessions WHERE synced = 0 AND status = 'CLOSED'")
    suspend fun getPendingClosed(): List<WorkSessionEntity>

    @Query("UPDATE work_sessions SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM work_sessions WHERE synced = 0 AND status = 'CLOSED'")
    suspend fun getPendingCount(): Int
}
