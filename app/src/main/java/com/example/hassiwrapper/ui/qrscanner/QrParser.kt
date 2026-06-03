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
    val upper = text.uppercase()
    if (upper.startsWith("JAFURAH PACKING LIST") || upper.startsWith("RIYAS PACKING LIST")) {
        val spoolCode = Regex("""(?i)ID:\s*(.+?)(?=\s*(?:$STOP_WORDS)|\z)""").find(text)
            ?.groupValues?.get(1)?.trim()
        val spoolSuffix = Regex("""(?i)Suffix:\s*(.+?)(?=\s*(?:Desc:|Diameter:|Lenght:|Length:|Priority:)|\z)""").find(text)
            ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        android.util.Log.d("QrParser", "spool block: spoolCode=$spoolCode spoolSuffix=$spoolSuffix")
        return if (!spoolCode.isNullOrBlank()) QrResult.Spool(spoolCode, spoolSuffix) else QrResult.Unknown(text)
    }
    if (text.startsWith("VEH:")) return QrResult.VehicleBadge(text.removePrefix("VEH:"))
    val urlVehicleId = Regex("""/vehicles?/(\d+)""").find(text)
        ?.groupValues?.getOrNull(1)?.toLongOrNull()
    if (urlVehicleId != null) return QrResult.VehicleId(urlVehicleId)
    return QrResult.VehiclePlate(text)
}