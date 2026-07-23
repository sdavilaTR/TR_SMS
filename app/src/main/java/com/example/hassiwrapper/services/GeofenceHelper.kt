package com.example.hassiwrapper.services

import com.example.hassiwrapper.ServiceLocator

/** Point-in-polygon check for area geofences (`sms_sub_position.geofence_polygon` / `geofence_mode` —
 *  moved here from `sms_area` by MIGRATION_41_42 since sub-positions are the table actually synced live). */
object GeofenceHelper {

    const val MODE_GEOLOCATION = "GEOLOCATION"
    const val MODE_FORCED = "FORCED"

    sealed class CheckResult {
        /** No area in this project has a GEOLOCATION-mode geofence — nothing to enforce. */
        object NoGeofence : CheckResult()
        object Inside : CheckResult()
        /** Outside every GEOLOCATION-mode geofence in the project; names of the areas missed. */
        data class Outside(val areaNames: List<String>) : CheckResult()
    }

    /**
     * Checks (lat, lon) against every sub-position in [projectId] with `geofence_mode = GEOLOCATION`.
     * FORCED-mode areas are never checked (that mode means "accept without GPS validation").
     * A point counts as Inside if it falls in ANY geolocation-mode area — this is a project-wide
     * check for now (single active workshop), not a per-target-area rule.
     */
    suspend fun checkProjectGeofences(projectId: Int, lat: Double, lon: Double): CheckResult {
        val areas = ServiceLocator.smsSubPositionDao.getByProject(projectId)
            .filter { it.geofence_mode == MODE_GEOLOCATION && !it.geofence_polygon.isNullOrBlank() }
        if (areas.isEmpty()) return CheckResult.NoGeofence

        val missed = mutableListOf<String>()
        for (area in areas) {
            val polygon = KmlParser.deserialize(area.geofence_polygon!!)
            if (isInside(lat, lon, polygon)) return CheckResult.Inside
            missed += area.name
        }
        return CheckResult.Outside(missed)
    }

    /** Ray-casting test. `polygon` needs >= 3 points; result undefined for degenerate input. */
    fun isInside(lat: Double, lon: Double, polygon: List<GeoPolygonPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val crosses = (pi.lat > lat) != (pj.lat > lat)
            if (crosses) {
                val xIntersect = pj.lon + (lat - pj.lat) / (pi.lat - pj.lat) * (pi.lon - pj.lon)
                if (lon < xIntersect) inside = !inside
            }
            j = i
        }
        return inside
    }
}
