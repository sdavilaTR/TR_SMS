package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.db.dao.ProjectDao
import com.example.hassiwrapper.data.db.dao.SmsBugReportDao
import com.example.hassiwrapper.data.db.dao.SmsIncidentDao
import com.example.hassiwrapper.data.db.dao.SmsOutboxDao
import com.example.hassiwrapper.data.db.dao.SmsPackingListDao
import com.example.hassiwrapper.data.db.dao.SmsPackingListSpoolDao
import com.example.hassiwrapper.data.db.dao.SmsSpoolDao
import com.example.hassiwrapper.data.db.dao.SmsSpoolEventDao
import com.example.hassiwrapper.data.db.dao.SmsSpoolLocationDao
import com.example.hassiwrapper.data.db.dao.SmsSpoolPropertyDao
import com.example.hassiwrapper.data.db.dao.SmsSpoolStatusFlagsDao
import com.example.hassiwrapper.data.db.dao.SmsTransferDao
import com.example.hassiwrapper.data.db.dao.SmsVehicleDao
import com.example.hassiwrapper.data.db.dao.SmsVehicleLoadingDao
import com.example.hassiwrapper.data.db.entities.ProjectEntity
import com.example.hassiwrapper.data.db.entities.SmsIdMapEntity
import com.example.hassiwrapper.data.db.entities.SmsOutboxEntity
import com.example.hassiwrapper.data.db.entities.SmsTransferEntity
import com.example.hassiwrapper.data.db.entities.SmsTransferSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleLoadingEntity
import com.example.hassiwrapper.data.db.entities.SmsVehicleLoadingSpoolEntity
import com.example.hassiwrapper.network.AtlasApiService
import com.example.hassiwrapper.network.dto.CreateSmsBugReportRequest
import com.example.hassiwrapper.network.dto.CreateSpoolRequest
import com.example.hassiwrapper.network.dto.CreateVehicleRequest
import com.example.hassiwrapper.network.dto.RouteStateUpdatePayload
import com.example.hassiwrapper.network.dto.SpoolCreatePayload
import com.example.hassiwrapper.network.dto.UpdateSpoolRequest
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * In-memory fake of [SmsOutboxDao] — exercises real insertion order, status
 * transitions and id-map lookups instead of stubbing them away, since those
 * are exactly the behaviors [OutboxService.drain] is verified against here.
 */
private class FakeSmsOutboxDao : SmsOutboxDao {
    private val rows = mutableMapOf<Long, SmsOutboxEntity>()
    private val idMap = mutableMapOf<Pair<String, Long>, Long>()
    private var nextId = 1L

    override suspend fun insert(op: SmsOutboxEntity): Long {
        val id = nextId++
        rows[id] = op.copy(op_id = id)
        return id
    }

    override suspend fun getPending(): List<SmsOutboxEntity> =
        rows.values.filter { it.status == "PENDING" }.sortedBy { it.op_id }

    override suspend fun getPendingAndFailed(): List<SmsOutboxEntity> =
        rows.values.filter { it.status == "PENDING" || it.status == "FAILED" }.sortedByDescending { it.op_id }

    override suspend fun pendingCount(): Int = rows.values.count { it.status == "PENDING" }

    override suspend fun failedCount(): Int = rows.values.count { it.status == "FAILED" }

    override suspend fun oldestPendingCreatedAt(): String? =
        rows.values.filter { it.status == "PENDING" }.minByOrNull { it.created_at }?.created_at

    override suspend fun hasUnfinishedOp(entityType: String, opType: String, localEntityId: Long): Boolean =
        rows.values.any {
            (it.status == "PENDING" || it.status == "FAILED") &&
                it.entity_type == entityType && it.op_type == opType && it.local_entity_id == localEntityId
        }

    override suspend fun getFailed(): List<SmsOutboxEntity> =
        rows.values.filter { it.status == "FAILED" }.sortedByDescending { it.op_id }

    override suspend fun getFailedNonDelete(): List<SmsOutboxEntity> =
        rows.values.filter { it.status == "FAILED" && it.op_type !in setOf("DELETE", "HARD_DELETE") }
            .sortedByDescending { it.op_id }

    override suspend fun markDone(opId: Long) {
        rows[opId] = rows[opId]!!.copy(status = "DONE")
    }

