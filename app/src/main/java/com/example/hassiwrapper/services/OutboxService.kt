package com.example.hassiwrapper.services

import android.util.Log
import com.example.hassiwrapper.data.db.dao.ProjectDao
import com.example.hassiwrapper.data.db.dao.SmsIncidentDao
import com.example.hassiwrapper.data.db.dao.SmsOutboxDao
import com.example.hassiwrapper.data.db.dao.SmsPackingListDao
import com.example.hassiwrapper.data.db.dao.SmsSpoolDao
import com.example.hassiwrapper.data.db.dao.SmsSpoolStatusFlagsDao
import com.example.hassiwrapper.data.db.dao.SmsVehicleDao
import com.example.hassiwrapper.data.db.entities.SmsIdMapEntity
import com.example.hassiwrapper.data.db.entities.SmsOutboxEntity
import com.example.hassiwrapper.network.AtlasApiService
import com.example.hassiwrapper.network.dto.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.ResponseBody
import retrofit2.Response
import java.time.Instant

/**
 * Persisted, ordered queue of SMS mutations and its drain loop.
 *
 * Fragments call [enqueue] right after their optimistic local write instead of
 * hitting the API directly; [SyncService] later calls [drain], which sends each
 * pending op in insertion order with the correct HTTP verb and reconciles
 * server-assigned ids via `sms_id_map` so deferred creates and their dependent
 * edits/assignments line up. This replaces the create-only `uploadNew*` helpers
 * and the volatile in-memory `locallyDeleted*Ids` sets.
 *
 * Offline-created rows use NEGATIVE temp ids; a non-CREATE op that references an
 * unmapped negative id means its CREATE has not drained yet, so the op is left
 * PENDING for a later cycle.
 */
