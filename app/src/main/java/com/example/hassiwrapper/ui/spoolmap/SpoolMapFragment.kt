package com.example.hassiwrapper.ui.spoolmap

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.ProfileManager
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.dao.SmsSpoolMapMarker
import com.example.hassiwrapper.data.db.entities.SmsPositionEntity
import com.example.hassiwrapper.data.db.entities.SmsSubPositionEntity
import com.example.hassiwrapper.normalizeDeviceLocation
import com.example.hassiwrapper.services.GeofenceHelper
import com.example.hassiwrapper.services.KmlParser
import com.example.hassiwrapper.ui.createspool.SpoolDetailBottomSheet
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

class SpoolMapFragment : Fragment() {

    private data class GeofenceInfo(
        val positionName: String,
        val subPositionName: String,
        val polygons: List<List<GeoPoint>>
    ) {
        val label: String get() = "$positionName / $subPositionName"
        val allPoints: List<GeoPoint> get() = polygons.flatten()
    }

    private var mapView: MapView? = null
    private lateinit var txtEmpty: View
    private var hasCentered = false
    private var currentGeofences: List<GeofenceInfo> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        Configuration.getInstance().userAgentValue = requireContext().packageName
        return inflater.inflate(R.layout.fragment_spool_map, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        txtEmpty = view.findViewById(R.id.txtEmpty)
        mapView = view.findViewById<MapView>(R.id.mapViewSpools).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // MAPNIK has no tiles past z19; cap zoom so pinch doesn't degrade into blank stretched tiles.
            maxZoomLevel = 19.0
        }
        view.findViewById<View>(R.id.btnRefreshMap).setOnClickListener { load() }
        view.findViewById<View>(R.id.btnCenterGeofence).setOnClickListener { showCenterGeofenceDialog() }
        // First load comes from onResume (which always follows onViewCreated) — calling it here
        // too would just run the whole query twice on every open.
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
        // Pins now arrive from other terminals via the auto-sync download (syncSmsData's
        // "spool-locations" section), so a map left open would keep showing the snapshot it
        // loaded at onViewCreated until the user hit refresh. `hasCentered` stays true across
        // reloads, so re-rendering never re-zooms — the user's pan/zoom survives.
        load()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        mapView?.onDetach()
        mapView = null
        super.onDestroyView()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val isGuest = ProfileManager.currentUserRole() == ProfileManager.UserRole.GUEST

            // GUEST never sees other zones' spool positions or geofences — pin the map to the
            // terminal's own zone (device_location), same scoping as CreateSpoolFragment/HomeFragment.
            // A terminal further pinned to one sub-position (e.g. JAFURAH "Laydown GCP 5") is
            // narrowed one level more — never shows sibling GCP zones either.
            val zone = if (isGuest) normalizeDeviceLocation(ServiceLocator.configRepo.get("device_location")) else null
            if (isGuest && zone == null) {
                renderMarkers(emptyList(), emptyList(), emptyMap())
                return@launch
            }
            val zoneSubPositions = zone?.let { z ->
                ServiceLocator.smsPositionDao.getByCode(z)?.position_id
                    ?.let { ServiceLocator.smsSubPositionDao.getByPosition(projectId, it) }
            }.orEmpty()
            // Same rule as HomeFragment.loadGuestZoneStats: narrow to the pinned sub-position only
            // when it actually has siblings under the zone (JAFURAH GCP5/6/9), which is the only
            // case where showing the whole zone would leak another sub-position's spools. A lone
            // sub-position (an auto-seeded "WORKSHOP/WORKSHOP") has nothing to hide, and narrowing
            // there dropped every pin whose spool predates PositionHelper's sub-position stamping
            // (or was scanned by an unpinned terminal) — those rows still carry a null.
            val subPositionId = if (isGuest && zoneSubPositions.size > 1)
                ServiceLocator.configRepo.get("device_sub_position_id")?.toLongOrNull() else null