    override suspend fun markFailed(opId: Long, error: String?) {
        val row = rows[opId]!!
        rows[opId] = row.copy(status = "FAILED", attempts = row.attempts + 1, last_error = error)
    }

    override suspend fun recordAttempt(opId: Long, error: String?) {
        val row = rows[opId]!!
        rows[opId] = row.copy(attempts = row.attempts + 1, last_error = error)
    }

    override suspend fun pendingDeleteIds(entityType: String): List<Long> =
        rows.values.filter { it.status == "PENDING" && it.op_type == "DELETE" && it.entity_type == entityType }
            .map { it.local_entity_id }

    override suspend fun pruneDone() {
        rows.values.filter { it.status == "DONE" }.map { it.op_id }.forEach { rows.remove(it) }
    }

    override suspend fun deleteAllFailed() {
        rows.values.filter { it.status == "FAILED" }.map { it.op_id }.forEach { rows.remove(it) }
    }

    override suspend fun deleteOp(opId: Long) {
        rows.remove(opId)
    }

    override suspend fun putMapping(mapping: SmsIdMapEntity) {
        idMap[mapping.entity_type to mapping.local_id] = mapping.server_id
    }

    override suspend fun serverIdFor(entityType: String, localId: Long): Long? =
        idMap[entityType to localId]

    fun statusOf(opId: Long): String? = rows[opId]?.status
    fun attemptsOf(opId: Long): Int? = rows[opId]?.attempts
}

class OutboxServiceDrainTest {

    private val gson = Gson()
    private lateinit var outboxDao: FakeSmsOutboxDao
    private lateinit var projectDao: ProjectDao
    private lateinit var smsSpoolDao: SmsSpoolDao
    private lateinit var smsSpoolStatusFlagsDao: SmsSpoolStatusFlagsDao
    private lateinit var smsPackingListDao: SmsPackingListDao
    private lateinit var smsVehicleDao: SmsVehicleDao
    private lateinit var smsIncidentDao: SmsIncidentDao
    private lateinit var smsSpoolPropertyDao: SmsSpoolPropertyDao
    private lateinit var smsSpoolEventDao: SmsSpoolEventDao
    private lateinit var smsSpoolLocationDao: SmsSpoolLocationDao
    private lateinit var smsPackingListSpoolDao: SmsPackingListSpoolDao
    private lateinit var smsVehicleLoadingDao: SmsVehicleLoadingDao
    private lateinit var smsTransferDao: SmsTransferDao
    private lateinit var smsBugReportDao: SmsBugReportDao
    private lateinit var service: OutboxService

    @Before
    fun setUp() {
        outboxDao = FakeSmsOutboxDao()
        projectDao = mockk(relaxed = true)
        smsSpoolDao = mockk(relaxed = true)
        smsSpoolStatusFlagsDao = mockk(relaxed = true)
        smsPackingListDao = mockk(relaxed = true)
        smsVehicleDao = mockk(relaxed = true)
        smsIncidentDao = mockk(relaxed = true)
        smsSpoolPropertyDao = mockk(relaxed = true)
        smsSpoolEventDao = mockk(relaxed = true)
        smsSpoolLocationDao = mockk(relaxed = true)
        smsPackingListSpoolDao = mockk(relaxed = true)
        smsVehicleLoadingDao = mockk(relaxed = true)
        smsTransferDao = mockk(relaxed = true)
        smsBugReportDao = mockk(relaxed = true)
        coEvery { projectDao.getById(any()) } returns ProjectEntity(project_id = 6, project_code = "ELS-001")
        service = OutboxService(
            outboxDao, projectDao, smsSpoolDao, smsSpoolStatusFlagsDao, smsPackingListDao, smsVehicleDao, smsIncidentDao,
            smsSpoolPropertyDao, smsSpoolEventDao, smsSpoolLocationDao, smsPackingListSpoolDao, smsVehicleLoadingDao, smsTransferDao,
            smsBugReportDao
        )
    }

    private fun spoolCreatePayload(code: String) = SpoolCreatePayload(
        create = CreateSpoolRequest(
            spoolCode = code,
            spoolSuffix = "SP01",
            lineCode = "L1",
            projectId = 6,
            createdAt = "2026-06-24T00:00:00Z",
            createdBy = "tester"
        )
    )

