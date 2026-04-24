package com.example.hassiwrapper.ui.workers

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import kotlinx.coroutines.launch

class WorkersFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_workers, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keep pulling the existing personDao data so the sync pipeline
        // stays wired up while the real spools schema is not yet available.
        // Once the ATLAS backend exposes spool data per section (workshop /
        // laydown / site), populate the three cards from dedicated DAOs.
        viewLifecycleOwner.lifecycleScope.launch {
            val received = ServiceLocator.personDao.getAll().size
            Log.d(TAG, "Received $received records from personDao (placeholder pipe)")
        }

        // Counters intentionally left blank — no spools source yet.
        view.findViewById<TextView>(R.id.txtWorkshopCount).text = ""
        view.findViewById<TextView>(R.id.txtLaydownCount).text = ""
        view.findViewById<TextView>(R.id.txtSiteCount).text = ""
    }

    companion object {
        private const val TAG = "WorkersFragment"
    }
}
