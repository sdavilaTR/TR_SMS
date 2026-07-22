package com.example.hassiwrapper.ui.spoolmap

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.dao.SmsSpoolMapMarker
import com.example.hassiwrapper.data.db.entities.SmsAreaEntity
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

    private var mapView: MapView? = null
    private lateinit var txtEmpty: View
    private var hasCentered = false

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
        }
        view.findViewById<View>(R.id.btnRefreshMap).setOnClickListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
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
            val markers = ServiceLocator.smsSpoolLocationDao.getLatestByProject(projectId)
            val geofences = ServiceLocator.smsAreaDao.getByProject(projectId).filter { !it.geofence_polygon.isNullOrBlank() }
            renderMarkers(markers, geofences)
        }
    }

    private fun renderMarkers(markers: List<SmsSpoolMapMarker>, geofences: List<SmsAreaEntity>) {
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
        geofences.forEach { area ->
            val polygon = area.geofence_polygon?.let { KmlParser.deserialize(it) } ?: return@forEach
            val points = polygon.map { GeoPoint(it.lat, it.lon) }
            if (points.size < 3) return@forEach
            geofencePoints += points
            mv.overlays.add(Polygon(mv).apply {
                this.points = points
                title = area.name
                fillColor = 0x220D47A1
                strokeColor = 0xFF0D47A1.toInt()
                strokeWidth = 3f
            })
        }

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
}
