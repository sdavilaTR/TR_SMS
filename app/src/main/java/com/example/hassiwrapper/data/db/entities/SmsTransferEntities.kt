package com.example.hassiwrapper.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sms_transfer")
data class SmsTransferEntity(
    @PrimaryKey(autoGenerate = true) val transfer_id: Long = 0,
    val transfer_type: String,           // "SEND" | "RECEIVE"
    val packing_list_id: Long,
    val packing_list_name: String,
    val vehicle_id: Long,
    val vehicle_plate: String,
    val origin_location: String,         // "WORKSHOP" | "LAYDOWN" | "SITE"
    val destination_location: String,
    val signature_data: String,          // Base64-encoded PNG
    val created_at: String,
    val project_id: Int,
    val synced: Boolean = false
)

@Entity(tableName = "sms_transfer_spool")
data class SmsTransferSpoolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transfer_id: Long,
    val spool_id: Long,
    val spool_code: String,
    val spool_suffix: String?,
    val assignment: String?              // section (Laydown) or unit (Site); null for SEND
)