            val markers = when {
                subPositionId != null && zone != null -> ServiceLocator.smsSpoolLocationDao.getLatestByProjectZoneAndSubPosition(projectId, zone, subPositionId)
                zone != null -> ServiceLocator.smsSpoolLocationDao.getLatestByProjectAndZone(projectId, zone)
                else -> ServiceLocator.smsSpoolLocationDao.getLatestByProject(projectId)
            }
            val allGeofences = when {
                subPositionId != null -> zoneSubPositions.filter { it.sub_position_id == subPositionId }
                zone != null -> zoneSubPositions
                else -> ServiceLocator.smsSubPositionDao.getByProject(projectId)
            }
            val geofences = allGeofences.filter { !it.geofence_polygon.isNullOrBlank() }
            val positions = ServiceLocator.smsPositionDao.getAll().associateBy { it.position_id }
            renderMarkers(markers, geofences, positions)
        }
    }

    private fun renderMarkers(
        markers: List<SmsSpoolMapMarker>,
        geofences: List<SmsSubPositionEntity>,
        positions: Map<Int, SmsPositionEntity>
    ) {
        val mv = mapView ?: return
        val isEmpty = markers.isEmpty() && geofences.isEmpty()
        txtEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        mv.visibility = if (isEmpty) View.GONE else View.VISIBLE
        mv.overlays.clear()

        if (isEmpty) {
            mv.invalidate()
            return
        }

        val geofencePoints = mutableListOf<GeoPoint>()
        val geofenceInfos = mutableListOf<GeofenceInfo>()
        geofences.forEach { area ->
            val stored = area.geofence_polygon ?: return@forEach
            val rawPolygons = KmlParser.deserializeMulti(stored).filter { it.size >= 3 }
            val polygons = rawPolygons.map { polygon -> polygon.map { GeoPoint(it.lat, it.lon) } }
            if (polygons.isEmpty()) return@forEach
            geofencePoints += polygons.flatten()
            val positionName = positions[area.position_id]?.name?.ifBlank { null }
                ?: area.full_path.substringBefore("/")
            val subPositionName = area.name.ifBlank { area.code }.ifBlank { area.full_path.substringAfterLast("/") }
            val info = GeofenceInfo(positionName, subPositionName, polygons)
            geofenceInfos += info
            rawPolygons.zip(polygons).forEach { (raw, points) ->
                mv.overlays.add(Polygon(mv).apply {
                    this.points = points
                    title = info.label
                    fillColor = 0x220D47A1
                    strokeColor = 0xFF0D47A1.toInt()
                    strokeWidth = 3f
                    // Polygon.contains() hit-tests the last-drawn screen-space Path, which
                    // false-positives once zoomed out enough that the shape clips to a few px
                    // (e.g. auto-fit across JAFURAH's far-apart GCP sub-positions). Re-check the
                    // tapped point against the real lon/lat ring before opening the bubble.
                    setOnClickListener { polygon, _, eventPos ->
                        if (GeofenceHelper.isInside(eventPos.latitude, eventPos.longitude, raw)) {
                            polygon.setInfoWindowLocation(eventPos)
                            polygon.showInfoWindow()
                            true
                        } else false
                    }
                })
            }
        }
        currentGeofences = geofenceInfos

        markers.forEach { m ->
            val point = GeoPoint(m.latitude, m.longitude)
            mv.overlays.add(Marker(mv).apply {
                position = point
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = m.displayCode
                snippet = m.status
                setOnMarkerClickListener { _, _ ->
                    SpoolDetailBottomSheet.newInstance(m.spool_id).also { sheet ->
                        sheet.onSpoolUpdated = { load() }
                        sheet.show(childFragmentManager, "spool_detail")
                    }
                    true
                }
            })
        }

        if (!hasCentered) {
            hasCentered = true
            if (markers.size == 1 && geofencePoints.isEmpty()) {
                mv.controller.setZoom(17.0)
                mv.controller.setCenter(GeoPoint(markers[0].latitude, markers[0].longitude))
            } else {
                val allPoints = markers.map { GeoPoint(it.latitude, it.longitude) } + geofencePoints
                val lats = allPoints.map { it.latitude }
                val lons = allPoints.map { it.longitude }
                val box = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
                mv.post { mv.zoomToBoundingBox(box, false, 96) }
            }
        }
        mv.invalidate()
    }

    private fun showCenterGeofenceDialog() {
        val geofences = currentGeofences
        if (geofences.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.spool_map_no_geofences), Toast.LENGTH_SHORT).show()
            return
        }
        val labels = geofences.map { it.label }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.spool_map_center_geofence_title)
            .setItems(labels) { _, which ->
                val mv = mapView ?: return@setItems
                val points = geofences[which].allPoints
                val lats = points.map { it.latitude }
                val lons = points.map { it.longitude }
                val box = BoundingBox(lats.max(), lons.max(), lats.min(), lons.min())
                mv.post { mv.zoomToBoundingBox(box, true, 96) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
