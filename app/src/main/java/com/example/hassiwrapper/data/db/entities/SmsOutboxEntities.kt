package com.example.hassiwrapper.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted, ordered queue of pending SMS mutations. Every offline-capable action
 * (create/update/delete/assign on spools, packing lists, vehicles, etc.) enqueues
 * one row here; [com.example.hassiwrapper.services.OutboxService] drains them in
 * [op_id] insertion order, dispatching the correct HTTP verb and reconciling
 * server-assigned ids via [SmsIdMapEntity].
 *
 * Replaces the old single-boolean `synced` + in-memory `locallyDeleted*Ids` sets,
 * which could not represent edits/deletes and did not survive process death.
 */
@Entity(tableName = "sms_outbox")
data class SmsOutboxEntity(
    @PrimaryKey(autoGenerate = true) val op_id: Long = 0,
    /** SPOOL | VEHICLE | PACKING_LIST | INCIDENT | PL_ASSIGN | VEHICLE_LOADING | TRANSFER | ROUTE_STATE */
    val entity_type: String,
    /** CREATE | UPDATE | DELETE | ASSIGN | UNASSIGN */
    val op_type: String,
    /** Local PK the op targets (for PL_ASSIGN/UNASSIGN this is the spool id). */
    val local_entity_id: Long,
    /** Secondary local ref needing translation (e.g. packing_list_id for assign). */
    val ref_entity_id: Long? = null,
    /** Serialized request DTO (Gson); null for ops carrying only ids. */
    val payload_json: String? = null,
    val project_id: Int,
    val created_at: String,
    val attempts: Int = 0,
    val last_error: String? = null,
    /** PENDING | DONE | FAILED */
    val status: String = "PENDING"
)

/**
 * Maps a local (temp) entity id to the server-assigned id once its CREATE op drains,
 * so later UPDATE/DELETE/ASSIGN ops that still reference the local id can be retargeted.
 */
@Entity(tableName = "sms_id_map", primaryKeys = ["entity_type", "local_id"])
data class SmsIdMapEntity(
    val entity_type: String,
    val local_id: Long,
    val server_id: Long
)
