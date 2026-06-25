package com.example.hassiwrapper.services

import com.example.hassiwrapper.data.db.dao.ProjectDao
import com.example.hassiwrapper.data.db.dao.SmsIncidentDao
import com.example.hassiwrapper.data.db.dao.SmsOutboxDao
import com.example.hassiwrapper.data.db.dao.SmsPackingListDao
import com.example.hassiwrapper.data.db.dao.SmsSpoolDao
import com.example.hassiwrapper.data.db.dao.SmsVehicleDao
import com.example.hassiwrapper.data.db.entities.ProjectEntity
import com.example.hassiwrapper.data.db.entities.SmsIdMapEntity
import com.example.hassiwrapper.data.db.entities.SmsOutboxEntity
import com.example.hassiwrapper.network.AtlasApiService
import com.example.hassiwrapper.network.dto.CreateSpoolRequest
import com.example.hassiwrapper.network.dto.SpoolCreatePayload
import com.example.hassiwrapper.network.dto.UpdateSpoolRequest
import com.google.gson.Gson
import io.mockk.coEvery
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

    override suspend fun pendingCount(): Int = rows.values.count { it.status == "PENDING" }

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
    private lateinit var smsPackingListDao: SmsPackingListDao
    private lateinit var smsVehicleDao: SmsVehicleDao
    private lateinit var smsIncidentDao: SmsIncidentDao
    private lateinit var service: OutboxService

    @Before
    fun setUp() {
        outboxDao = FakeSmsOutboxDao()
        projectDao = mockk(relaxed = true)
        smsSpoolDao = mockk(relaxed = true)
        smsPackingListDao = mockk(relaxed = true)
        smsVehicleDao = mockk(relaxed = true)
        smsIncidentDao = mockk(relaxed = true)
        coEvery { projectDao.getById(any()) } returns ProjectEntity(project_id = 6, project_code = "ELS-001")
        service = OutboxService(outboxDao, projectDao, smsSpoolDao, smsPackingListDao, smsVehicleDao, smsIncidentDao)
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
}
