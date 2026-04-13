package com.example.hassiwrapper.ui.comingsoon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.hassiwrapper.R

class ObservationsGeneralFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_coming_soon, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<android.widget.TextView>(R.id.txtComingSoonTitle).text =
            getString(R.string.menu_observations_general)
        view.findViewById<android.widget.TextView>(R.id.txtComingSoonDesc).text =
            getString(R.string.coming_soon_observations)
        view.findViewById<android.widget.ImageView>(R.id.imgComingSoonIcon)
            .setImageResource(R.drawable.ic_observation)
    }
}