    private suspend fun enqueueSpoolCreate(localId: Long, code: String): Long =
        outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.SPOOL,
                op_type = OutboxService.Op.CREATE,
                local_entity_id = localId,
                payload_json = gson.toJson(spoolCreatePayload(code)),
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )

    private suspend fun enqueueSpoolUpdate(localId: Long): Long =
        outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.SPOOL,
                op_type = OutboxService.Op.UPDATE,
                local_entity_id = localId,
                payload_json = gson.toJson(
                    UpdateSpoolRequest(
                        spoolId = localId,
                        spoolCode = "ignored",
                        spoolSuffix = "SP01",
                        lineCode = "L1",
                        projectId = 6,
                        projectCode = "ELS-001"
                    )
                ),
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )

    private fun jsonResponse(code: Int, json: String): Response<ResponseBody> {
        val body = json.toResponseBody("application/json".toMediaType())
        return if (code in 200..299) Response.success(code, body) else Response.error(code, body)
    }

    @Test
    fun `drains pending ops in op_id order`() = runTest {
        val callOrder = mutableListOf<Long>()
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.createSpool(any(), any()) } coAnswers {
            val req = secondArg<CreateSpoolRequest>()
            callOrder += req.spoolCode.removePrefix("S").toLong()
            jsonResponse(200, """{"spoolId": ${callOrder.last() + 1000}}""")
        }

        enqueueSpoolCreate(-1, "S1")
        enqueueSpoolCreate(-2, "S2")
        enqueueSpoolCreate(-3, "S3")

        val result = service.drain(api)

        assertEquals(listOf(1L, 2L, 3L), callOrder)
        assertEquals(3, result.done)
        // drain() prunes DONE rows at the end — nothing pending left behind.
        assertEquals(0, outboxDao.pendingCount())
    }

    @Test
    fun `CREATE id-map translates a later UPDATE onto the server id`() = runTest {
        var updateSpoolId: Long? = null
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.createSpool(any(), any()) } returns jsonResponse(200, """{"spoolId": 501}""")
        coEvery { api.updateSpool(any(), any()) } coAnswers {
            updateSpoolId = secondArg<UpdateSpoolRequest>().spoolId
            jsonResponse(200, "{}")
        }

        enqueueSpoolCreate(-1, "S1")
        enqueueSpoolUpdate(-1)

        val result = service.drain(api)

        assertEquals(2, result.done)
        assertEquals(501L, updateSpoolId)
    }

    @Test
    fun `4xx marks the op FAILED and continues draining`() = runTest {
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.createSpool(any(), match { it.spoolCode == "BAD" }) } returns
            jsonResponse(422, """{"error":"duplicate spool code"}""")
        coEvery { api.createSpool(any(), match { it.spoolCode == "OK" }) } returns
            jsonResponse(200, """{"spoolId": 777}""")

        val failingId = enqueueSpoolCreate(-1, "BAD")
        enqueueSpoolCreate(-2, "OK")

        val result = service.drain(api)

        assertEquals(1, result.done)
        assertEquals(1, result.failed)
        assertEquals(false, result.transient)
        assertEquals("FAILED", outboxDao.statusOf(failingId))
        // okId was DONE, then pruned — nothing pending left behind.
        assertEquals(0, outboxDao.pendingCount())
    }

    @Test
    fun `cancellation mid-drain propagates without failing or recording an attempt`() = runTest {
        // Regression test for the sync-resilience plan's Tier 1.3: onPause cancelling
        // lifecycleScope mid-drain must not count as a failed attempt toward MAX_ATTEMPTS —
        // OutboxService.call{} and drain()'s loop must rethrow CancellationException instead of
        // converting it to TransientFailure / markFailed.
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.createSpool(any(), any()) } throws kotlinx.coroutines.CancellationException("cancelled")

        val opId = enqueueSpoolCreate(-1, "S1")

        var caught = false
        try {
            service.drain(api)
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = true
        }

        assertEquals(true, caught)
        assertEquals("PENDING", outboxDao.statusOf(opId))
        assertEquals(0, outboxDao.attemptsOf(opId))
    }

    @Test
    fun `network error stops the drain transiently and leaves the op PENDING`() = runTest {
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.createSpool(any(), any()) } throws java.io.IOException("connection reset")

        val opId = enqueueSpoolCreate(-1, "S1")

        val result = service.drain(api)

        assertEquals(0, result.done)
        assertEquals(true, result.transient)
        assertEquals("PENDING", outboxDao.statusOf(opId))
        assertEquals(1, outboxDao.attemptsOf(opId))
    }

    @Test
    fun `CREATE remaps the temp spool id in every local child table`() = runTest {
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.createSpool(any(), any()) } returns jsonResponse(200, """{"spoolId": 900}""")

        enqueueSpoolCreate(-7, "S1")
        service.drain(api)

        coVerify { smsSpoolPropertyDao.remapSpoolId(-7, 900) }
        coVerify { smsSpoolEventDao.remapSpoolId(-7, 900) }
        coVerify { smsSpoolLocationDao.remapSpoolId(-7, 900) }
        coVerify { smsPackingListSpoolDao.remapSpoolId(-7, 900) }
        coVerify { smsVehicleLoadingDao.remapSpoolId(-7, 900) }
        coVerify { smsTransferDao.remapSpoolId(-7, 900) }
    }

    @Test
    fun `vehicle CREATE remaps the vehicle's own PK plus every local referencing table`() = runTest {
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.createVehicle(any(), any()) } returns jsonResponse(200, """{"vehicleId": 42}""")

        outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.VEHICLE,
                op_type = OutboxService.Op.CREATE,
                local_entity_id = -3,
                payload_json = gson.toJson(
                    CreateVehicleRequest(
                        licensePlate = "TEST-001", company = null, vehicleName = null, vehicleType = null,
                        capacityWeightKg = null, createdBy = "tester", projectCode = "ELS-001"
                    )
                ),
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )
        service.drain(api)

        coVerify { smsVehicleDao.remapAndSync(-3, 42) }
        coVerify { smsPackingListDao.remapVehicleId(-3, 42) }
        coVerify { smsVehicleLoadingDao.remapVehicleId(-3, 42) }
        coVerify { smsTransferDao.remapVehicleId(-3, 42) }
    }

    @Test
    fun `bug report CREATE marks the local row synced with the server id`() = runTest {
        // Regression test for sync-resilience plan Tier 1.5 step 1: bug-report metadata create
        // moved from a legacy fire-and-forget loop in SyncService into the outbox, so it now
        // gets attempts/FAILED/MAX_ATTEMPTS like every other mutation.
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.createSmsBugReport(any(), any()) } returns jsonResponse(200, """{"bugReportId": 55}""")

        outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.BUG_REPORT,
                op_type = OutboxService.Op.CREATE,
                local_entity_id = 9,
                payload_json = gson.toJson(
                    CreateSmsBugReportRequest(
                        uuid = "test-uuid", title = "t", description = "d", logs = null,
                        reporterName = null, terminalCode = "ELS-001", appVersion = "1.0", deviceModel = "EDA52",
                        screenName = "Home"
                    )
                ),
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )
        val result = service.drain(api)

        assertEquals(1, result.done)
        coVerify { smsBugReportDao.markMetadataSynced(9, 55) }
    }

    @Test
    fun `hasUnfinishedOp detects PENDING and FAILED so a migration backfill won't duplicate or resurrect ops`() = runTest {
        // The primitive SyncService.backfillBugReportOutboxOps relies on: a pre-migration bug
        // report row backfilled once must not get re-enqueued every 60s cycle while its op is
        // still PENDING — and, just as importantly, must not get re-enqueued after its op gave up
        // and went FAILED (that would silently undo MAX_ATTEMPTS and spam "Operaciones fallidas").
        val opId = service.enqueue(
            entityType = OutboxService.Entity.BUG_REPORT,
            opType = OutboxService.Op.CREATE,
            localEntityId = 9,
            projectId = 6,
            payload = CreateSmsBugReportRequest(
                uuid = "test-uuid", title = "t", description = "d", logs = null,
                reporterName = null, terminalCode = "ELS-001", appVersion = "1.0", deviceModel = "EDA52",
                screenName = "Home"
            )
        )

        assertEquals(true, service.hasUnfinishedOp(OutboxService.Entity.BUG_REPORT, OutboxService.Op.CREATE, 9))
        assertEquals(false, service.hasUnfinishedOp(OutboxService.Entity.BUG_REPORT, OutboxService.Op.CREATE, 10))

        outboxDao.markFailed(opId, "gave up after 6 attempts")
        assertEquals(true, service.hasUnfinishedOp(OutboxService.Entity.BUG_REPORT, OutboxService.Op.CREATE, 9))

        outboxDao.deleteOp(opId)
        assertEquals(false, service.hasUnfinishedOp(OutboxService.Entity.BUG_REPORT, OutboxService.Op.CREATE, 9))
    }

    @Test
    fun `bug report CREATE 4xx marks FAILED without retrying forever`() = runTest {
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.createSmsBugReport(any(), any()) } returns jsonResponse(422, """{"error":"bad payload"}""")

        val opId = outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.BUG_REPORT,
                op_type = OutboxService.Op.CREATE,
                local_entity_id = 9,
                payload_json = gson.toJson(
                    CreateSmsBugReportRequest(
                        uuid = "test-uuid", title = "t", description = "d", logs = null,
                        reporterName = null, terminalCode = "ELS-001", appVersion = "1.0", deviceModel = "EDA52",
                        screenName = "Home"
                    )
                ),
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )
        val result = service.drain(api)

        assertEquals(1, result.failed)
        assertEquals("FAILED", outboxDao.statusOf(opId))
    }

    @Test
    fun `ROUTE_STATE UPDATE onRoute calls setVehicleOnRoute with the queued destination and marks route-state synced`() = runTest {
        // Regression test for sync-resilience plan Tier 1.5 step 2: vehicle route state (on/off
        // route) moved from a legacy poll-and-resend loop into the outbox — see
        // SendPackingListFragment/ReceivePackingListFragment/PackingListCleanup enqueue call sites.
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.setVehicleOnRoute(any(), any(), any()) } returns jsonResponse(200, "{}")

        outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.ROUTE_STATE,
                op_type = OutboxService.Op.UPDATE,
                local_entity_id = 42,
                payload_json = gson.toJson(RouteStateUpdatePayload(onRoute = true, destinationId = 7)),
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )
        val result = service.drain(api)

        assertEquals(1, result.done)
        coVerify { api.setVehicleOnRoute("ELS-001", 42, 7) }
        coVerify { smsVehicleDao.markRouteStateSynced(listOf(42L)) }
    }

    @Test
    fun `ROUTE_STATE UPDATE offRoute calls setVehicleOffRoute and ignores a stale destination`() = runTest {
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.setVehicleOffRoute(any(), any()) } returns jsonResponse(200, "{}")

        outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.ROUTE_STATE,
                op_type = OutboxService.Op.UPDATE,
                local_entity_id = 42,
                payload_json = gson.toJson(RouteStateUpdatePayload(onRoute = false, destinationId = null)),
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )
        val result = service.drain(api)

        assertEquals(1, result.done)
        coVerify { api.setVehicleOffRoute("ELS-001", 42) }
        coVerify(exactly = 0) { api.setVehicleOnRoute(any(), any(), any()) }
        coVerify { smsVehicleDao.markRouteStateSynced(listOf(42L)) }
    }

    @Test
    fun `ROUTE_STATE UPDATE 5xx is transient and leaves the op PENDING`() = runTest {
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { api.setVehicleOnRoute(any(), any(), any()) } returns jsonResponse(503, "{}")

        val opId = outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.ROUTE_STATE,
                op_type = OutboxService.Op.UPDATE,
                local_entity_id = 42,
                payload_json = gson.toJson(RouteStateUpdatePayload(onRoute = true, destinationId = 7)),
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )
        val result = service.drain(api)

        assertEquals(true, result.transient)
        assertEquals("PENDING", outboxDao.statusOf(opId))
    }

    @Test
    fun `VEHICLE_LOADING CREATE reads the row fresh at drain time, uploads it and marks its PL ready to send`() = runTest {
        // Regression test for sync-resilience plan Tier 1.5 step 3: this is a pointer op — the
        // payload carries nothing but local_entity_id, and the loading row (including its
        // packing_list_id, which may have been remapped by uploadNewPackingLists/packingListCreate
        // any time between enqueue and drain) is read fresh from the DAO here, not from a
        // payload snapshot taken at enqueue time.
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { smsVehicleLoadingDao.getById(11) } returns SmsVehicleLoadingEntity(
            loading_id = 11, vehicle_id = 42, vehicle_plate = "TEST-001", project_id = 6, created_at = "2026-06-24T00:00:00Z"
        )
        coEvery { smsVehicleLoadingDao.getSpoolsByLoading(11) } returns listOf(
            SmsVehicleLoadingSpoolEntity(loading_id = 11, spool_id = 100, spool_code = "S100", spool_suffix = null, packing_list_id = 900, packing_list_name = "PL-900")
        )
        coEvery { api.uploadVehicleLoading(any(), any()) } returns jsonResponse(200, "{}")
        coEvery { api.setPackingListReadyToSend(any(), any(), any()) } returns jsonResponse(200, "{}")

        outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.VEHICLE_LOADING,
                op_type = OutboxService.Op.CREATE,
                local_entity_id = 11,
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )
        val result = service.drain(api)

        assertEquals(1, result.done)
        coVerify { smsVehicleLoadingDao.markSynced(listOf(11L)) }
        coVerify { api.setPackingListReadyToSend("ELS-001", 900, true) }
    }

    @Test
    fun `VEHICLE_LOADING CREATE for a locally-deleted loading is a no-op DONE`() = runTest {
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { smsVehicleLoadingDao.getById(11) } returns null

        outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.VEHICLE_LOADING,
                op_type = OutboxService.Op.CREATE,
                local_entity_id = 11,
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )
        val result = service.drain(api)

        assertEquals(1, result.done)
        coVerify(exactly = 0) { api.uploadVehicleLoading(any(), any()) }
    }

    @Test
    fun `TRANSFER CREATE reads the row fresh at drain time and marks it synced`() = runTest {
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { smsTransferDao.getById(21) } returns SmsTransferEntity(
            transfer_id = 21, transfer_type = "SEND", packing_list_id = 900, packing_list_name = "PL-900",
            vehicle_id = 42, vehicle_plate = "TEST-001", origin_location = "WORKSHOP", destination_location = "SITE",
            signature_data = "", created_at = "2026-06-24T00:00:00Z", project_id = 6
        )
        coEvery { smsTransferDao.getSpoolsByTransfer(21) } returns listOf(
            SmsTransferSpoolEntity(transfer_id = 21, spool_id = 100, spool_code = "S100", spool_suffix = null, assignment = null)
        )
        coEvery { api.uploadTransfer(any(), any()) } returns jsonResponse(200, "{}")

        outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.TRANSFER,
                op_type = OutboxService.Op.CREATE,
                local_entity_id = 21,
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )
        val result = service.drain(api)

        assertEquals(1, result.done)
        coVerify { smsTransferDao.markSynced(listOf(21L)) }
    }

    @Test
    fun `TRANSFER CREATE 4xx marks FAILED without retrying forever`() = runTest {
        val api = mockk<AtlasApiService>(relaxed = true)
        coEvery { smsTransferDao.getById(21) } returns SmsTransferEntity(
            transfer_id = 21, transfer_type = "SEND", packing_list_id = 900, packing_list_name = "PL-900",
            vehicle_id = 42, vehicle_plate = "TEST-001", origin_location = "WORKSHOP", destination_location = "SITE",
            signature_data = "", created_at = "2026-06-24T00:00:00Z", project_id = 6
        )
        coEvery { smsTransferDao.getSpoolsByTransfer(21) } returns emptyList()
        coEvery { api.uploadTransfer(any(), any()) } returns jsonResponse(422, """{"error":"bad payload"}""")

        val opId = outboxDao.insert(
            SmsOutboxEntity(
                entity_type = OutboxService.Entity.TRANSFER,
                op_type = OutboxService.Op.CREATE,
                local_entity_id = 21,
                project_id = 6,
                created_at = "2026-06-24T00:00:00Z"
            )
        )
        val result = service.drain(api)

        assertEquals(1, result.failed)
        assertEquals("FAILED", outboxDao.statusOf(opId))
    }
}
