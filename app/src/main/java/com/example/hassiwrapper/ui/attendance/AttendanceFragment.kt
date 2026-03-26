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
import kotlinx.coroutines.launch

class AttendanceFragment : Fragment() {

    private val logs = mutableListOf<AccessLogWithPerson>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_attendance, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rv = view.findViewById<RecyclerView>(R.id.rvLogs)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = LogAdapter()
        loadLogs()
    }

    private fun loadLogs() {
        viewLifecycleOwner.lifecycleScope.launch {
            val recent = ServiceLocator.accessLogDao.getRecentWithPerson(50)
            logs.clear()
            logs.addAll(recent)
            view?.findViewById<RecyclerView>(R.id.rvLogs)?.adapter?.notifyDataSetChanged()
        }
    }

    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val txt1: TextView = view.findViewById(android.R.id.text1)
            val txt2: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            view.setBackgroundColor(resources.getColor(R.color.card_bg, null))
            view.setPadding(16, 12, 16, 12)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = logs[position]
            val log = item.log
            val dir = when (log.direction) { "ENTRY" -> "↑ ENTRADA"; "EXIT" -> "↓ SALIDA"; else -> log.direction }
            val status = if (log.result == "GRANTED") "✓" else "✕"
            val workerName = when {
                item.givenName != null || item.familyName != null ->
                    "${item.givenName.orEmpty()} ${item.familyName.orEmpty()}".trim()
                else -> log.unique_id_value?.take(8)?.let { "$it…" } ?: "Desconocido"
            }
            val badge = item.badgeNumber?.let { " · $it" } ?: ""
            holder.txt1.text = "$status $dir — $workerName$badge"
            holder.txt1.setTextColor(resources.getColor(
                if (log.result == "GRANTED") R.color.granted else R.color.denied, null
            ))
            val time = log.event_time.take(19).replace('T', ' ')
            val syncIcon = if (log.synced) "☁" else "⏳"
            val failReason = if (log.result != "GRANTED" && log.failure_reason != null) "  ${log.failure_reason}" else ""
            holder.txt2.text = "$time  $syncIcon$failReason"
            holder.txt2.setTextColor(resources.getColor(R.color.on_surface_variant, null))
        }

        override fun getItemCount() = logs.size
    }
}
