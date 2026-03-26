package com.example.hassiwrapper.ui.workers

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.PersonEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WorkersFragment : Fragment() {

    private val workers = mutableListOf<PersonEntity>()
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_workers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rvWorkers)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = WorkerAdapter()

        val search = view.findViewById<EditText>(R.id.inputSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(300)
                    loadWorkers(s.toString().trim())
                }
            }
        })

        loadWorkers("")
    }

    private fun loadWorkers(query: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = if (query.isNotEmpty()) {
                ServiceLocator.personDao.search(query)
            } else {
                ServiceLocator.personDao.getAll()
            }
            workers.clear()
            workers.addAll(result)
            view?.let { v ->
                v.findViewById<TextView>(R.id.txtWorkerCount).text = getString(R.string.workers_count, workers.size)
                v.findViewById<RecyclerView>(R.id.rvWorkers).adapter?.notifyDataSetChanged()
            }
        }
    }

    inner class WorkerAdapter : RecyclerView.Adapter<WorkerAdapter.VH>() {
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
            val w = workers[position]
            holder.txt1.text = "${w.given_name} ${w.family_name}"
            holder.txt1.setTextColor(resources.getColor(R.color.on_surface, null))
            holder.txt2.text = getString(R.string.workers_badge_format, w.badge_number, w.position)
            holder.txt2.setTextColor(resources.getColor(R.color.on_surface_variant, null))
        }

        override fun getItemCount() = workers.size
    }
}
