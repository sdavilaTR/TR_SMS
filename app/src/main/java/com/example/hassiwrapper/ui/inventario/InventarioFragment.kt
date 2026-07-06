package com.example.hassiwrapper.ui.inventario

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ui.common.SwipeTabContainer
import com.example.hassiwrapper.ui.createspool.CreateSpoolFragment
import com.example.hassiwrapper.ui.packinglists.PackingListsFragment
import com.example.hassiwrapper.ui.vehicles.VehiclesFragment
import com.google.android.material.tabs.TabLayout

class InventarioFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_inventario, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val tabLayout = view.findViewById<TabLayout>(R.id.tabInventario)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Tabs are created lazily on first selection: instantiating all three at once
        // fired three parallel network loads and delayed the visible Spools tab.
        if (savedInstanceState == null) {
            val initialTab = arguments?.getInt("initialTab", 0) ?: 0
            if (initialTab != 0) tabLayout.getTabAt(initialTab)?.select() else showTab(0)
        }

        view.findViewById<SwipeTabContainer>(R.id.inventarioContainer).apply {
            onSwipeLeft = {
                val next = tabLayout.selectedTabPosition + 1
                if (next < tabLayout.tabCount) tabLayout.getTabAt(next)?.select()
            }
            onSwipeRight = {
                val prev = tabLayout.selectedTabPosition - 1
                if (prev >= 0) tabLayout.getTabAt(prev)?.select()
            }
        }
    }

    private fun showTab(position: Int) {
        val tag = when (position) {
            0 -> TAG_SPOOLS
            1 -> TAG_PLS
            else -> TAG_VEHICLES
        }
        val tx = childFragmentManager.beginTransaction()
        val target = childFragmentManager.findFragmentByTag(tag)
            ?: when (position) {
                0 -> CreateSpoolFragment()
                1 -> PackingListsFragment()
                else -> VehiclesFragment()
            }.also { tx.add(R.id.inventarioContainer, it, tag) }
        childFragmentManager.fragments.forEach { if (it !== target) tx.hide(it) }
        tx.show(target)
        tx.commit()
    }

    companion object {
        private const val TAG_SPOOLS   = "tab_spools"
        private const val TAG_PLS      = "tab_pls"
        private const val TAG_VEHICLES = "tab_vehicles"
    }
}
