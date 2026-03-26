package com.example.hassiwrapper.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.LocaleHelper
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsFragment : Fragment() {

    // Language codes must match the values-xx folder names
    private val languageCodes = arrayOf("es", "en", "fr")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val inputApiUrl = view.findViewById<EditText>(R.id.inputApiUrl)
        val btnSaveApiUrl = view.findViewById<MaterialButton>(R.id.btnSaveApiUrl)
        val inputDeviceName = view.findViewById<EditText>(R.id.inputDeviceName)
        val txtDeviceId = view.findViewById<TextView>(R.id.txtDeviceId)
        val btnSaveDevice = view.findViewById<MaterialButton>(R.id.btnSaveDevice)
        val btnLogout = view.findViewById<MaterialButton>(R.id.btnLogout)
        val spinnerLanguage = view.findViewById<Spinner>(R.id.spinnerLanguage)

        // Language spinner
        val languageNames = arrayOf(
            getString(R.string.language_es),
            getString(R.string.language_en),
            getString(R.string.language_fr)
        )
        spinnerLanguage.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, languageNames)
        val currentLang = LocaleHelper.getLanguage(requireContext())
        spinnerLanguage.setSelection(languageCodes.indexOf(currentLang).coerceAtLeast(0))

        spinnerLanguage.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val selected = languageCodes[pos]
                if (selected != LocaleHelper.getLanguage(requireContext())) {
                    LocaleHelper.setLanguage(requireContext(), selected)
                    requireActivity().recreate()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        // Load current values
        viewLifecycleOwner.lifecycleScope.launch {
            val apiUrl = ServiceLocator.configRepo.get("api_base_url") ?: ""
            inputApiUrl.setText(apiUrl)

            val deviceName = ServiceLocator.configRepo.get("device_name") ?: ""
            inputDeviceName.setText(deviceName)

            val deviceId = ServiceLocator.configRepo.get("device_id")
            txtDeviceId.text = if (!deviceId.isNullOrEmpty())
                getString(R.string.settings_device_id_format, deviceId)
            else
                getString(R.string.settings_device_id_none)
        }

        // Save API URL
        btnSaveApiUrl.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val url = inputApiUrl.text.toString().trim()
                ServiceLocator.configRepo.set("api_base_url", url)
                ServiceLocator.apiClient.resetResolvedBase()
                Toast.makeText(requireContext(), getString(R.string.settings_url_saved), Toast.LENGTH_SHORT).show()
            }
        }

        // Save device config
        btnSaveDevice.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val name = inputDeviceName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), getString(R.string.settings_name_required), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                ServiceLocator.configRepo.set("device_name", name)

                var deviceId = ServiceLocator.configRepo.get("device_id")
                if (deviceId.isNullOrEmpty() || deviceId == "unknown") {
                    deviceId = UUID.randomUUID().toString()
                    ServiceLocator.configRepo.set("device_id", deviceId)
                }
                txtDeviceId.text = getString(R.string.settings_device_id_format, deviceId)
                ServiceLocator.authRepo.refreshDeviceId()
                Toast.makeText(requireContext(), getString(R.string.settings_device_saved), Toast.LENGTH_SHORT).show()
            }
        }

        // Logout
        btnLogout.setOnClickListener {
            (requireActivity() as? MainActivity)?.logout()
        }
    }
}
