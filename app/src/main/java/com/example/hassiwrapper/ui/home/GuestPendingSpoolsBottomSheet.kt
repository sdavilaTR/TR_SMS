package com.example.hassiwrapper.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.ui.createspool.SpoolDetailBottomSheet
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/** Guest home "pending" KPI drill-down: lists spools scanned/moved offline on this terminal
 *  that are still awaiting upload (synced=0), same bucket as the blue KPI count in
 *  HomeFragment.loadGuestZoneStats — see SmsSpoolDao.getPendingByProjectAndZone. */
class GuestPendingSpoolsBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_PROJECT_ID = "project_id"
        private const val ARG_LOCATION = "location"
        private const val ARG_SUB_POSITION_ID = "sub_position_id"

        fun newInstance(projectId: Int, location: String, subPositionId: Long?): GuestPendingSpoolsBottomSheet =
            GuestPendingSpoolsBottomSheet().apply {
                arguments = Bundle().apply {
                    putInt(ARG_PROJECT_ID, projectId)
                    putString(ARG_LOCATION, location)
                    subPositionId?.let { putLong(ARG_SUB_POSITION_ID, it) }
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_guest_pending_spools_bottom_sheet, container, false)

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = arguments ?: return
        val projectId = args.getInt(ARG_PROJECT_ID)
        val location = args.getString(ARG_LOCATION) ?: return
        val subPositionId = args.getLong(ARG_SUB_POSITION_ID, -1L).takeIf { it != -1L }

        val adapter = PendingSpoolAdapter { spool ->
            SpoolDetailBottomSheet.newInstance(spool.spool_id).show(childFragmentManager, "spool_detail")
        }
        view.findViewById<RecyclerView>(R.id.rvPendingSpools).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val spools = ServiceLocator.smsSpoolDao.getPendingByProjectAndZone(projectId, location, subPositionId)
            adapter.submit(spools)
            view.findViewById<TextView>(R.id.txtPendingListEmpty).visibility =
                if (spools.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private class PendingSpoolAdapter(
        private val onClick: (SmsSpoolEntity) -> Unit
    ) : RecyclerView.Adapter<PendingSpoolAdapter.VH>() {

        private var items: List<SmsSpoolEntity> = emptyList()

        fun submit(newItems: List<SmsSpoolEntity>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val code: TextView = view.findViewById(R.id.txtSpoolCode)
            val suffix: TextView = view.findViewById(R.id.txtSpoolSuffix)
            val revision: TextView = view.findViewById(R.id.txtSpoolRevision)
            val line: TextView = view.findViewById(R.id.txtSpoolLine)
            val details: TextView = view.findViewById(R.id.txtSpoolDetails)
            val updated: TextView = view.findViewById(R.id.txtPackingList)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_spool, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val spool = items[position]
            holder.code.text = spool.spool_code.ifBlank { spool.spool_id.toString() }
            holder.suffix.text = spool.spool_suffix.orEmpty()
            holder.suffix.visibility = if (spool.spool_suffix.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.revision.text = spool.revision.orEmpty()
            holder.revision.visibility = if (spool.revision.isNullOrBlank()) View.GONE else View.VISIBLE
            if (!spool.line_code.isNullOrBlank()) {
                holder.line.text = spool.line_code
                holder.line.visibility = View.VISIBLE
            } else {
                holder.line.visibility = View.GONE
            }
            holder.details.visibility = View.GONE
            val updatedAt = spool.updated_at
            if (!updatedAt.isNullOrBlank()) {
                holder.updated.text = holder.itemView.context.getString(
                    R.string.home_guest_pending_list_updated_at, updatedAt
                )
                holder.updated.visibility = View.VISIBLE
            } else {
                holder.updated.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onClick(spool) }
        }
    }
}
