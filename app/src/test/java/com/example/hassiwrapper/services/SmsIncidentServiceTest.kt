package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.ConfigRepository
import com.example.hassiwrapper.data.db.dao.ConfigDao
import com.example.hassiwrapper.data.db.dao.SmsIncidentDao
import com.example.hassiwrapper.data.db.dao.SmsPositionDao
import com.example.hassiwrapper.data.db.dao.SmsSubPositionDao
import com.example.hassiwrapper.data.db.entities.SmsIncidentEntity
import com.example.hassiwrapper.data.db.entities.SmsPositionEntity
import com.example.hassiwrapper.data.db.entities.SmsSubPositionEntity
import com.example.hassiwrapper.network.dto.CreateSmsIncidentRequest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory fake mirroring the real `sms_incident` DAO's SQL semantics (including
 * the `synced = 0` reset that [SmsIncidentDao.close] now performs) so the
 * create → upload → close → re-upload round trip can be exercised without a real
 * Room/SQLite instance (no Robolectric in this project's test setup).
 */
private class FakeSmsIncidentDao : SmsIncidentDao {
    private val rows = mutableMapOf<Long, SmsIncidentEntity>()
    private var nextId = 1L

    override suspend fun insert(item: SmsIncidentEntity): Long {
        val id = nextId++
        rows[id] = item.copy(id = id)
        return id
    }

    override suspend fun getByProject(projectId: Int): List<SmsIncidentEntity> =
        rows.values.filter { it.project_id == projectId }.sortedByDescending { it.event_date }

    override suspend fun getById(id: Long): SmsIncidentEntity? = rows[id]

    override suspend fun getCriticalCount(projectId: Int): Int =
        rows.values.count { it.project_id == projectId && it.severity == "CRITICAL" }

    override suspend fun getUnsynced(): List<SmsIncidentEntity> = rows.values.filter { !it.synced }

    override suspend fun markSynced(ids: List<Long>) {
        ids.forEach { id -> rows[id] = rows[id]!!.copy(synced = true) }
    }

    override suspend fun setServerId(id: Long, serverId: Long) {
        rows[id] = rows[id]!!.copy(server_id = serverId)
    }

    override suspend fun getPendingPhotoUploads(): List<SmsIncidentEntity> =
        rows.values.filter { it.photo_path != null && !it.photo_synced && it.server_id != null }

    override suspend fun markPhotoSynced(id: Long) {
        rows[id] = rows[id]!!.copy(photo_synced = true)
    }

    override suspend fun close(id: Long, closedBy: String?, closedAt: String) {
        val row = rows[id]!!
        rows[id] = row.copy(status = "CLOSED", closed_by = closedBy, closed_at = closedAt, synced = false)
    }

    override suspend fun deleteById(id: Long) {
        rows.remove(id)
    }
}

/** Mirrors [SyncService]'s private request-building (same field-for-field mapping). */
private fun toUploadRequest(inc: SmsIncidentEntity, projectCode: String) = CreateSmsIncidentRequest(
    uuid = inc.uuid,
    projectCode = projectCode,
    spoolCode = inc.spool_code,
    spoolSuffix = inc.spool_suffix,
    description = inc.description,
    vehiclePlate = inc.vehicle_plate,
    locationType = inc.location_type,
    locationDetail = inc.location_detail,
    severity = inc.severity,
    positionId = inc.position_id,
    subPositionId = inc.sub_position_id,
    positionCode = inc.position_code,
    authorName = inc.author_name,
    eventDate = inc.event_date,
    status = inc.status,
    closedBy = inc.closed_by,
    closedAt = inc.closed_at
)

class SmsIncidentServiceTest {

    private lateinit var incidentDao: FakeSmsIncidentDao
    private lateinit var configRepo: ConfigRepository
    private lateinit var positionDao: SmsPositionDao
    private lateinit var subPositionDao: SmsSubPositionDao
    private lateinit var service: SmsIncidentService

    private val position = SmsPositionEntity(position_id = 10, code = "LAYDOWN-A", name = "Laydown A")

    @Before
    fun setUp() {
        incidentDao = FakeSmsIncidentDao()
        positionDao = mockk(relaxed = true)
        subPositionDao = mockk(relaxed = true)
        val configDao = mockk<ConfigDao>(relaxed = true)
        coEvery { configDao.getValue("selected_project_id") } returns "6"
        coEvery { configDao.getValue("device_location") } returns "LAYDOWN-A"
        coEvery { configDao.getValue("assigned_operator_name") } returns "Juan Perez"
        configRepo = ConfigRepository(configDao)
        coEvery { positionDao.getByCode("LAYDOWN-A") } returns position

        service = SmsIncidentService(incidentDao, configRepo, positionDao, subPositionDao)
    }

    @Test
    fun `createIncident persists every field passed in plus the auto-filled position and author`() = runTest {
        val created = service.createIncident(
            spoolCode = "SP-001",
            spoolSuffix = "A",
            description = "Carcasa golpeada al descargar",
            vehiclePlate = "1234-ABC",
            locationType = "LAYDOWN",
            locationDetail = "Fila 3",
            severity = "HIGH",
            photoPath = "/data/photos/inc1.jpg",
            subPositionId = 55L
        )

        val stored = incidentDao.getById(created.id)
        assertNotNull(stored)
        stored!!

        // Caller-provided fields land untouched.
        assertEquals("SP-001", stored.spool_code)
        assertEquals("A", stored.spool_suffix)
        assertEquals("Carcasa golpeada al descargar", stored.description)
        assertEquals("1234-ABC", stored.vehicle_plate)
        assertEquals("LAYDOWN", stored.location_type)
        assertEquals("Fila 3", stored.location_detail)
        assertEquals("HIGH", stored.severity)
        assertEquals("/data/photos/inc1.jpg", stored.photo_path)
        assertEquals(55L, stored.sub_position_id)

        // Auto-filled fields are resolved from config/position DAOs, not left null.
        assertEquals(6, stored.project_id)
        assertEquals(10, stored.position_id)
        assertEquals("LAYDOWN-A", stored.position_code)
        assertEquals("Juan Perez", stored.author_name)

        // Bookkeeping defaults for a brand-new offline incident.
        assertTrue(stored.uuid.isNotBlank())
        assertEquals("OPEN", stored.status)
        assertFalse(stored.synced)
        assertEquals(null, stored.server_id)
        assertFalse(stored.photo_synced)
        assertTrue(stored.event_date.isNotBlank())
    }

    @Test
    fun `closing a synced incident re-flags it unsynced so the close reaches the backend`() = runTest {
        val created = service.createIncident(
            spoolCode = "SP-002", spoolSuffix = null, description = "Golpe en transporte",
            vehiclePlate = null, locationType = "SITE", locationDetail = null,
            severity = "MEDIUM", photoPath = null
        )

        // Simulate SyncService.uploadSmsIncidents(): first upload succeeds, server assigns id 999.
        val firstRequest = toUploadRequest(incidentDao.getById(created.id)!!, "ELS-001")
        assertEquals("OPEN", firstRequest.status)
        incidentDao.setServerId(created.id, 999L)
        incidentDao.markSynced(listOf(created.id))
        assertTrue(incidentDao.getById(created.id)!!.synced)
        assertTrue(incidentDao.getUnsynced().isEmpty())

        // User closes it locally.
        service.closeIncident(created.id)

        val afterClose = incidentDao.getById(created.id)!!
        assertEquals("CLOSED", afterClose.status)
        assertEquals("Juan Perez", afterClose.closed_by)
        assertNotNull(afterClose.closed_at)
        assertFalse("close() must reset synced=false so it gets re-uploaded", afterClose.synced)
        assertEquals(999L, afterClose.server_id) // server id survives the close

        // Next sync cycle picks it up again via getUnsynced().
        val pending = incidentDao.getUnsynced()
        assertEquals(1, pending.size)
        val secondRequest = toUploadRequest(pending.single(), "ELS-001")

        // Status/closedBy/closedAt must NOT be omitted on resend — backend resets to
        // OPEN if status is missing from the upsert body.
        assertEquals("CLOSED", secondRequest.status)
        assertEquals("Juan Perez", secondRequest.closedBy)
        assertNotNull(secondRequest.closedAt)

        incidentDao.markSynced(listOf(created.id))
        assertTrue(incidentDao.getById(created.id)!!.synced)
    }
}
