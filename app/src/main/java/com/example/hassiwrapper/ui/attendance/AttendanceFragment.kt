package com.example.hassiwrapper.ui.attendance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.dao.AccessLogWithPerson
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

class AttendanceFragment : Fragment() {

    private val logs = mutableListOf<AccessLogWithPerson>()

    // null = all, true = synced, false = pending
    private var syncFilter: Boolean? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_attendance, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rvLogs)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = LogAdapter()

        view.findViewById<ChipGroup>(R.id.chipGroupFilter).setOnCheckedStateChangeListener { _, checkedIds ->
            syncFilter = when {
                checkedIds.contains(R.id.chipSynced) -> true
                checkedIds.contains(R.id.chipPending) -> false
                else -> null
            }
            loadLogs()
        }

        loadLogs()
    }

    private fun loadLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val recent = when (val f = syncFilter) {
                null -> ServiceLocator.accessLogDao.getRecentWithPerson(200)
                else -> ServiceLocator.accessLogDao.getRecentWithPersonFiltered(synced = f)
            }
            logs.clear()
            logs.addAll(recent)
            view?.findViewById<RecyclerView>(R.id.rvLogs)?.adapter?.notifyDataSetChanged()
        }
    }

    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvResult: TextView = view.findViewById(R.id.tvResult)
            val tvDirection: TextView = view.findViewById(R.id.tvDirection)
            val tvWorkerName: TextView = view.findViewById(R.id.tvWorkerName)
            val tvSyncIcon: TextView = view.findViewById(R.id.tvSyncIcon)
            val tvBadge: TextView = view.findViewById(R.id.tvBadge)
            val tvTime: TextView = view.findViewById(R.id.tvTime)
            val tvFailReason: TextView = view.findViewById(R.id.tvFailReason)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_access_log, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = logs[position]
            val log = item.log

            val granted = log.result == "GRANTED"
            holder.tvResult.text = if (granted) "✓" else "✕"
            holder.tvResult.setTextColor(resources.getColor(
                if (granted) R.color.granted else R.color.denied, null
            ))

            holder.tvDirection.text = when (log.direction) {
                "ENTRY" -> getString(R.string.scanner_entry)
                "EXIT"  -> getString(R.string.scanner_exit)
                else    -> log.direction
            }
            holder.tvDirection.setTextColor(resources.getColor(
                if (granted) R.color.granted else R.color.denied, null
            ))

            holder.tvWorkerName.text = when {
                item.givenName != null || item.familyName != null ->
                    "${item.givenName.orEmpty()} ${item.familyName.orEmpty()}".trim()
                else -> log.unique_id_value?.take(12)?.let { "$it…" } ?: "—"
            }

            holder.tvSyncIcon.text = if (log.synced) "☁" else "⏳"

            holder.tvBadge.text = item.badgeNumber?.let { "Badge: $it" } ?: ""

            holder.tvTime.text = log.event_time.take(19).replace('T', ' ')

            if (!granted && log.failure_reason != null) {
                holder.tvFailReason.text = log.failure_reason
                holder.tvFailReason.visibility = View.VISIBLE
            } else {
                holder.tvFailReason.visibility = View.GONE
            }
        }

        override fun getItemCount() = logs.size
    }
}
