package com.example.hassiwrapper.ui.transfers

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.R

class TransfersFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_transfers, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.cardSend).setOnClickListener {
            findNavController().navigate(R.id.action_transfersFragment_to_sendPackingListFragment)
        }
        view.findViewById<View>(R.id.cardReceive).setOnClickListener {
            findNavController().navigate(R.id.action_transfersFragment_to_receivePackingListFragment)
        }
    }
}
