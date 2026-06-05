package com.example.hassiwrapper.ui.qrscanner

internal sealed class QrResult {
    data class Spool(val spoolCode: String, val spoolSuffix: String?) : QrResult()
    data class VehicleId(val id: Long) : QrResult()
    data class VehiclePlate(val plate: String) : QrResult()
    data class VehicleBadge(val uuid: String) : QrResult()
    data class Unknown(val raw: String) : QrResult()
}

// QR fields may be newline-separated or concatenated with no separator; use regex to extract.
private val STOP_WORDS = """Suffix:|Desc:|Diameter:|Lenght:|Length:|Priority:"""

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
    if (clean.startsWith("VEH:")) return QrResult.VehicleBadge(clean.removePrefix("VEH:"))
    val urlVehicleId = Regex("""/vehicles?/(\d+)""").find(clean)
        ?.groupValues?.getOrNull(1)?.toLongOrNull()
    if (urlVehicleId != null) return QrResult.VehicleId(urlVehicleId)
    return QrResult.VehiclePlate(clean)
}