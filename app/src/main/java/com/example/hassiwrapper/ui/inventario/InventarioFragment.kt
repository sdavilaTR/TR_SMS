package com.example.hassiwrapper.ui.inventario

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ui.createspool.CreateSpoolFragment
import com.example.hassiwrapper.ui.packinglists.PackingListsFragment
import com.example.hassiwrapper.ui.vehicles.VehiclesFragment
import com.google.android.material.tabs.TabLayout

class InventarioFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_inventario, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (savedInstanceState == null) {
            val spoolsFrag = CreateSpoolFragment()
            val plsFrag    = PackingListsFragment()
            val vehiclesFrag = VehiclesFragment()
            childFragmentManager.beginTransaction()
                .add(R.id.inventarioContainer, spoolsFrag, TAG_SPOOLS)
                .add(R.id.inventarioContainer, plsFrag, TAG_PLS).hide(plsFrag)
                .add(R.id.inventarioContainer, vehiclesFrag, TAG_VEHICLES).hide(vehiclesFrag)
                .commitNow()
        }

        val tabLayout = view.findViewById<TabLayout>(R.id.tabInventario)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        val initialTab = arguments?.getInt("initialTab", 0) ?: 0
        if (initialTab != 0) {
            tabLayout.getTabAt(initialTab)?.select()
            showTab(initialTab)
        }
    }

    private fun showTab(position: Int) {
        val target = when (position) {
            0 -> childFragmentManager.findFragmentByTag(TAG_SPOOLS)
            1 -> childFragmentManager.findFragmentByTag(TAG_PLS)
            else -> childFragmentManager.findFragmentByTag(TAG_VEHICLES)
        } ?: return
        childFragmentManager.beginTransaction().apply {
            childFragmentManager.fragments.forEach { hide(it) }
            show(target)
        }.commit()
    }

    companion object {
        private const val TAG_SPOOLS   = "tab_spools"
        private const val TAG_PLS      = "tab_pls"
        private const val TAG_VEHICLES = "tab_vehicles"
    }
}
