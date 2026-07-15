package com.example.hassiwrapper.ui.qrscanner

internal sealed class QrResult {
    data class Spool(
        val spoolCode: String,
        val spoolSuffix: String?,
        val unitCode: String? = null,
        val service: String? = null,
        val lineCode: String? = null,
        val sitNumber: String? = null,
        val revision: String? = null
    ) : QrResult()
    data class VehicleId(val id: Long) : QrResult()
    data class VehiclePlate(val plate: String) : QrResult()
    data class VehicleBadge(val uuid: String) : QrResult()
    data class Unknown(val raw: String) : QrResult()
}

// QR fields may be newline-separated or concatenated with no separator; use regex to extract.
private val STOP_WORDS = """Suffix:|Desc:|Diameter:|Lenght:|Length:|Priority:"""

// JAFURAH physical spool tag: UNIT-SERVICE-LINE-SIT-SPOOLID-REVISION, e.g. 821-RP-25107-002-SP01-01A.
// Anchored on SPnn (unique to spool tags) so vehicle plates containing dashes aren't misclassified.
private val JAFURAH_TAG = Regex("""(?i)^([A-Z0-9]+)-([A-Z]+)-([A-Z0-9]+)-([A-Z0-9]+)-(SP\d+)-([A-Z0-9]+)$""")

// Loose JAFURAH shape (6 alnum dash-segments) but missing/malformed SPnn id — still a spool
// tag (e.g. invented/test codes), not a vehicle plate. Route to spool lookup so the error
// message stays in the right domain ("spool not found" instead of "vehicle not found").
private val JAFURAH_SHAPE = Regex("""(?i)^[A-Z0-9]+-[A-Z]+-[A-Z0-9]+-[A-Z0-9]+-[A-Z0-9]+-[A-Z0-9]+$""")

internal fun parseQr(text: String): QrResult {
    val clean = text.trimStart('﻿').trim()
    val upper = clean.uppercase()
    if (upper.startsWith("JAFURAH PACKING LIST") || upper.startsWith("RIYAS PACKING LIST")) {
        val spoolCode = Regex("""(?i)ID:\s*(.+?)(?=\s*(?:$STOP_WORDS)|\z)""").find(clean)
            ?.groupValues?.get(1)?.trim()
        val spoolSuffix = Regex("""(?i)Suffix:\s*(.+?)(?=\s*(?:Desc:|Diameter:|Lenght:|Length:|Priority:)|\z)""").find(clean)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        android.util.Log.d("QrParser", "spool block: spoolCode=$spoolCode spoolSuffix=$spoolSuffix")
        return if (!spoolCode.isNullOrBlank()) QrResult.Spool(spoolCode, spoolSuffix) else QrResult.Unknown(clean)
    }
    JAFURAH_TAG.find(clean)?.let { m ->
        val (unit, service, line, sit, spoolSuffix, revision) = m.destructured
        return QrResult.Spool(
            spoolCode = listOf(unit, service, line, sit).joinToString("-"),
            spoolSuffix = spoolSuffix,
            unitCode = unit, service = service, lineCode = line,
            sitNumber = sit, revision = revision
        )
    }
    if (clean.startsWith("VEH:")) return QrResult.VehicleBadge(clean.removePrefix("VEH:"))
    val urlVehicleId = Regex("""/vehicles?/(\d+)""").find(clean)
        ?.groupValues?.getOrNull(1)?.toLongOrNull()
    if (urlVehicleId != null) return QrResult.VehicleId(urlVehicleId)
    if (JAFURAH_SHAPE.matches(clean)) {
        val parts = clean.split("-")
        return QrResult.Spool(
            spoolCode = parts.take(4).joinToString("-"),
            spoolSuffix = parts[4],
            unitCode = parts[0], service = parts[1], lineCode = parts[2],
            sitNumber = parts[3], revision = parts[5]
        )
    }
    return QrResult.VehiclePlate(clean)
}