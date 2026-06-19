package com.example.hassiwrapper.network.dto

/**
 * Composite payload for a SPOOL CREATE outbox op. `createSpool` only accepts the
 * core fields, so the optional property + status-flags (sent as separate POSTs by
 * NewSpoolFragment) ride along here and are dispatched by the drain once the
 * server spool id is known.
 */
data class SpoolCreatePayload(
    val create: CreateSpoolRequest,
    val property: CreateSpoolPropertyRequest? = null,
    val flags: CreateSpoolStatusFlagsRequest? = null
)
