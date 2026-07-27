package com.example.hassiwrapper.services

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/** One vertex of a geofence polygon boundary. */
data class GeoPolygonPoint(val lon: Double, val lat: Double)

/** A Placemark/Polygon boundary found in a KML file, with its `<name>` (blank if none). */
data class KmlPolygon(val placemarkName: String, val points: List<GeoPolygonPoint>)

/**
 * Minimal KML reader for the single use case this app needs: Placemark/Polygon boundaries
 * (e.g. a prefab workshop or laydown yard exported from Google Earth/My Maps), not general KML.
 */
object KmlParser {

    /** Parses every `<Placemark><Polygon><outerBoundaryIs><coordinates>` in the document. */
    fun parseAllPolygons(kml: String): List<KmlPolygon> {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(kml.reader())
        return extractAllPolygons(parser)
    }

    fun parseAllPolygons(input: InputStream): List<KmlPolygon> {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        return extractAllPolygons(parser)
    }

    private fun extractAllPolygons(parser: XmlPullParser): List<KmlPolygon> {
        val result = mutableListOf<KmlPolygon>()
        var currentPlacemarkName: String? = null
        var inOuterBoundary = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Placemark" -> currentPlacemarkName = null
                    "name" -> if (currentPlacemarkName == null) currentPlacemarkName = parser.nextText()
                    "outerBoundaryIs" -> inOuterBoundary = true
                    "coordinates" -> if (inOuterBoundary) {
                        val text = parser.nextText()
                        val points = parseCoordinates(text)
                        if (points.size >= 3) {
                            result.add(KmlPolygon(currentPlacemarkName?.trim().orEmpty(), points))
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "outerBoundaryIs") {
                inOuterBoundary = false
            }
            event = parser.next()
        }
        return result
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

    /** Separates multiple polygons linked to the same sub-position (see [serializeMulti]). */
    private const val POLYGON_SEPARATOR = ";;"

    /** Serializes to the "lon,lat|lon,lat|..." format stored in `sms_sub_position.geofence_polygon`. */
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

    /**
     * Serializes several disjoint polygons linked to the same sub-position (e.g. a workshop's
     * outer boundary plus separate subzone shapes) into one `geofence_polygon` string.
     */
    fun serializeMulti(polygons: List<List<GeoPolygonPoint>>): String =
        polygons.joinToString(POLYGON_SEPARATOR) { serialize(it) }

    /**
     * Reads back a `geofence_polygon` value as a list of polygons. Old rows saved before multi-
     * polygon support (no [POLYGON_SEPARATOR] present) come back as a single-element list, so this
     * is a drop-in replacement for [deserialize] on every existing row.
     */
    fun deserializeMulti(stored: String): List<List<GeoPolygonPoint>> =
        stored.split(POLYGON_SEPARATOR).filter { it.isNotBlank() }.map { deserialize(it) }
}
