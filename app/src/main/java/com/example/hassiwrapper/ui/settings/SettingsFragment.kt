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
import androidx.appcompat.widget.SwitchCompat
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
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

        setupTabs(view)
        populateAppInfo(view)
        setupApiEnvSelector(view)
        setupUserRoleSelector(view)
        setupLanguageSelector(view)
        setupDeviceCode(view)
        setupLocationConfig(view)
        setupAssignedOperator(view)
        setupDebugLocationButton(view)
        setupKioskMode(view)

        refreshAuthButtons()

        btnLogin.setOnClickListener {
            (requireActivity() as? MainActivity)?.launchLogin()
        }

        // Load device info (read-only) — auto-fill name/ID from the device itself if missing
        viewLifecycleOwner.lifecycleScope.launch {
            var id = ServiceLocator.configRepo.get("device_id")
            if (id.isNullOrBlank()) {
                id = android.provider.Settings.Secure.getString(
                    requireContext().contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                )
                ServiceLocator.configRepo.set("device_id", id)
                ServiceLocator.authRepo.refreshDeviceId()
            }

            var name = ServiceLocator.configRepo.get("device_name")
            if (name.isNullOrBlank()) {
                name = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
                ServiceLocator.configRepo.set("device_name", name)
            }

            val location = ServiceLocator.configRepo.get("device_location") ?: "—"
            view.findViewById<TextView>(R.id.txtDeviceName).text     = name
            view.findViewById<TextView>(R.id.txtDeviceId).text       = id ?: "—"
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

    // ── Device code ────────────────────────────────────────────────────

    private fun setupDeviceCode(view: View) {
        val card = view.findViewById<View>(R.id.cardDeviceCode)
        val input = view.findViewById<TextInputEditText>(R.id.inputDeviceCode)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveDeviceCode)

        card.visibility = View.VISIBLE

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

    private val languageFlags = mapOf("es" to "Español", "en" to "English", "fr" to "Français", "zh" to "中文")

    private lateinit var langButtons: Map<String, MaterialButton>

    private fun setupLanguageSelector(view: View) {
        val txtCurrent = view.findViewById<TextView>(R.id.txtCurrentLanguage)
        val btnEs = view.findViewById<MaterialButton>(R.id.btnLangEs)
        val btnEn = view.findViewById<MaterialButton>(R.id.btnLangEn)
        val btnFr = view.findViewById<MaterialButton>(R.id.btnLangFr)
        val btnZh = view.findViewById<MaterialButton>(R.id.btnLangZh)

        langButtons = mapOf("es" to btnEs, "en" to btnEn, "fr" to btnFr, "zh" to btnZh)

        val currentLang = LocaleHelper.getLanguage(requireContext())
        txtCurrent.text = languageFlags[currentLang] ?: languageFlags["es"]
        highlightLanguageButton(currentLang)

        btnEs.setOnClickListener { changeLanguage("es") }
        btnEn.setOnClickListener { changeLanguage("en") }
        btnFr.setOnClickListener { changeLanguage("fr") }
        btnZh.setOnClickListener { changeLanguage("zh") }
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

    // ── API environment selector ───────────────────────────────────────

    private lateinit var envButtons: Map<ProfileManager.ApiEnvironment, MaterialButton>

    private fun setupApiEnvSelector(view: View) {
        val txtCurrent = view.findViewById<TextView>(R.id.txtCurrentApiEnv)
        val btnPro = view.findViewById<MaterialButton>(R.id.btnEnvPro)
        val btnPre = view.findViewById<MaterialButton>(R.id.btnEnvPre)
        val btnDev = view.findViewById<MaterialButton>(R.id.btnEnvDev)

        envButtons = mapOf(
            ProfileManager.ApiEnvironment.PRO to btnPro,
            ProfileManager.ApiEnvironment.PRE to btnPre,
            ProfileManager.ApiEnvironment.DEV to btnDev
        )

        updateEnvLabel(txtCurrent)
        highlightEnvButton(ProfileManager.currentApiEnvironment())

        // PRO is the public environment — no code required. PRE/DEV require access code.
        btnPro.setOnClickListener { switchApiEnv(ProfileManager.ApiEnvironment.PRO, txtCurrent) }
        btnPre.setOnClickListener { requestCodeThen { switchApiEnv(ProfileManager.ApiEnvironment.PRE, txtCurrent) } }
        btnDev.setOnClickListener { requestCodeThen { switchApiEnv(ProfileManager.ApiEnvironment.DEV, txtCurrent) } }
    }

    private fun highlightEnvButton(env: ProfileManager.ApiEnvironment) {
        val selected = envButtons[env] ?: return
        val others = envButtons.values.filter { it != selected }.toTypedArray()
        highlightSelected(selected, *others)
    }

    private fun updateEnvLabel(txt: TextView) {
        txt.text = when (ProfileManager.currentApiEnvironment()) {
            ProfileManager.ApiEnvironment.PRO -> getString(R.string.env_pro_desc)
            ProfileManager.ApiEnvironment.PRE -> getString(R.string.env_pre_desc)
            ProfileManager.ApiEnvironment.DEV -> getString(R.string.env_dev_desc)
        }
    }

    private fun switchApiEnv(target: ProfileManager.ApiEnvironment, label: TextView) {
        val previous = ProfileManager.currentApiEnvironment()
        if (target == previous) return

        ProfileManager.setApiEnvironment(target)
        updateEnvLabel(label)
        highlightEnvButton(target)

        // Different API environment — reset API cache, auth, and local DB.
        ServiceLocator.apiClient.resetResolvedBase()
        ServiceLocator.authRepo.clearCaches()

        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AtlasApp.instance.database.clearAllData()
            }
            ServiceLocator.authRepo.logout()
            refreshAuthButtons()
            Toast.makeText(requireContext(), R.string.profile_dev_db_cleared, Toast.LENGTH_SHORT).show()
        }

        Toast.makeText(requireContext(), getString(R.string.env_changed, target.name), Toast.LENGTH_SHORT).show()
    }

    // ── User role selector ──────────────────────────────────────────────

    private lateinit var roleButtons: Map<ProfileManager.UserRole, MaterialButton>

    private fun setupUserRoleSelector(view: View) {
        val txtCurrent = view.findViewById<TextView>(R.id.txtCurrentRole)
        val btnGuest = view.findViewById<MaterialButton>(R.id.btnRoleGuest)
        val btnAdmin = view.findViewById<MaterialButton>(R.id.btnRoleAdmin)
        val btnDev   = view.findViewById<MaterialButton>(R.id.btnRoleDev)

        roleButtons = mapOf(
            ProfileManager.UserRole.GUEST to btnGuest,
            ProfileManager.UserRole.ADMIN to btnAdmin,
            ProfileManager.UserRole.DEV   to btnDev
        )

        updateRoleLabel(txtCurrent)
        highlightRoleButton(ProfileManager.currentUserRole())

        btnGuest.setOnClickListener { switchUserRole(ProfileManager.UserRole.GUEST, txtCurrent) }
        btnAdmin.setOnClickListener { requestCodeThen { switchUserRole(ProfileManager.UserRole.ADMIN, txtCurrent) } }
        btnDev.setOnClickListener   { requestCodeThen { switchUserRole(ProfileManager.UserRole.DEV, txtCurrent) } }
    }

    private fun highlightRoleButton(role: ProfileManager.UserRole) {
        val selected = roleButtons[role] ?: return
        val others = roleButtons.values.filter { it != selected }.toTypedArray()
        highlightSelected(selected, *others)
    }

    private fun updateRoleLabel(txt: TextView) {
        txt.text = when (ProfileManager.currentUserRole()) {
            ProfileManager.UserRole.GUEST -> getString(R.string.role_guest_desc)
            ProfileManager.UserRole.ADMIN -> getString(R.string.role_admin_desc)
            ProfileManager.UserRole.DEV   -> getString(R.string.role_dev_desc)
        }
    }

    private fun switchUserRole(target: ProfileManager.UserRole, label: TextView) {
        if (target == ProfileManager.currentUserRole()) return

        ProfileManager.setUserRole(target)
        updateRoleLabel(label)
        highlightRoleButton(target)

        Toast.makeText(requireContext(), getString(R.string.role_changed, target.name), Toast.LENGTH_SHORT).show()

        // Refresh navigation menu visibility and device code card
        (requireActivity() as? MainActivity)?.refreshProfileMenu()
        view?.let { setupDeviceCode(it) }
    }

    // ── Access code dialog (shared) ─────────────────────────────────────

    private fun requestCodeThen(onValid: () -> Unit) {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.profile_access_code_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.profile_access_code_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (ProfileManager.validateAccessCode(code)) {
                    onValid()
                } else {
                    Toast.makeText(requireContext(), R.string.profile_access_code_error, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Location config (Laydown sections / Site units) ────────────────

    private fun setupLocationConfig(view: View) {
        val inputSections = view.findViewById<TextInputEditText>(R.id.inputLaydownSections)
        val inputUnits    = view.findViewById<TextInputEditText>(R.id.inputSiteUnits)
        val btnSave       = view.findViewById<MaterialButton>(R.id.btnSaveLocationConfig)

        viewLifecycleOwner.lifecycleScope.launch {
            val sections = ServiceLocator.configRepo.get("laydown_sections") ?: "1A,2A,1B,2B,1C,2C,1D,2D"
            val units    = ServiceLocator.configRepo.get("site_units") ?: "1,2,3,4"
            inputSections.setText(sections)
            inputUnits.setText(units)
        }

        btnSave.setOnClickListener {
            val sections = inputSections.text.toString().trim()
            val units    = inputUnits.text.toString().trim()
            viewLifecycleOwner.lifecycleScope.launch {
                ServiceLocator.configRepo.set("laydown_sections", sections)
                ServiceLocator.configRepo.set("site_units", units)
                Toast.makeText(requireContext(), R.string.settings_location_config_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupAssignedOperator(view: View) {
        val input   = view.findViewById<TextInputEditText>(R.id.inputAssignedOperator)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveAssignedOperator)

        viewLifecycleOwner.lifecycleScope.launch {
            input.setText(ServiceLocator.configRepo.get("assigned_operator_name") ?: "")
        }

        btnSave.setOnClickListener {
            val name = input.text.toString().trim()
            viewLifecycleOwner.lifecycleScope.launch {
                ServiceLocator.configRepo.set("assigned_operator_name", name)
                Toast.makeText(requireContext(), R.string.settings_assigned_operator_saved, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // TODO: BORRAR - Solo para debug: permite cambiar la ubicación del terminal manualmente
    private fun setupDebugLocationButton(view: View) {
        val btn = view.findViewById<MaterialButton>(R.id.btnDebugChangeLocation)
        val txtLocation = view.findViewById<TextView>(R.id.txtDeviceLocation)
        btn.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val positions = ServiceLocator.smsPositionDao.getAll()
                val options = if (positions.isNotEmpty()) {
                    positions.map { it.code }.toTypedArray()
                } else {
                    arrayOf("WORKSHOP", "LAYDOWN", "SITE")
                }
                AlertDialog.Builder(requireContext())
                    .setTitle(getString(R.string.settings_debug_location_btn))
                    .setItems(options) { _, which ->
                        val selected = options[which]
                        viewLifecycleOwner.lifecycleScope.launch {
                            ServiceLocator.configRepo.set("device_location", selected)
                            txtLocation.text = selected
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.settings_debug_location_changed, selected),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .show()
            }
        }
    }

    // ── Kiosk mode ─────────────────────────────────────────────────────

    private fun setupKioskMode(view: View) {
        val switch = view.findViewById<SwitchCompat>(R.id.switchKioskMode)
        val btnClose = view.findViewById<View>(R.id.btnCloseApp)

        viewLifecycleOwner.lifecycleScope.launch {
            val enabled = ServiceLocator.configRepo.get("kiosk_mode") == "true"
            switch.isChecked = enabled
            btnClose.visibility = if (enabled) View.GONE else View.VISIBLE

            // Attach listener only after initial state is set, so restoring the
            // saved value above doesn't itself fire the listener (and re-show the toast).
            switch.setOnCheckedChangeListener { _, isChecked ->
                viewLifecycleOwner.lifecycleScope.launch {
                    ServiceLocator.configRepo.set("kiosk_mode", isChecked.toString())
                    (requireActivity() as? MainActivity)?.setKioskMode(isChecked)
                    val msg = if (isChecked) R.string.settings_kiosk_enabled else R.string.settings_kiosk_disabled
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    btnClose.visibility = if (isChecked) View.GONE else View.VISIBLE
                }
            }
        }

        btnClose.setOnClickListener {
            requireActivity().finishAffinity()
            System.exit(0)
        }
    }

    // ── Tab switching ───────────────────────────────────────────────────

    private fun setupTabs(view: View) {
        val tabLayout = view.findViewById<TabLayout>(R.id.settingsTabLayout)
        val scrollBasic = view.findViewById<View>(R.id.scrollBasic)
        val scrollDev = view.findViewById<View>(R.id.scrollDev)

        tabLayout.addTab(tabLayout.newTab().setText(R.string.settings_tab_basic))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.settings_tab_dev))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                scrollBasic.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                scrollDev.visibility   = if (tab.position == 1) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
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
