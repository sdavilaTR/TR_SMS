package com.example.hassiwrapper.services

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/** One vertex of a geofence polygon boundary. */
data class GeoPolygonPoint(val lon: Double, val lat: Double)

/**
 * Minimal KML reader for the single use case this app needs: a Placemark/Polygon boundary
 * (e.g. a prefab workshop or laydown yard exported from Google Earth/My Maps), not general KML.
 */
object KmlParser {

    /** Parses the first `<Polygon><outerBoundaryIs><coordinates>` found in the document. Null if none. */
    fun parsePolygon(kml: String): List<GeoPolygonPoint>? {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(kml.reader())
        return extractFirstPolygon(parser)
    }

    fun parsePolygon(input: InputStream): List<GeoPolygonPoint>? {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        return extractFirstPolygon(parser)
    }

    private fun extractFirstPolygon(parser: XmlPullParser): List<GeoPolygonPoint>? {
        var inOuterBoundary = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "outerBoundaryIs" -> inOuterBoundary = true
                    "coordinates" -> if (inOuterBoundary) {
                        val text = parser.nextText()
                        val points = parseCoordinates(text)
                        if (points.size >= 3) return points
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "outerBoundaryIs") {
                inOuterBoundary = false
            }
            event = parser.next()
        }
        return null
    }

    /** KML coordinate tuples are whitespace-separated "lon,lat[,alt]". */
    private fun parseCoordinates(text: String): List<GeoPolygonPoint> =
        text.trim().split(Regex("\\s+")).mapNotNull { tuple ->
            val parts = tuple.split(",")
            if (parts.size < 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            GeoPolygonPoint(lon, lat)
        }

    /** Serializes to the "lon,lat|lon,lat|..." format stored in `sms_area.geofence_polygon`. */
    fun serialize(points: List<GeoPolygonPoint>): String =
        points.joinToString("|") { "${it.lon},${it.lat}" }

    fun deserialize(stored: String): List<GeoPolygonPoint> =
        stored.split("|").mapNotNull { pair ->
            val parts = pair.split(",")
            if (parts.size != 2) return@mapNotNull null
            val lon = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            GeoPolygonPoint(lon, lat)
        }
}