class OutboxService(
    private val outboxDao: SmsOutboxDao,
    private val projectDao: ProjectDao,
    private val smsSpoolDao: SmsSpoolDao,
    private val smsSpoolStatusFlagsDao: SmsSpoolStatusFlagsDao,
    private val smsPackingListDao: SmsPackingListDao,
    private val smsVehicleDao: SmsVehicleDao,
    private val smsIncidentDao: SmsIncidentDao
) {
    companion object {
        private const val TAG = "OutboxService"
        /** Give up on an op after this many failed attempts to avoid an infinite retry. */
        private const val MAX_ATTEMPTS = 6
    }

    object Entity {
        const val SPOOL = "SPOOL"
        const val VEHICLE = "VEHICLE"
        const val PACKING_LIST = "PACKING_LIST"
        const val INCIDENT = "INCIDENT"
        const val PL_ASSIGN = "PL_ASSIGN"
        const val VEHICLE_LOADING = "VEHICLE_LOADING"
        const val TRANSFER = "TRANSFER"
        const val ROUTE_STATE = "ROUTE_STATE"
    }

    object Op {
        const val CREATE = "CREATE"
        const val UPDATE = "UPDATE"
        const val DELETE = "DELETE"
        const val HARD_DELETE = "HARD_DELETE"
        const val ASSIGN = "ASSIGN"
        const val UNASSIGN = "UNASSIGN"
    }

    private val gson = Gson()

    /** Raised internally when an op hits a network error or 5xx — drain stops so the caller can retry the whole cycle. */
    private class TransientFailure(msg: String) : Exception(msg)

    private enum class Outcome { DONE, SKIP, FAILED }

    data class DrainResult(val done: Int = 0, val failed: Int = 0, val transient: Boolean = false)

    // ── Enqueue ────────────────────────────────────────────────────────────────

    /**
     * Enqueues one pending operation. [payload] (a request DTO) is serialized to JSON;
     * pass null for ops that carry only ids. Returns the new op_id.
     */
    suspend fun enqueue(
        entityType: String,
        opType: String,
        localEntityId: Long,
        projectId: Int,
        refEntityId: Long? = null,
        payload: Any? = null
    ): Long {
        val op = SmsOutboxEntity(
            entity_type = entityType,
            op_type = opType,
            local_entity_id = localEntityId,
            ref_entity_id = refEntityId,
            payload_json = payload?.let { gson.toJson(it) },
            project_id = projectId,
            created_at = Instant.now().toString()
        )
        return outboxDao.insert(op)
    }

    /** Local ids the user deleted offline (PENDING DELETE/HARD_DELETE) — to hide them on download-merge. */
    suspend fun pendingDeleteIds(entityType: String): List<Long> =
        outboxDao.pendingDeleteIds(entityType)

    // ── Negative temp ids for offline-created rows ──────────────────────────────
    // Negatives never collide with server ids (always positive) and signal to the
    // drain's resolve() that the id must be translated once its CREATE op lands.

    suspend fun nextTempSpoolId(): Long = minOf(smsSpoolDao.getMinId() ?: 0L, 0L) - 1L
    suspend fun nextTempVehicleId(): Long = minOf(smsVehicleDao.getMinId() ?: 0L, 0L) - 1L
    suspend fun nextTempPackingListId(): Long = minOf(smsPackingListDao.getMinId() ?: 0L, 0L) - 1L

    // ── Drain ────────────────────────────────────────────────────────────────

    /**
     * Sends every PENDING op in op_id order. Stops and returns `transient = true` on
     * the first network/5xx error (leaving that op PENDING) so [SyncService]'s retry
     * budget re-runs the cycle. 4xx marks the op FAILED and continues. Once an op's
     * `attempts` reaches [MAX_ATTEMPTS] — whether stuck transient or perpetually
     * SKIP'd waiting on a prerequisite that itself gave up — it is marked FAILED and
     * drain moves past it instead of blocking every later queued op forever.
     */
    suspend fun drain(api: AtlasApiService): DrainResult {
        val pending = outboxDao.getPending()
        if (pending.isEmpty()) return DrainResult()

        Log.i(TAG, "Draining ${pending.size} outbox op(s)")
        var done = 0
        var failed = 0

        for (op in pending) {
            val projectCode = projectDao.getById(op.project_id)?.project_code
            if (projectCode.isNullOrBlank()) {
                Log.w(TAG, "op ${op.op_id} (${op.entity_type}/${op.op_type}): no project code, skipping")
                continue
            }
            val exhausted = op.attempts + 1 >= MAX_ATTEMPTS
            try {
                when (dispatch(api, op, projectCode)) {
                    Outcome.DONE -> { outboxDao.markDone(op.op_id); done++ }
                    Outcome.SKIP -> {
                        if (exhausted) {
                            outboxDao.markFailed(op.op_id, "gave up after $MAX_ATTEMPTS attempts: prerequisite never resolved")
                            Log.e(TAG, "op ${op.op_id} giving up: prerequisite never resolved after $MAX_ATTEMPTS attempts")
                            failed++
                        } else {
                            outboxDao.recordAttempt(op.op_id, "waiting for prerequisite create")
                        }
                    }
                    Outcome.FAILED -> failed++   // dispatch already called markFailed
                }
            } catch (e: TransientFailure) {
                if (exhausted) {
                    outboxDao.markFailed(op.op_id, "gave up after $MAX_ATTEMPTS attempts: ${e.message}")
                    Log.e(TAG, "op ${op.op_id} giving up after $MAX_ATTEMPTS attempts (${e.message}) — skipping, drain continues")
                    failed++
                } else {
                    outboxDao.recordAttempt(op.op_id, e.message)
                    Log.w(TAG, "op ${op.op_id} transient (${e.message}) — stopping drain for retry")
                    outboxDao.pruneDone()
                    return DrainResult(done, failed, transient = true)
                }
            } catch (e: Exception) {
                outboxDao.markFailed(op.op_id, "${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "op ${op.op_id} unexpected error", e)
                failed++
            }
        }

        outboxDao.pruneDone()
        return DrainResult(done, failed, transient = false)
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    private suspend fun dispatch(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        return when (op.entity_type) {
            Entity.SPOOL -> when (op.op_type) {
                Op.CREATE -> spoolCreate(api, op, projectCode)
                Op.UPDATE -> spoolUpdate(api, op, projectCode)
                Op.DELETE, Op.HARD_DELETE -> spoolDelete(api, op, projectCode, hard = op.op_type == Op.HARD_DELETE)
                else -> unknownOp(op)
            }
            Entity.VEHICLE -> when (op.op_type) {
                Op.CREATE -> vehicleCreate(api, op, projectCode)
                Op.UPDATE -> vehicleUpdate(api, op, projectCode)
                Op.DELETE, Op.HARD_DELETE -> vehicleDelete(api, op, projectCode)
                else -> unknownOp(op)
            }
            Entity.PACKING_LIST -> when (op.op_type) {
                Op.CREATE -> packingListCreate(api, op, projectCode)
                Op.UPDATE -> packingListUpdate(api, op, projectCode)
                Op.DELETE, Op.HARD_DELETE -> packingListDelete(api, op, projectCode, hard = op.op_type == Op.HARD_DELETE)
                else -> unknownOp(op)
            }
            Entity.PL_ASSIGN -> when (op.op_type) {
                Op.ASSIGN -> plAssign(api, op, projectCode)
                Op.UNASSIGN -> plUnassign(api, op, projectCode)
                else -> unknownOp(op)
            }
            Entity.INCIDENT -> when (op.op_type) {
                Op.CREATE -> incidentCreate(api, op, projectCode)
                else -> unknownOp(op)
            }
            else -> unknownOp(op)
        }
    }

    // ── Spool ──────────────────────────────────────────────────────────────────

    private suspend fun spoolCreate(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        val payload = gson.fromJson(op.payload_json, SpoolCreatePayload::class.java)
        val resp = call { api.createSpool(projectCode, payload.create) }
        return onResponse(op, resp) {
            val serverId = parseServerId(resp, "spoolId", "spool_id")
            if (serverId != null) {
                outboxDao.putMapping(SmsIdMapEntity(Entity.SPOOL, op.local_entity_id, serverId))
                // Remap local CRC-hash id → server id so the merge in syncSmsData can match by id
                // and preserve locally-set zone/sub_position_id that the GET /spools response omits.
                smsSpoolDao.remapAndSync(op.local_entity_id, serverId)
                smsSpoolStatusFlagsDao.remapSpoolId(op.local_entity_id, serverId)
                payload.property?.let { p ->
                    runCatching { api.createSpoolProperty(projectCode, serverId, p.copy(spoolId = serverId)) }
                }
                payload.flags?.let { f ->
                    runCatching { api.createSpoolStatusFlags(projectCode, serverId, f.copy(spoolId = serverId)) }
                }
            }
        }
    }

    private suspend fun spoolUpdate(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        val serverId = resolve(Entity.SPOOL, op.local_entity_id) ?: return Outcome.SKIP
        val payload = gson.fromJson(op.payload_json, UpdateSpoolRequest::class.java).copy(spoolId = serverId)
        val resp = call { api.updateSpool(projectCode, payload) }
        return onResponse(op, resp) { smsSpoolDao.markSynced(listOf(op.local_entity_id)) }
    }

    private suspend fun spoolDelete(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String, hard: Boolean): Outcome {
        val serverId = resolve(Entity.SPOOL, op.local_entity_id) ?: return Outcome.SKIP
        val resp = call { if (hard) api.hardDeleteSpool(projectCode, serverId) else api.deleteSpool(projectCode, serverId) }
        return onResponse(op, resp) {}
    }

    // ── Vehicle ─────────────────────────────────────────────────────────────────

    private suspend fun vehicleCreate(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        val payload = gson.fromJson(op.payload_json, CreateVehicleRequest::class.java)
        val resp = call { api.createVehicle(projectCode, payload) }
        return onResponse(op, resp) {
            val serverId = parseServerId(resp, "vehicleId", "vehicle_id")
            if (serverId != null) {
                outboxDao.putMapping(SmsIdMapEntity(Entity.VEHICLE, op.local_entity_id, serverId))
                smsVehicleDao.markSynced(listOf(op.local_entity_id))
            }
        }
    }

    private suspend fun vehicleUpdate(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        val serverId = resolve(Entity.VEHICLE, op.local_entity_id) ?: return Outcome.SKIP
        val payload = gson.fromJson(op.payload_json, UpdateVehicleRequest::class.java).copy(vehicleId = serverId)
        val resp = call { api.updateVehicle(projectCode, payload) }
        return onResponse(op, resp) { smsVehicleDao.markSynced(listOf(op.local_entity_id)) }
    }

    private suspend fun vehicleDelete(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        val serverId = resolve(Entity.VEHICLE, op.local_entity_id) ?: return Outcome.SKIP
        val resp = call { api.hardDeleteVehicle(projectCode, serverId) }
        return onResponse(op, resp) {}
    }

    // ── Packing list ─────────────────────────────────────────────────────────────

    private suspend fun packingListCreate(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        val payload = gson.fromJson(op.payload_json, CreatePackingListRequest::class.java)
        val resp = call { api.createPackingList(projectCode, payload) }
        return onResponse(op, resp) {
            val serverId = parseServerId(resp, "packingListId", "packing_list_id")
            if (serverId != null) {
                outboxDao.putMapping(SmsIdMapEntity(Entity.PACKING_LIST, op.local_entity_id, serverId))
                smsPackingListDao.markSynced(listOf(op.local_entity_id))
            }
        }
    }

    private suspend fun packingListUpdate(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        val serverId = resolve(Entity.PACKING_LIST, op.local_entity_id) ?: return Outcome.SKIP
        val payload = gson.fromJson(op.payload_json, UpdatePackingListRequest::class.java).copy(packingListId = serverId)
        val resp = call { api.updatePackingList(projectCode, payload) }
        return onResponse(op, resp) { smsPackingListDao.markSynced(listOf(op.local_entity_id)) }
    }

    private suspend fun packingListDelete(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String, hard: Boolean): Outcome {
        val serverId = resolve(Entity.PACKING_LIST, op.local_entity_id) ?: return Outcome.SKIP
        val resp = call { if (hard) api.hardDeletePackingList(projectCode, serverId) else api.deletePackingList(projectCode, serverId) }
        return onResponse(op, resp) {}
    }

    // ── Packing-list ↔ spool assignment ──────────────────────────────────────────

    private suspend fun plAssign(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        val spoolId = resolve(Entity.SPOOL, op.local_entity_id) ?: return Outcome.SKIP
        val plId = resolve(Entity.PACKING_LIST, op.ref_entity_id ?: return unknownOp(op)) ?: return Outcome.SKIP
        val payload = gson.fromJson(op.payload_json, AssignSpoolRequest::class.java).copy(spoolId = spoolId)
        val resp = call { api.addSpoolToPackingList(projectCode, plId, payload) }
        return onResponse(op, resp) {}
    }

    private suspend fun plUnassign(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        val spoolId = resolve(Entity.SPOOL, op.local_entity_id) ?: return Outcome.SKIP
        val plId = resolve(Entity.PACKING_LIST, op.ref_entity_id ?: return unknownOp(op)) ?: return Outcome.SKIP
        val resp = call { api.removeSpoolFromPackingList(projectCode, plId, spoolId) }
        return onResponse(op, resp) {}
    }

    // ── Incident ──────────────────────────────────────────────────────────────────

    private suspend fun incidentCreate(api: AtlasApiService, op: SmsOutboxEntity, projectCode: String): Outcome {
        val payload = gson.fromJson(op.payload_json, CreateSmsIncidentRequest::class.java)
        val resp = call { api.createSmsIncident(projectCode, payload) }
        return onResponse(op, resp) { smsIncidentDao.markSynced(listOf(op.local_entity_id)) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /** Translate a possibly-local id to its server id. Positive ids are already server ids. */
    private suspend fun resolve(entityType: String, localId: Long): Long? =
        if (localId >= 0) localId else outboxDao.serverIdFor(entityType, localId)

    /** Runs an API call, converting any thrown network exception into a [TransientFailure]. */
    private suspend fun call(block: suspend () -> Response<ResponseBody>): Response<ResponseBody> =
        try { block() } catch (e: Exception) { throw TransientFailure(e.message ?: "network error") }

    /**
     * Classifies a response: 2xx runs [onSuccess] and returns DONE; 5xx throws
     * [TransientFailure] (caller in [drain] turns this into FAILED once
     * [MAX_ATTEMPTS] is exhausted); 4xx marks the op FAILED and returns FAILED.
     */
    private suspend fun onResponse(
        op: SmsOutboxEntity,
        resp: Response<ResponseBody>,
        onSuccess: suspend () -> Unit
    ): Outcome {
        if (resp.isSuccessful) {
            onSuccess()
            return Outcome.DONE
        }
        if (resp.code() >= 500) throw TransientFailure("HTTP ${resp.code()}")
        val body = resp.errorBody()?.string()?.take(200)
        outboxDao.markFailed(op.op_id, "HTTP ${resp.code()} $body")
        Log.e(TAG, "op ${op.op_id} (${op.entity_type}/${op.op_type}) rejected: HTTP ${resp.code()} $body")
        return Outcome.FAILED
    }

    private suspend fun unknownOp(op: SmsOutboxEntity): Outcome {
        outboxDao.markFailed(op.op_id, "unknown op ${op.entity_type}/${op.op_type}")
        Log.e(TAG, "Unknown outbox op ${op.entity_type}/${op.op_type}")
        return Outcome.FAILED
    }

    private fun parseServerId(resp: Response<ResponseBody>, vararg keys: String): Long? {
        return try {
            val raw = resp.body()?.string().orEmpty()
            val el = JsonParser.parseString(raw)
            val obj = when {
                el.isJsonObject && el.asJsonObject.has("data") && !el.asJsonObject.get("data").isJsonNull ->
                    el.asJsonObject.getAsJsonObject("data")
                el.isJsonObject -> el.asJsonObject
                else -> return null
            }
            for (k in keys) {
                obj.get(k)?.takeIf { !it.isJsonNull }?.let { return it.asLong }
            }
            obj.get("id")?.takeIf { !it.isJsonNull }?.asLong
        } catch (_: Exception) { null }
    }
}
