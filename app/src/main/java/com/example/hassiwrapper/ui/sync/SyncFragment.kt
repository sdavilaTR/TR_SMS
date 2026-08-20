package com.example.hassiwrapper.ui.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class SyncFragment : Fragment() {

    private var apiReachable = false

    /** Rotación del icono del botón Sincronizar mientras hay una sincronización en curso.
     *
     *  Se anima el LEVEL de un RotateDrawable en vez de rotar la vista: el icono de un
     *  MaterialButton lo pinta el propio botón, no es una vista aparte, así que no se le puede
     *  aplicar una animación de vista. El invalidate() explícito en cada paso es necesario porque
     *  el botón no se repinta solo al cambiar el nivel del drawable. */
    private var syncIconAnimator: android.animation.ObjectAnimator? = null

    private fun setSyncIconSpinning(button: MaterialButton, spinning: Boolean) {
        if (spinning) {
            if (syncIconAnimator?.isRunning == true) return
            val base = ContextCompat.getDrawable(requireContext(), R.drawable.ic_sync) ?: return
            val rotate = android.graphics.drawable.RotateDrawable().apply {
                drawable = base
                fromDegrees = 0f
                toDegrees = 360f
                isPivotXRelative = true
                isPivotYRelative = true
                pivotX = 0.5f
                pivotY = 0.5f
            }
            button.icon = rotate
            syncIconAnimator = android.animation.ObjectAnimator.ofInt(rotate, "level", 0, 10000).apply {
                duration = 900L
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { button.invalidate() }
                start()
            }
        } else {
            syncIconAnimator?.cancel()
            syncIconAnimator = null
            // Se devuelve el icono original: el RotateDrawable se queda congelado en el ángulo que
            // tuviera al parar, y un icono torcido parece un fallo de pintado.
            button.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_sync)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_sync, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnSync = view.findViewById<MaterialButton>(R.id.btnFullSync)
        btnSync.setOnClickListener {
            performSync()
        }

        // El icono gira mientras haya CUALQUIER sincronización en curso, no sólo si se ha pulsado
        // el botón: al abrir la app, en el ciclo automático o al volver la cobertura también.
        // repeatOnLifecycle(STARTED) lo desengancha al salir de la pantalla, para no dejar una
        // animación corriendo contra una vista que ya no se ve.
        (activity as? MainActivity)?.let { main ->
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    main.anySyncInProgressFlow.collect { syncing ->
                        setSyncIconSpinning(btnSync, syncing)
                    }
                }
            }
        }
        view.findViewById<View>(R.id.cardOutboxFailed).setOnClickListener {
            showFailedOutboxDialog()
        }
        view.findViewById<View>(R.id.cardKpiPending).setOnClickListener {
            showPendingDialog()
        }

        loadKpis()
        loadLastSync()
        checkConnectivity()
        checkAuthStatus()
    }

    override fun onResume() {
        super.onResume()
        checkConnectivity()
        checkAuthStatus()
        loadKpis()
    }

    private fun checkConnectivity() {
        viewLifecycleOwner.lifecycleScope.launch {
            val v = view ?: return@launch

            val dotNetwork = v.findViewById<View>(R.id.dotNetwork)
            val txtNetwork = v.findViewById<TextView>(R.id.txtNetworkStatus)
            val dotApi = v.findViewById<View>(R.id.dotApi)
            val txtApi = v.findViewById<TextView>(R.id.txtApiStatus)

            dotNetwork.setBackgroundResource(R.drawable.dot_grey)
            txtNetwork.text = getString(R.string.sync_status_checking)
            dotApi.setBackgroundResource(R.drawable.dot_grey)
            txtApi.text = getString(R.string.sync_status_checking)

            val networkOnline = isNetworkAvailable()
            if (networkOnline) {
                dotNetwork.setBackgroundResource(R.drawable.dot_green)
                txtNetwork.text = getString(R.string.sync_network_online)
            } else {
                dotNetwork.setBackgroundResource(R.drawable.dot_red)
                txtNetwork.text = getString(R.string.sync_network_offline)
                dotApi.setBackgroundResource(R.drawable.dot_red)
                txtApi.text = getString(R.string.sync_api_fail)
                apiReachable = false
                loadKpis()
                return@launch
            }

            val status = ServiceLocator.apiClient.checkConnectivity()
            apiReachable = status.apiReachable
            if (status.apiReachable) {
                dotApi.setBackgroundResource(R.drawable.dot_green)
                txtApi.text = getString(R.string.sync_api_ok)
            } else {
                dotApi.setBackgroundResource(R.drawable.dot_red)
                txtApi.text = getString(R.string.sync_api_fail)
            }
            loadKpis()
        }
    }

    private fun checkAuthStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            val v = view ?: return@launch
            val dotAuth = v.findViewById<View>(R.id.dotAuth)
            val txtAuth = v.findViewById<TextView>(R.id.txtAuthStatus)
            val btnSync = v.findViewById<MaterialButton>(R.id.btnFullSync)

            var authenticated = ServiceLocator.authRepo.isAuthenticated()

            // If not authenticated, attempt auto-re-login with stored device code
            if (!authenticated) {
                dotAuth.setBackgroundResource(R.drawable.dot_grey)
                txtAuth.text = getString(R.string.sync_auto_relogin)
                val relogged = ServiceLocator.authRepo.reLoginWithStoredCode(
                    ServiceLocator.apiClient.getService()
                )
                authenticated = relogged
            }

            if (authenticated) {
                dotAuth.setBackgroundResource(R.drawable.dot_green)
                txtAuth.text = getString(R.string.sync_auth_ok)
                btnSync.isEnabled = true
            } else {
                dotAuth.setBackgroundResource(R.drawable.dot_orange)
                txtAuth.text = getString(R.string.sync_auth_none)
                btnSync.isEnabled = false
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Un grupo de trabajo sin entregar: qué es y qué filas concretas lo forman. */
    private data class PendingGroup(val titulo: String, val items: List<String>)

    /**
     * Lo que este terminal todavía NO ha conseguido enviar al servidor.
     *
     * Antes esto sumaba filas con `synced = 0` de tres tablas, que es otra cosa: "esta fila la tocó
     * este terminal alguna vez", no "esto está sin entregar". Ese flag lo pone a 0 cualquier apunte
     * local —incluida la limpieza que hace el propio sync— y sólo vuelve a 1 cuando el servidor
     * devuelve esa fila, cosa que con sincronización delta puede no pasar nunca. Resultado: un
     * número clavado durante días, distinto en cada aparato, que no significaba nada.
     *
     * Ahora se cuentan las COLAS DE SUBIDA de verdad, las mismas que recorre SyncService. Si aquí
     * pone 3, es que hay tres cosas que el servidor aún no tiene. Si pone 0, no hay nada.
     */
    private suspend fun collectPending(): List<PendingGroup> {
        val grupos = mutableListOf<PendingGroup>()

        // Reubicaciones: posición/sub-posición escritas aquí y aún sin confirmar por el servidor.
        // Es la misma consulta que alimenta SyncService.uploadPendingRelocations, no una parecida.
        ServiceLocator.smsSpoolDao.getUnsyncedRelocated().let { list ->
            if (list.isNotEmpty()) grupos += PendingGroup(
                getString(R.string.sync_pending_group_spools, list.size),
                list.map { it.spool_code.ifBlank { "#${it.spool_id}" } })
        }
        ServiceLocator.smsPackingListDao.getUnsynced().let { list ->
            if (list.isNotEmpty()) grupos += PendingGroup(
                getString(R.string.sync_pending_group_pls, list.size),
                list.map { it.packing_list_name.ifBlank { "#${it.packing_list_id}" } })
        }
        ServiceLocator.smsVehicleLoadingDao.getUnsynced().let { list ->
            if (list.isNotEmpty()) grupos += PendingGroup(
                getString(R.string.sync_pending_group_loadings, list.size),
                list.map { it.vehicle_plate?.takeIf { p -> p.isNotBlank() } ?: "#${it.loading_id}" })
        }
        ServiceLocator.smsTransferDao.getUnsynced().let { list ->
            if (list.isNotEmpty()) grupos += PendingGroup(
                getString(R.string.sync_pending_group_transfers, list.size),
                list.map { "${it.transfer_type} · ${it.packing_list_name.ifBlank { "#${it.packing_list_id}" }}" })
        }
        ServiceLocator.smsPackingListSpoolDao.getUnsynced().let { list ->
            if (list.isNotEmpty()) grupos += PendingGroup(
                getString(R.string.sync_pending_group_links, list.size),
                list.map { "spool #${it.spool_id} → lista #${it.packing_list_id}" })
        }

        // La cola del outbox: envíos, ediciones y borrados que esperan turno. FAILED va aparte,
        // en su propia tarjeta, porque ahí sí hace falta que alguien decida.
        ServiceLocator.smsOutboxDao.getPending().let { list ->
            if (list.isNotEmpty()) grupos += PendingGroup(
                getString(R.string.sync_pending_group_queue, list.size),
                list.map { "${it.entity_type} · ${it.op_type} · #${it.local_entity_id}" })
        }
        return grupos
    }

    private suspend fun countRealPending(): Int = collectPending().sumOf { it.items.size }

    /**
     * Desglose de "Pendientes Sync": qué es exactamente cada cosa que falta por subir.
     * Con "Ver todo" se despliega la lista completa, sin el tope de 8 por grupo.
     */
    private fun showPendingDialog(verTodo: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            val grupos = collectPending()
            val queueFailed = ServiceLocator.smsOutboxDao.failedCount()
            val total = grupos.sumOf { it.items.size }
            val tope = if (verTodo) Int.MAX_VALUE else 8

            val sb = StringBuilder()
            if (total == 0) {
                sb.append(getString(R.string.sync_pending_dialog_empty))
                if (queueFailed > 0) {
                    sb.append("\n\n").append(getString(R.string.sync_pending_only_failed, queueFailed))
                }
            } else {
                grupos.forEach { g ->
                    if (sb.isNotEmpty()) sb.append("\n")
                    sb.append(g.titulo).append("\n")
                    g.items.take(tope).forEach { sb.append("  • ").append(it).append("\n") }
                    if (g.items.size > tope) {
                        sb.append("  ").append(getString(R.string.sync_pending_more, g.items.size - tope)).append("\n")
                    }
                }
                sb.append("\n").append(getString(R.string.sync_pending_queue_note))
                if (queueFailed > 0) {
                    sb.append("\n\n").append(getString(R.string.sync_pending_only_failed, queueFailed))
                }
            }

            val hayMas = !verTodo && grupos.any { it.items.size > 8 }
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sync_pending_dialog_title)
                .setMessage(sb.toString().trim())
                .setPositiveButton(R.string.sync_pending_dialog_close, null)
                .apply {
                    if (hayMas) setNeutralButton(R.string.sync_pending_see_all) { _, _ -> showPendingDialog(verTodo = true) }
                }
                .show()
        }
    }

    private fun loadKpis() {
        if (view == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val v = view ?: return@launch
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6

            // Matches HomeFragment's "spool count" KPI (scanned-only) so the two screens
            // don't show two different numbers for what looks like the same metric.
            val spoolCount = ServiceLocator.smsSpoolDao.countScannedByProject(projectId)
            val packingListCount = ServiceLocator.smsPackingListDao.countByProject(projectId)
            val vehicleCount = ServiceLocator.smsVehicleDao.countByProject(projectId)
            val inTransitCount = ServiceLocator.smsSpoolDao.countInTransitByProject(projectId)
            // Device-wide, not project-scoped — SyncService uploads unsynced rows across every
            // locally-cached project, not just the one selected here, so the KPI must match.
            val pendingTotal = countRealPending()

            val synced = if (apiReachable) getString(R.string.sync_kpi_workers_synced) else ""

            // Spools KPI
            val txtSpools = v.findViewById<TextView>(R.id.txtKpiRecords)
            txtSpools.text = spoolCount.toString()
            txtSpools.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            v.findViewById<TextView>(R.id.txtKpiRecordsLabel).text = synced

            // Packing Lists KPI
            val txtPL = v.findViewById<TextView>(R.id.txtKpiPhotos)
            txtPL.text = packingListCount.toString()
            txtPL.setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
            v.findViewById<TextView>(R.id.txtKpiPhotosLabel).text = synced

            // SMS Vehicles KPI
            val txtVeh = v.findViewById<TextView>(R.id.txtKpiObservations)
            txtVeh.text = vehicleCount.toString()
            txtVeh.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondary))
            v.findViewById<TextView>(R.id.txtKpiObservationsLabel).text = synced

            // En Tránsito KPI
            val txtTransit = v.findViewById<TextView>(R.id.txtKpiWorkers)
            txtTransit.text = inTransitCount.toString()
            txtTransit.setTextColor(ContextCompat.getColor(requireContext(),
                if (inTransitCount > 0) R.color.warning else R.color.on_surface_variant))
            v.findViewById<TextView>(R.id.txtKpiWorkersLabel).text =
                if (inTransitCount > 0) getString(R.string.sync_kpi_in_transit_active) else ""

            // Pendientes Sync KPI
            setupPendingKpi(
                v.findViewById(R.id.txtKpiVehicles),
                v.findViewById(R.id.txtKpiVehiclesLabel),
                pendingTotal
            )

            // Outbox ops that gave up permanently after MAX_ATTEMPTS — otherwise invisible to the user
            val failedCount = ServiceLocator.smsOutboxDao.failedCount()
            val cardFailed = v.findViewById<View>(R.id.cardOutboxFailed)
            if (failedCount > 0) {
                cardFailed.visibility = View.VISIBLE
                v.findViewById<TextView>(R.id.txtOutboxFailed).text =
                    getString(R.string.sync_outbox_failed_banner, failedCount)
            } else {
                cardFailed.visibility = View.GONE
            }

            // Delta-sync flag has no UI toggle — only a debug adb hook (see MainActivity
            // DEBUG_SET_CONFIG). It stayed silently ON on a device for a full day in the past;
            // this banner makes that state visible instead of invisible.
            val deltaEnabled = ServiceLocator.configRepo.get("sms_delta_sync_enabled") == "true"
            v.findViewById<View>(R.id.cardDeltaActive).visibility =
                if (deltaEnabled) View.VISIBLE else View.GONE
        }
    }

    private fun showFailedOutboxDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val failed = ServiceLocator.smsOutboxDao.getFailed()
            if (!isAdded || failed.isEmpty()) return@launch
            val message = failed.joinToString("\n\n") { op ->
                getString(
                    R.string.sync_outbox_failed_dialog_item,
                    op.entity_type, op.op_type, op.local_entity_id, op.last_error ?: ""
                )
            }
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.sync_outbox_failed_dialog_title)
                .setMessage(message)
                .setPositiveButton(R.string.scan_action_close, null)
                .setNegativeButton(R.string.sync_outbox_failed_dialog_discard) { _, _ ->
                    confirmDiscardFailedOutbox()
                }
                .show()
        }
    }

    private fun confirmDiscardFailedOutbox() {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = ServiceLocator.smsOutboxDao.failedCount()
            if (!isAdded || count == 0) return@launch
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.sync_outbox_failed_dialog_discard_confirm_title)
                .setMessage(getString(R.string.sync_outbox_failed_dialog_discard_confirm_message, count))
                .setPositiveButton(R.string.sync_outbox_failed_dialog_discard_confirm_yes) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        ServiceLocator.smsOutboxDao.deleteAllFailed()
                        if (isAdded) loadKpis()
                    }
                }
                .setNegativeButton(R.string.sync_outbox_failed_dialog_discard_confirm_no, null)
                .show()
        }
    }

    private fun setupPendingKpi(txtValue: TextView, txtLabel: TextView, pending: Int) {
        if (pending == 0) {
            txtValue.text = "✓"
            txtValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.granted))
            txtLabel.text = getString(R.string.sync_kpi_synced)
        } else {
            txtValue.text = pending.toString()
            txtValue.setTextColor(ContextCompat.getColor(requireContext(), R.color.warning))
            txtLabel.text = getString(R.string.sync_kpi_pending)
        }
    }

    private fun loadLastSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            val lastSync = ServiceLocator.configRepo.get("last_sync")
            view?.findViewById<TextView>(R.id.txtLastSync)?.text = if (lastSync != null) {
                getString(R.string.sync_last_format, lastSync.take(19).replace('T', ' '))
            } else getString(R.string.sync_last_none)
        }
    }

    private fun performSync() {
        val v = view ?: return
        val btn = v.findViewById<MaterialButton>(R.id.btnFullSync)
        val progress = v.findViewById<View>(R.id.progressSync)
        val dotApi = v.findViewById<View>(R.id.dotApi)
        val txtApi = v.findViewById<TextView>(R.id.txtApiStatus)
        val cardResult = v.findViewById<View>(R.id.cardSyncResult)
        val scrollLog = v.findViewById<ScrollView>(R.id.scrollSyncLog)
        val txtLog = v.findViewById<TextView>(R.id.txtSyncLog)

        viewLifecycleOwner.lifecycleScope.launch {
            if (!ServiceLocator.authRepo.isAuthenticated()) {
                scrollLog.visibility = View.VISIBLE
                txtLog.text = getString(R.string.sync_auth_required)
                return@launch
            }

            btn.isEnabled = false
            progress.visibility = View.VISIBLE
            cardResult.visibility = View.GONE
            scrollLog.visibility = View.VISIBLE
            txtLog.text = getString(R.string.sync_log_start)

            val appendLog: (String) -> Unit = { msg ->
                val current = txtLog.text.toString()
                txtLog.text = if (current.isEmpty()) msg else "$current\n$msg"
                scrollLog.post { scrollLog.fullScroll(View.FOCUS_DOWN) }
            }

            val result = ServiceLocator.syncService.fullSync(
                onRetry = { retry ->
                    appendLog(getString(R.string.sync_retrying, retry.attempt, retry.waitSeconds))
                    dotApi.setBackgroundResource(R.drawable.dot_grey)
                    txtApi.text = getString(R.string.sync_status_checking)
                },
                onProgress = appendLog
            )

            if (result.success) {
                appendLog(getString(R.string.sync_step_sms))
                try {
                    // force=true: user explicitly asked for a sync, don't let the
                    // auto-sync throttle (see MainActivity.doSyncSmsData) skip the spool fetch.
                    (requireActivity() as? com.example.hassiwrapper.MainActivity)?.syncSmsData(appendLog, force = true)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("SyncSMS", "SMS sync failed", e)
                    appendLog(getString(R.string.sync_error, e.message))
                }
            }

            if (!isAdded) return@launch

            btn.isEnabled = true
            progress.visibility = View.GONE

            showSyncResultCard(v, result)

            if (result.success) {
                dotApi.setBackgroundResource(R.drawable.dot_green)
                txtApi.text = getString(R.string.sync_api_ok)
                apiReachable = true
            } else {
                checkConnectivity()
            }

            loadKpis()
            loadLastSync()
        }
    }

    private fun showSyncResultCard(v: View, result: com.example.hassiwrapper.services.SyncService.SyncResult) {
        val card = v.findViewById<View>(R.id.cardSyncResult)
        val txtIcon = v.findViewById<TextView>(R.id.txtResultIcon)
        val txtTitle = v.findViewById<TextView>(R.id.txtResultTitle)
        val txtError = v.findViewById<TextView>(R.id.txtResultError)

        card.visibility = View.VISIBLE
        // Per-category counts (logs/workers/AC-vehicles/observations/photos) belonged to the
        // old Access-Control SyncResult; syncSmsUploads only reports success/error.
        v.findViewById<TextView>(R.id.txtResultLogs).visibility = View.GONE
        v.findViewById<TextView>(R.id.txtResultWorkers).visibility = View.GONE
        v.findViewById<TextView>(R.id.txtResultVehicles).visibility = View.GONE
        v.findViewById<TextView>(R.id.txtResultObservations).visibility = View.GONE
        v.findViewById<TextView>(R.id.txtResultPhotos).visibility = View.GONE

        if (result.success) {
            txtIcon.text = "✓"
            txtIcon.setTextColor(ContextCompat.getColor(requireContext(), R.color.granted))
            txtTitle.text = getString(R.string.sync_result_title_ok)
            txtTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.granted))
        } else {
            txtIcon.text = "✕"
            txtIcon.setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
            txtTitle.text = getString(R.string.sync_result_title_error)
            txtTitle.setTextColor(ContextCompat.getColor(requireContext(), R.color.error))
        }

        if (!result.success && result.error != null) {
            txtError.text = result.error
            txtError.visibility = View.VISIBLE
        } else {
            txtError.visibility = View.GONE
        }
    }

}
