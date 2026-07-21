package com.example.hassiwrapper.network.dto

import com.google.gson.JsonParser

/**
 * Reads the human-readable `message` out of a 409 conflict body from the packing-list
 * vehicle/rowversion guards (PackingListVehicleConflictException / PackingListRowVersionConflictException
 * in the backend). Falls back to the raw body, or null if this isn't a 409.
 */
fun parsePackingListConflictMessage(httpCode: Int, body: String?): String? {
    if (httpCode != 409 || body.isNullOrBlank()) return null
    return try {
        JsonParser.parseString(body).asJsonObject
            .get("message")?.takeIf { !it.isJsonNull }?.asString ?: body
    } catch (_: Exception) { body }
}
