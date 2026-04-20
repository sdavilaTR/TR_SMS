package com.example.hassiwrapper.ui.settings

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.AtlasApp
import com.example.hassiwrapper.BuildConfig
import com.example.hassiwrapper.LocaleHelper
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.ProfileManager
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.update.UpdateInstaller
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
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
        val btnReinstallPrevious = view.findViewById<MaterialButton>(R.id.btnReinstallPrevious)

        populateAppInfo(view)
        setupProfileSelector(view)
        setupLanguageSelector(view)
        setupDeviceCode(view)

        refreshAuthButtons()

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

        // Rollback: reinstall previous version (only shown when a previous APK exists)
        btnReinstallPrevious.visibility =
            if (UpdateInstaller.hasPreviousApk(requireContext())) View.VISIBLE else View.GONE

        btnReinstallPrevious.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_reinstall_previous_title)
                .setMessage(R.string.settings_reinstall_previous_confirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    UpdateInstaller.reinstallPreviousVersion(requireContext())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Logout — clear session and refresh buttons immediately
        btnLogout.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                ServiceLocator.authRepo.logout()
                refreshAuthButtons()
                Toast.makeText(requireContext(), R.string.sync_auth_none, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAuthButtons()
        view?.findViewById<MaterialButton>(R.id.btnReinstallPrevious)?.visibility =
            if (UpdateInstaller.hasPreviousApk(requireContext())) View.VISIBLE else View.GONE
    }

    private fun refreshAuthButtons() {
        val v = view ?: return
        val btnLogin = v.findViewById<MaterialButton>(R.id.btnLogin)
        val btnLogout = v.findViewById<MaterialButton>(R.id.btnLogout)
        viewLifecycleOwner.lifecycleScope.launch {
            val authenticated = ServiceLocator.authRepo.isAuthenticated()
            btnLogin.visibility = if (authenticated) View.GONE else View.VISIBLE
            btnLogout.visibility = if (authenticated) View.VISIBLE else View.GONE
        }
    }

    // ── Device code (admin only) ────────────────────────────────────────

    private fun setupDeviceCode(view: View) {
        val card = view.findViewById<View>(R.id.cardDeviceCode)
        val input = view.findViewById<TextInputEditText>(R.id.inputDeviceCode)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveDeviceCode)

        // Only visible for non-USER profiles
        val isAdmin = ProfileManager.currentProfile() != ProfileManager.Profile.USER
        card.visibility = if (isAdmin) View.VISIBLE else View.GONE

        if (!isAdmin) return

        // Load stored device code
        viewLifecycleOwner.lifecycleScope.launch {
            val storedCode = ServiceLocator.configRepo.get("device_code")
            if (!storedCode.isNullOrBlank()) {
                input.setText(storedCode)
            }
        }

        btnSave.setOnClickListener {
            val code = input.text.toString().trim().uppercase()
            if (code.isEmpty()) {
                Toast.makeText(requireContext(), R.string.settings_device_code_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnSave.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                ServiceLocator.configRepo.set("device_code", code)

                // Auto-login immediately with the new device code
                val api = ServiceLocator.apiClient.getService()
                val success = ServiceLocator.authRepo.reLoginWithStoredCode(api)
                btnSave.isEnabled = true
                if (success) {
                    Toast.makeText(requireContext(), R.string.sync_auto_relogin_ok, Toast.LENGTH_SHORT).show()
                    refreshAuthButtons()
                } else {
                    Toast.makeText(requireContext(), R.string.settings_device_code_saved, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Button highlight helper ─────────────────────────────────────────

    private fun highlightSelected(selected: MaterialButton, vararg others: MaterialButton) {
        val primaryColor = ContextCompat.getColor(requireContext(), R.color.primary)
        val onPrimaryColor = ContextCompat.getColor(requireContext(), R.color.on_primary)
        val transparentColor = android.graphics.Color.TRANSPARENT

        selected.backgroundTintList = ColorStateList.valueOf(primaryColor)
        selected.setTextColor(onPrimaryColor)
        selected.strokeColor = ColorStateList.valueOf(primaryColor)

        for (btn in others) {
            btn.backgroundTintList = ColorStateList.valueOf(transparentColor)
            btn.setTextColor(primaryColor)
            btn.strokeColor = ColorStateList.valueOf(primaryColor)
        }
    }

    // ── Language selector ──────────────────────────────────────────────

    private val languageFlags = mapOf("es" to "🇪🇸 Español", "en" to "🇬🇧 English", "fr" to "🇫🇷 Français")

    private lateinit var langButtons: Map<String, MaterialButton>

    private fun setupLanguageSelector(view: View) {
        val txtCurrent = view.findViewById<TextView>(R.id.txtCurrentLanguage)
        val btnEs = view.findViewById<MaterialButton>(R.id.btnLangEs)
        val btnEn = view.findViewById<MaterialButton>(R.id.btnLangEn)
        val btnFr = view.findViewById<MaterialButton>(R.id.btnLangFr)

        langButtons = mapOf("es" to btnEs, "en" to btnEn, "fr" to btnFr)

        val currentLang = LocaleHelper.getLanguage(requireContext())
        txtCurrent.text = languageFlags[currentLang] ?: languageFlags["es"]
        highlightLanguageButton(currentLang)

        btnEs.setOnClickListener { changeLanguage("es") }
        btnEn.setOnClickListener { changeLanguage("en") }
        btnFr.setOnClickListener { changeLanguage("fr") }
    }

    private fun highlightLanguageButton(code: String) {
        val selected = langButtons[code] ?: return
        val others = langButtons.values.filter { it != selected }.toTypedArray()
        highlightSelected(selected, *others)
    }

    private fun changeLanguage(code: String) {
        if (code != LocaleHelper.getLanguage(requireContext())) {
            LocaleHelper.setLanguage(requireContext(), code)
            requireActivity().recreate()
        }
    }

    // ── Profile selector ───────────────────────────────────────────────

    private lateinit var profileButtons: Map<ProfileManager.Profile, MaterialButton>

    private fun setupProfileSelector(view: View) {
        val txtCurrent = view.findViewById<TextView>(R.id.txtCurrentProfile)
        val btnUser  = view.findViewById<MaterialButton>(R.id.btnProfileUser)
        val btnHse   = view.findViewById<MaterialButton>(R.id.btnProfileHse)
        val btnAdmin = view.findViewById<MaterialButton>(R.id.btnProfileAdmin)
        val btnPre   = view.findViewById<MaterialButton>(R.id.btnProfilePre)
        val btnDev   = view.findViewById<MaterialButton>(R.id.btnProfileDev)

        profileButtons = mapOf(
            ProfileManager.Profile.USER  to btnUser,
            ProfileManager.Profile.HSE   to btnHse,
            ProfileManager.Profile.ADMIN to btnAdmin,
            ProfileManager.Profile.PRE   to btnPre,
            ProfileManager.Profile.DEV   to btnDev
        )

        updateProfileLabel(txtCurrent)
        highlightProfileButton(ProfileManager.currentProfile())

        btnUser.setOnClickListener  { switchProfile(ProfileManager.Profile.USER, txtCurrent) }
        btnHse.setOnClickListener   { requestCodeAndSwitch(ProfileManager.Profile.HSE, txtCurrent) }
        btnAdmin.setOnClickListener { requestCodeAndSwitch(ProfileManager.Profile.ADMIN, txtCurrent) }
        btnPre.setOnClickListener   { requestCodeAndSwitch(ProfileManager.Profile.PRE, txtCurrent) }
        btnDev.setOnClickListener   { requestCodeAndSwitch(ProfileManager.Profile.DEV, txtCurrent) }
    }

    private fun highlightProfileButton(profile: ProfileManager.Profile) {
        val selected = profileButtons[profile] ?: return
        val others = profileButtons.values.filter { it != selected }.toTypedArray()
        highlightSelected(selected, *others)
    }

    private fun updateProfileLabel(txt: TextView) {
        val profile = ProfileManager.currentProfile()
        val name = when (profile) {
            ProfileManager.Profile.USER  -> getString(R.string.profile_user)
            ProfileManager.Profile.HSE   -> getString(R.string.profile_hse)
            ProfileManager.Profile.ADMIN -> getString(R.string.profile_admin)
            ProfileManager.Profile.PRE   -> getString(R.string.profile_pre)
            ProfileManager.Profile.DEV   -> getString(R.string.profile_dev)
        }
        val desc = when (profile) {
            ProfileManager.Profile.USER  -> getString(R.string.profile_user_desc)
            ProfileManager.Profile.HSE   -> getString(R.string.profile_hse_desc)
            ProfileManager.Profile.ADMIN -> getString(R.string.profile_admin_desc)
            ProfileManager.Profile.PRE   -> getString(R.string.profile_pre_desc)
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

        val changingEnvironment =
            ProfileManager.apiUrlFor(target) != ProfileManager.apiUrlFor(previous)

        ProfileManager.setProfile(target)
        updateProfileLabel(label)
        highlightProfileButton(target)

        if (changingEnvironment) {
            // Different API environment — reset API cache, auth, and local DB
            ServiceLocator.apiClient.resetResolvedBase()
            ServiceLocator.authRepo.clearCaches()

            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    AtlasApp.instance.database.clearAllData()
                }
                // Also clear the auth token from config (clearAllData wiped it from DB,
                // but we need to ensure the in-memory cache is also gone)
                ServiceLocator.authRepo.logout()
                refreshAuthButtons()
                Toast.makeText(requireContext(), R.string.profile_dev_db_cleared, Toast.LENGTH_SHORT).show()
            }
        }

        Toast.makeText(
            requireContext(),
            getString(R.string.profile_changed, target.name),
            Toast.LENGTH_SHORT
        ).show()

        // Refresh navigation menu visibility and device code card
        (requireActivity() as? MainActivity)?.refreshProfileMenu()
        view?.let { setupDeviceCode(it) }
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
