package com.example.hassiwrapper.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.AtlasApp
import com.example.hassiwrapper.BuildConfig
import com.example.hassiwrapper.LocaleHelper
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.ProfileManager
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnLogout = view.findViewById<MaterialButton>(R.id.btnLogout)
        val btnLogin = view.findViewById<MaterialButton>(R.id.btnLogin)
        val btnCheckUpdates = view.findViewById<MaterialButton>(R.id.btnCheckUpdates)

        populateAppInfo(view)
        setupProfileSelector(view)
        setupLanguageSelector(view)

        // Show login or logout button based on auth state
        viewLifecycleOwner.lifecycleScope.launch {
            val authenticated = ServiceLocator.authRepo.isAuthenticated()
            btnLogin.visibility = if (authenticated) android.view.View.GONE else android.view.View.VISIBLE
            btnLogout.visibility = if (authenticated) android.view.View.VISIBLE else android.view.View.GONE
        }

        btnLogin.setOnClickListener {
            (requireActivity() as? MainActivity)?.launchLogin()
        }

        // Load device info (read-only)
        viewLifecycleOwner.lifecycleScope.launch {
            val name     = ServiceLocator.configRepo.get("device_name")     ?: "—"
            val id       = ServiceLocator.configRepo.get("device_id")       ?: "—"
            val location = ServiceLocator.configRepo.get("device_location") ?: "—"
            view.findViewById<TextView>(R.id.txtDeviceName).text     = name
            view.findViewById<TextView>(R.id.txtDeviceId).text       = id
            view.findViewById<TextView>(R.id.txtDeviceLocation).text = location
        }

        // Check for updates
        btnCheckUpdates.setOnClickListener {
            btnCheckUpdates.isEnabled = false
            btnCheckUpdates.text = getString(R.string.settings_check_updates_searching)
            (requireActivity() as? MainActivity)?.checkForUpdatesManually { alreadyUpToDate ->
                btnCheckUpdates.isEnabled = true
                btnCheckUpdates.text = getString(R.string.settings_check_updates)
                if (alreadyUpToDate) {
                    Toast.makeText(requireContext(), R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Logout
        btnLogout.setOnClickListener {
            (requireActivity() as? MainActivity)?.logout()
        }
    }

    // ── Language selector ──────────────────────────────────────────────

    private val languageFlags = mapOf("es" to "🇪🇸 Español", "en" to "🇬🇧 English", "fr" to "🇫🇷 Français")

    private fun setupLanguageSelector(view: View) {
        val txtCurrent = view.findViewById<TextView>(R.id.txtCurrentLanguage)
        val btnEs = view.findViewById<MaterialButton>(R.id.btnLangEs)
        val btnEn = view.findViewById<MaterialButton>(R.id.btnLangEn)
        val btnFr = view.findViewById<MaterialButton>(R.id.btnLangFr)

        val currentLang = LocaleHelper.getLanguage(requireContext())
        txtCurrent.text = languageFlags[currentLang] ?: languageFlags["es"]

        btnEs.setOnClickListener { changeLanguage("es") }
        btnEn.setOnClickListener { changeLanguage("en") }
        btnFr.setOnClickListener { changeLanguage("fr") }
    }

    private fun changeLanguage(code: String) {
        if (code != LocaleHelper.getLanguage(requireContext())) {
            LocaleHelper.setLanguage(requireContext(), code)
            requireActivity().recreate()
        }
    }

    // ── Profile selector ───────────────────────────────────────────────

    private fun setupProfileSelector(view: View) {
        val txtCurrent = view.findViewById<TextView>(R.id.txtCurrentProfile)
        val btnUser  = view.findViewById<MaterialButton>(R.id.btnProfileUser)
        val btnAdmin = view.findViewById<MaterialButton>(R.id.btnProfileAdmin)
        val btnDev   = view.findViewById<MaterialButton>(R.id.btnProfileDev)

        updateProfileLabel(txtCurrent)

        btnUser.setOnClickListener { switchProfile(ProfileManager.Profile.USER, txtCurrent) }
        btnAdmin.setOnClickListener { requestCodeAndSwitch(ProfileManager.Profile.ADMIN, txtCurrent) }
        btnDev.setOnClickListener   { requestCodeAndSwitch(ProfileManager.Profile.DEV, txtCurrent) }
    }

    private fun updateProfileLabel(txt: TextView) {
        val profile = ProfileManager.currentProfile()
        val name = when (profile) {
            ProfileManager.Profile.USER  -> getString(R.string.profile_user)
            ProfileManager.Profile.ADMIN -> getString(R.string.profile_admin)
            ProfileManager.Profile.DEV   -> getString(R.string.profile_dev)
        }
        val desc = when (profile) {
            ProfileManager.Profile.USER  -> getString(R.string.profile_user_desc)
            ProfileManager.Profile.ADMIN -> getString(R.string.profile_admin_desc)
            ProfileManager.Profile.DEV   -> getString(R.string.profile_dev_desc)
        }
        txt.text = "$name — $desc"
    }

    private fun requestCodeAndSwitch(target: ProfileManager.Profile, label: TextView) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.profile_access_code_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.profile_access_code_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = input.text.toString().trim()
                if (ProfileManager.validateAccessCode(code)) {
                    switchProfile(target, label)
                } else {
                    Toast.makeText(requireContext(), R.string.profile_access_code_error, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchProfile(target: ProfileManager.Profile, label: TextView) {
        val previous = ProfileManager.currentProfile()
        if (target == previous) return

        ProfileManager.setProfile(target)
        updateProfileLabel(label)

        // When switching to DEV, reset the local database
        if (target == ProfileManager.Profile.DEV) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    AtlasApp.instance.database.clearAllData()
                }
                ServiceLocator.apiClient.resetResolvedBase()
                Toast.makeText(requireContext(), R.string.profile_dev_db_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        // When switching away from DEV, also reset API cache
        if (previous == ProfileManager.Profile.DEV && target != ProfileManager.Profile.DEV) {
            ServiceLocator.apiClient.resetResolvedBase()
        }

        Toast.makeText(
            requireContext(),
            getString(R.string.profile_changed, target.name),
            Toast.LENGTH_SHORT
        ).show()

        // Refresh navigation menu visibility
        (requireActivity() as? MainActivity)?.refreshProfileMenu()
    }

    private fun populateAppInfo(view: View) {
        val tag = BuildConfig.BUILD_TAG

        view.findViewById<TextView>(R.id.txtInfoVersion).text = tag
        view.findViewById<TextView>(R.id.txtInfoBuildDate).text = parseBuildDate(tag)
        view.findViewById<TextView>(R.id.txtInfoRepo).text = "sdavilaTR/HassiSiteApp"
        view.findViewById<TextView>(R.id.txtInfoDeployment).text =
            if (tag == "dev") getString(R.string.settings_info_build_local)
            else getString(R.string.settings_info_deployment_value)
        view.findViewById<TextView>(R.id.txtInfoPackage).text = requireContext().packageName
    }

    /**
     * Converts a CI tag like "v2026-03-26-06-55" into a readable date "26/03/2026  06:55".
     * Returns the raw tag unchanged if it cannot be parsed.
     */
    private fun parseBuildDate(tag: String): String {
        if (tag == "dev") return getString(R.string.settings_info_build_local)
        return try {
            val parts = tag.trimStart('v', 'V').split("-")
            if (parts.size >= 5) "${parts[2]}/${parts[1]}/${parts[0]}  ${parts[3]}:${parts[4]}"
            else tag
        } catch (_: Exception) { tag }
    }
}
