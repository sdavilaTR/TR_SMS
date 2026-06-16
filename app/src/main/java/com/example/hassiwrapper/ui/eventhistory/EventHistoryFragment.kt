package com.example.hassiwrapper.ui.eventhistory

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsAuditLogEntity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class EventHistoryFragment : Fragment() {

    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var txtEmpty: TextView
    private lateinit var adapter: HistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_event_history, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv       = view.findViewById(R.id.rvEventHistory)
        swipe    = view.findViewById(R.id.swipeRefresh)
        txtEmpty = view.findViewById(R.id.txtEmpty)

        adapter = HistoryAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        swipe.setOnRefreshListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            swipe.isRefreshing = true
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val items = ServiceLocator.smsAuditLogDao.getByProject(projectId)
            adapter.setItems(items)
            txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            swipe.isRefreshing = false
        }
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {

        private val items = mutableListOf<SmsAuditLogEntity>()

        fun setItems(newItems: List<SmsAuditLogEntity>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_event_history, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) = h.bind(items[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val viewStrip: View     = view.findViewById(R.id.viewStrip)
            private val txtAction: TextView = view.findViewById(R.id.txtActionLabel)
            private val txtName: TextView   = view.findViewById(R.id.txtEntityName)
            private val txtDetail: TextView = view.findViewById(R.id.txtDetail)
            private val txtMeta: TextView   = view.findViewById(R.id.txtMeta)

            fun bind(item: SmsAuditLogEntity) {
                viewStrip.setBackgroundColor(stripColor(item.entity_type, itemView.context))
                txtAction.text = labelFor(item.action_type)
                txtAction.setTextColor(actionColor(item.action_type, itemView.context))
                txtName.text = item.entity_name
                if (item.detail.isNullOrBlank()) {
                    txtDetail.visibility = View.GONE
                } else {
                    txtDetail.visibility = View.VISIBLE
                    txtDetail.text = item.detail
                }
                val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(item.timestamp))
                txtMeta.text = "${item.terminal_name}  ·  $date"
            }
        }

        private fun stripColor(entityType: String, ctx: Context): Int = when (entityType) {
            "SPOOL"         -> ContextCompat.getColor(ctx, R.color.primary)
            "PL"            -> ContextCompat.getColor(ctx, R.color.green)
            "VEHICULO"      -> ContextCompat.getColor(ctx, R.color.warning)
            "TRANSFERENCIA" -> ContextCompat.getColor(ctx, R.color.purple)
            else            -> ContextCompat.getColor(ctx, R.color.graphite)
        }

        private fun actionColor(actionType: String, ctx: Context): Int = when {
            actionType.endsWith("_CREADO")                                              -> ContextCompat.getColor(ctx, R.color.green)
            actionType.endsWith("_ELIMINADO") || actionType.endsWith("_ELIMINADO_HARD") -> ContextCompat.getColor(ctx, R.color.error)
            actionType.startsWith("TRANSFERENCIA_")                                     -> ContextCompat.getColor(ctx, R.color.purple)
            else                                                                        -> ContextCompat.getColor(ctx, R.color.primary)
        }

        private fun labelFor(actionType: String): String = when (actionType) {
            "SPOOL_CREADO"           -> "Spool creado"
            "SPOOL_ELIMINADO"        -> "Spool eliminado"
            "SPOOL_ELIMINADO_HARD"   -> "Spool eliminado (definitivo)"
            "PL_CREADO"              -> "Packing List creado"
            "PL_EDITADO"             -> "Packing List editado"
            "PL_ELIMINADO"           -> "Packing List eliminado"
            "VEHICULO_CREADO"        -> "Vehículo creado"
            "VEHICULO_EDITADO"       -> "Vehículo editado"
            "VEHICULO_ELIMINADO"     -> "Vehículo eliminado"
            "TRANSFERENCIA_ENVIADA"  -> "Envío registrado"
            "TRANSFERENCIA_RECIBIDA" -> "Recepción registrada"
            else -> actionType.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
