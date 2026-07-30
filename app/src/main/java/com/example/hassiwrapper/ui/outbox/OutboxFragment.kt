package com.example.hassiwrapper.ui.outbox

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
import com.example.hassiwrapper.data.db.entities.SmsOutboxEntity
import com.example.hassiwrapper.services.OutboxService
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Read-only view of the sync outbox: everything still PENDING (queued, waiting for the next
 * drain) or FAILED (gave up after MAX_ATTEMPTS — see OutboxService). Lets a field user confirm
 * "did my offline change actually go through" instead of that state being invisible outside a
 * DB pull, and lets a FAILED backlog be discarded from here instead of only via the SyncFragment
 * "Operaciones fallidas" banner (kept as-is; this screen is the fuller picture, not a replacement).
 */
class OutboxFragment : Fragment() {

    private lateinit var rv: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var txtEmpty: View
    private lateinit var btnDiscardFailed: MaterialButton
    private lateinit var adapter: OutboxAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_outbox, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rv               = view.findViewById(R.id.rvOutbox)
        swipe            = view.findViewById(R.id.swipeRefresh)
        txtEmpty         = view.findViewById(R.id.txtEmpty)
        btnDiscardFailed = view.findViewById(R.id.btnDiscardFailed)

        adapter = OutboxAdapter()
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        swipe.setOnRefreshListener { load() }
        btnDiscardFailed.setOnClickListener { confirmDiscardFailed() }
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            swipe.isRefreshing = true
            val items = ServiceLocator.smsOutboxDao.getPendingAndFailed()
            adapter.setItems(items)
            txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            btnDiscardFailed.visibility = if (items.any { it.status == "FAILED" }) View.VISIBLE else View.GONE
            swipe.isRefreshing = false
        }
    }

    private fun confirmDiscardFailed() {
        viewLifecycleOwner.lifecycleScope.launch {
            val count = ServiceLocator.smsOutboxDao.failedCount()
            if (!isAdded || count == 0) return@launch
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.sync_outbox_failed_dialog_discard_confirm_title)
                .setMessage(getString(R.string.sync_outbox_failed_dialog_discard_confirm_message, count))
                .setPositiveButton(R.string.sync_outbox_failed_dialog_discard_confirm_yes) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        ServiceLocator.smsOutboxDao.deleteAllFailed()
                        if (isAdded) load()
                    }
                }
                .setNegativeButton(R.string.sync_outbox_failed_dialog_discard_confirm_no, null)
                .show()
        }
    }

    private inner class OutboxAdapter : RecyclerView.Adapter<OutboxAdapter.VH>() {

        private val items = mutableListOf<SmsOutboxEntity>()

        fun setItems(newItems: List<SmsOutboxEntity>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_outbox, parent, false))

        override fun onBindViewHolder(h: VH, position: Int) = h.bind(items[position])

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val viewStrip: View     = view.findViewById(R.id.viewStrip)
            private val txtStatus: TextView = view.findViewById(R.id.txtStatus)
            private val txtOp: TextView     = view.findViewById(R.id.txtOpLabel)
            private val txtError: TextView  = view.findViewById(R.id.txtError)
            private val txtMeta: TextView   = view.findViewById(R.id.txtMeta)

            fun bind(op: SmsOutboxEntity) {
                val ctx = itemView.context
                val isFailed = op.status == "FAILED"
                val statusColor = ContextCompat.getColor(ctx, if (isFailed) R.color.error else R.color.warning)

                viewStrip.setBackgroundColor(statusColor)
                txtStatus.text = ctx.getString(if (isFailed) R.string.outbox_status_failed else R.string.outbox_status_pending)
                txtStatus.setTextColor(statusColor)

                txtOp.text = "${opLabel(ctx, op.op_type)} · ${entityLabel(ctx, op.entity_type)} (#${op.local_entity_id})"

                if (isFailed && !op.last_error.isNullOrBlank()) {
                    txtError.visibility = View.VISIBLE
                    txtError.text = op.last_error
                } else {
                    txtError.visibility = View.GONE
                }

                val dateStr = op.created_at.take(19).replace('T', ' ')
                txtMeta.text = ctx.getString(R.string.outbox_meta_format, dateStr, op.attempts)
            }
        }

        private fun opLabel(ctx: Context, opType: String): String = when (opType) {
            OutboxService.Op.CREATE      -> ctx.getString(R.string.outbox_op_create)
            OutboxService.Op.UPDATE      -> ctx.getString(R.string.outbox_op_update)
            OutboxService.Op.DELETE      -> ctx.getString(R.string.outbox_op_delete)
            OutboxService.Op.HARD_DELETE -> ctx.getString(R.string.outbox_op_hard_delete)
            OutboxService.Op.ASSIGN      -> ctx.getString(R.string.outbox_op_assign)
            OutboxService.Op.UNASSIGN    -> ctx.getString(R.string.outbox_op_unassign)
            else -> opType
        }

        private fun entityLabel(ctx: Context, entityType: String): String = when (entityType) {
            OutboxService.Entity.SPOOL           -> ctx.getString(R.string.outbox_entity_spool)
            OutboxService.Entity.VEHICLE         -> ctx.getString(R.string.outbox_entity_vehicle)
            OutboxService.Entity.PACKING_LIST    -> ctx.getString(R.string.outbox_entity_packing_list)
            OutboxService.Entity.INCIDENT        -> ctx.getString(R.string.outbox_entity_incident)
            OutboxService.Entity.PL_ASSIGN       -> ctx.getString(R.string.outbox_entity_pl_assign)
            OutboxService.Entity.VEHICLE_LOADING -> ctx.getString(R.string.outbox_entity_vehicle_loading)
            OutboxService.Entity.TRANSFER        -> ctx.getString(R.string.outbox_entity_transfer)
            OutboxService.Entity.ROUTE_STATE     -> ctx.getString(R.string.outbox_entity_route_state)
            else -> entityType
        }
    }
}
