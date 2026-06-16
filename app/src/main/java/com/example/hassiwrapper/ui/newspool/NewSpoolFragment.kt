package com.example.hassiwrapper.ui.newspool

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsBoreSizeEntity
import com.example.hassiwrapper.data.db.entities.SmsIncompleteStatusEntity
import com.example.hassiwrapper.data.db.entities.SmsIsoTypeEntity
import com.example.hassiwrapper.data.db.entities.SmsPositionEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolPropertyEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolStatusEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolStatusFlagsEntity
import com.example.hassiwrapper.data.db.entities.SmsUnitEntity
import com.example.hassiwrapper.network.dto.CreateSpoolPropertyRequest
import com.example.hassiwrapper.network.dto.CreateSpoolRequest
import com.example.hassiwrapper.network.dto.CreateSpoolStatusFlagsRequest
import com.example.hassiwrapper.network.dto.SpoolCreatePayload
import com.example.hassiwrapper.services.OutboxService
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class NewSpoolFragment : Fragment() {

    private lateinit var tilUnit: TextInputLayout
    private lateinit var tilLine: TextInputLayout
    private lateinit var tilCode: TextInputLayout
    private lateinit var tilTrain: TextInputLayout
    private lateinit var tilIsoType: TextInputLayout
    private lateinit var tilStatus: TextInputLayout
    private lateinit var tilPosition: TextInputLayout
    private lateinit var tilIncompleteStatus: TextInputLayout
    private lateinit var tilBoreSize: TextInputLayout

    private lateinit var actvUnit: AutoCompleteTextView
    private lateinit var actvLine: AutoCompleteTextView
    private lateinit var actvCode: AutoCompleteTextView
    private lateinit var actvTrain: AutoCompleteTextView
    private lateinit var actvIsoType: AutoCompleteTextView
    private lateinit var actvStatus: AutoCompleteTextView
    private lateinit var actvPosition: AutoCompleteTextView
    private lateinit var actvIncompleteStatus: AutoCompleteTextView
    private lateinit var actvBoreSize: AutoCompleteTextView

    private lateinit var txtCodePreview: TextView
    private lateinit var txtSuffixPreview: TextView
    private lateinit var etDiameterInches: TextInputEditText
    private lateinit var etDiameter: TextInputEditText
    private lateinit var etWeightKg: TextInputEditText
    private lateinit var btnSave: MaterialButton

    private var selectedUnitId: Int? = null
    private var selectedUnitCode: String = ""
    private var selectedIsoTypeId: Int? = null
    private var selectedStatusId: Int? = null
    private var selectedPositionId: Int? = null
    private var selectedIncompleteStatusId: Int? = null
    private var selectedBoreSizeId: Int? = null
    private var prefillSpoolCode: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_new_spool, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as com.example.hassiwrapper.ui.common.SwipeBackScrollView).onSwipeBack = { findNavController().navigateUp() }
        prefillSpoolCode = arguments?.getString("prefillSpoolCode")
        tilUnit             = view.findViewById(R.id.tilUnit)
        tilLine             = view.findViewById(R.id.tilLine)
        tilCode             = view.findViewById(R.id.tilCode)
        tilTrain            = view.findViewById(R.id.tilTrain)
        tilIsoType          = view.findViewById(R.id.tilIsoType)
        tilStatus           = view.findViewById(R.id.tilStatus)
        tilPosition         = view.findViewById(R.id.tilPosition)
        tilIncompleteStatus = view.findViewById(R.id.tilIncompleteStatus)
        tilBoreSize         = view.findViewById(R.id.tilBoreSize)

        actvUnit             = view.findViewById(R.id.actvUnit)
        actvLine             = view.findViewById(R.id.actvLine)
        actvCode             = view.findViewById(R.id.actvCode)
        actvTrain            = view.findViewById(R.id.actvTrain)
        actvIsoType          = view.findViewById(R.id.actvIsoType)
        actvStatus           = view.findViewById(R.id.actvStatus)
        actvPosition         = view.findViewById(R.id.actvPosition)
        actvIncompleteStatus = view.findViewById(R.id.actvIncompleteStatus)
        actvBoreSize         = view.findViewById(R.id.actvBoreSize)

        txtCodePreview   = view.findViewById(R.id.txtSpoolCodePreview)
        txtSuffixPreview = view.findViewById(R.id.txtSuffixPreview)
        etDiameterInches = view.findViewById(R.id.etDiameterInches)
        etDiameter       = view.findViewById(R.id.etDiameter)
        etWeightKg       = view.findViewById(R.id.etWeightKg)
        btnSave          = view.findViewById(R.id.btnSave)

        val codeWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        }
        actvLine.addTextChangedListener(codeWatcher)
        actvCode.addTextChangedListener(codeWatcher)
        actvTrain.addTextChangedListener(codeWatcher)

        txtSuffixPreview.setTextColor(requireContext().getColor(R.color.primary))
        updatePreview()
        btnSave.setOnClickListener { saveSpool() }

        loadDropdownData()
    }

    private fun loadDropdownData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val units             = ServiceLocator.smsUnitDao.getAll()
            val isoTypes          = ServiceLocator.smsIsoTypeDao.getAll()
            val statuses          = ServiceLocator.smsSpoolStatusDao.getAll()
            val positions         = ServiceLocator.smsPositionDao.getAll()
            val incompleteStatuses = ServiceLocator.smsIncompleteStatusDao.getAll()
            val boreSizes         = ServiceLocator.smsBoreSizeDao.getAll()

            setupUnitDropdown(units)
            setupTextDropdown(actvLine, distinctSpoolCodeSegments(projectId, 1))
            setupTextDropdown(actvCode, distinctSpoolCodeSegments(projectId, 2))
            setupTextDropdown(actvTrain, ServiceLocator.smsSpoolDao.getDistinctTrains(projectId))
            applySpoolCodePrefill(units)
            setupDropdown(actvIsoType, isoTypes, { it.name.ifBlank { it.code } }) { selectedIsoTypeId = it?.iso_type_id }
            setupDropdown(actvStatus, statuses, { it.name.ifBlank { it.code } }) { selectedStatusId = it?.status_id }
            setupDropdown(actvPosition, positions, { it.name.ifBlank { it.code } }) { selectedPositionId = it?.position_id }
            setupDropdown(actvIncompleteStatus, incompleteStatuses, { it.name.ifBlank { it.code } }) { selectedIncompleteStatusId = it?.incomplete_status_id }
            setupDropdown(actvBoreSize, boreSizes, { it.name.ifBlank { it.code } }) { selectedBoreSizeId = it?.bore_size_id }
        }
    }

    private fun setupUnitDropdown(units: List<SmsUnitEntity>) {
        val labels = units.map { it.name.ifBlank { it.code } }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        actvUnit.setAdapter(adapter)
        actvUnit.setOnItemClickListener { _, _, position, _ ->
            val unit = units[position]
            selectedUnitId   = unit.unit_id
            selectedUnitCode = unit.code
            tilUnit.error    = null
            updatePreview()
        }
    }

    private fun setupTextDropdown(actv: AutoCompleteTextView, values: List<String>) {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, values)
        actv.setAdapter(adapter)
    }

    /** Segmento `index` (0=unidad, 1=línea, 2=código, 3=tren) de los spool_code existentes del proyecto. */
    private suspend fun distinctSpoolCodeSegments(projectId: Int, index: Int): List<String> {
        return ServiceLocator.smsSpoolDao.getDistinctSpoolCodes(projectId)
            .mapNotNull { it.split("-").getOrNull(index) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun <T> setupDropdown(
        actv: AutoCompleteTextView,
        items: List<T>,
        displayFn: (T) -> String,
        onSelect: (T?) -> Unit
    ) {
        val labels = items.map(displayFn)
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        actv.setAdapter(adapter)
        actv.setOnItemClickListener { _, _, position, _ -> onSelect(items[position]) }
    }

    private fun buildSpoolCode(): String {
        val unit  = selectedUnitCode
        val line  = actvLine.text?.toString()?.trim().orEmpty()
        val code  = actvCode.text?.toString()?.trim().orEmpty()
        val train = actvTrain.text?.toString()?.trim().orEmpty()
        return listOf(unit, line, code, train).filter { it.isNotEmpty() }.joinToString("-")
    }

    private fun updatePreview() {
        val spoolCode = buildSpoolCode()
        txtCodePreview.text = spoolCode.ifEmpty { "–" }
        if (spoolCode.isEmpty()) {
            txtSuffixPreview.text = ""
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
            val count     = ServiceLocator.smsSpoolDao.countByProjectAndCode(projectId, spoolCode)
            txtSuffixPreview.text = "SP%02d".format(count + 1)
        }
    }

    private fun saveSpool() {
        val line  = actvLine.text?.toString()?.trim().orEmpty()
        val code  = actvCode.text?.toString()?.trim().orEmpty()
        val train = actvTrain.text?.toString()?.trim().orEmpty()

        tilUnit.error  = null
        tilLine.error  = null
        tilCode.error  = null
        tilTrain.error = null

        var valid = true
        if (selectedUnitId == null) { tilUnit.error  = getString(R.string.new_spool_error_field_required); valid = false }
        if (line.isEmpty())  { tilLine.error  = getString(R.string.new_spool_error_field_required); valid = false }
        if (code.isEmpty())  { tilCode.error  = getString(R.string.new_spool_error_field_required); valid = false }
        if (train.isEmpty()) { tilTrain.error = getString(R.string.new_spool_error_field_required); valid = false }
        if (!valid) return

        btnSave.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val spoolCode = "$selectedUnitCode-$line-$code-$train"
                val count     = ServiceLocator.smsSpoolDao.countByProjectAndCode(projectId, spoolCode)
                val suffix    = "SP%02d".format(count + 1)
                val spoolId   = ServiceLocator.outboxService.nextTempSpoolId()
                val now       = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

                val spool = SmsSpoolEntity(
                    spool_id     = spoolId,
                    project_id   = projectId,
                    spool_code   = spoolCode,
                    spool_suffix = suffix,
                    line_code    = line,
                    unit_id      = selectedUnitId,
                    iso_type_id  = selectedIsoTypeId,
                    train        = train.takeIf { it.isNotBlank() },
                    is_active    = true,
                    created_at   = now,
                    created_by   = "",
                    synced       = false
                )
                ServiceLocator.smsSpoolDao.insertAll(listOf(spool))

                val diamIn = etDiameterInches.text?.toString()?.trim()?.toDoubleOrNull()
                val diam   = etDiameter.text?.toString()?.trim()?.toDoubleOrNull()
                val wt     = etWeightKg.text?.toString()?.trim()?.toDoubleOrNull()

                val property = SmsSpoolPropertyEntity(
                    spool_id        = spoolId,
                    diameter_inches = diamIn,
                    diameter        = diam,
                    bore_size_id    = selectedBoreSizeId,
                    weight_kg       = wt,
                    updated_at      = now
                )
                ServiceLocator.smsSpoolPropertyDao.insertAll(listOf(property))

                val statusFlags = SmsSpoolStatusFlagsEntity(
                    spool_id             = spoolId,
                    status_id            = selectedStatusId,
                    incomplete_status_id = selectedIncompleteStatusId,
                    position_id          = selectedPositionId,
                    updated_at           = now
                )
                ServiceLocator.smsSpoolStatusFlagsDao.insertAll(listOf(statusFlags))

                val hasProperty = diamIn != null || diam != null || selectedBoreSizeId != null || wt != null
                val hasFlags    = selectedStatusId != null || selectedIncompleteStatusId != null || selectedPositionId != null
                ServiceLocator.outboxService.enqueue(
                    OutboxService.Entity.SPOOL, OutboxService.Op.CREATE, spoolId, projectId,
                    payload = SpoolCreatePayload(
                        create = CreateSpoolRequest(
                            spoolCode   = spoolCode,
                            spoolSuffix = suffix,
                            lineCode    = line,
                            projectId   = projectId,
                            createdAt   = now,
                            createdBy   = "",
                            unitId      = selectedUnitId,
                            isoTypeId   = selectedIsoTypeId,
                            train       = train.takeIf { it.isNotBlank() }
                        ),
                        property = if (hasProperty)
                            CreateSpoolPropertyRequest(spoolId, diamIn, diam, selectedBoreSizeId, wt) else null,
                        flags = if (hasFlags)
                            CreateSpoolStatusFlagsRequest(spoolId, selectedStatusId, selectedIncompleteStatusId, selectedPositionId) else null
                    )
                )

                ServiceLocator.auditLogService.log(
                    com.example.hassiwrapper.services.AuditLogService.SPOOL_CREADO,
                    com.example.hassiwrapper.services.AuditLogService.ENTITY_SPOOL,
                    spoolId, spool.displayCode, projectId = projectId
                )
                (requireActivity() as? MainActivity)?.playSuccess()
                Toast.makeText(requireContext(), getString(R.string.new_spool_success), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                btnSave.isEnabled = true
                (requireActivity() as? MainActivity)?.playError()
                Toast.makeText(requireContext(), getString(R.string.new_spool_error_save, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applySpoolCodePrefill(units: List<SmsUnitEntity>) {
        val raw = prefillSpoolCode ?: return
        val parts = raw.split("-")
        val matchedUnit = units.find { it.code.equals(parts[0], ignoreCase = true) }
            ?: units.find { it.name.equals(parts[0], ignoreCase = true) }
        if (matchedUnit != null) {
            selectedUnitId   = matchedUnit.unit_id
            selectedUnitCode = matchedUnit.code
            tilUnit.error    = null
            actvUnit.setText(matchedUnit.name.ifBlank { matchedUnit.code }, false)
            if (parts.size >= 2) actvLine.setText(parts[1])
            if (parts.size >= 3) actvCode.setText(parts[2])
            if (parts.size >= 4) actvTrain.setText(parts.drop(3).joinToString("-"))
        } else if (parts.size == 1) {
            actvCode.setText(raw)
        } else {
            // Unit code unrecognised: still show it so the user can pick the right unit
            // from the dropdown, and map the remaining segments exactly as the matched branch.
            actvUnit.setText(parts[0], false)
            actvLine.setText(parts[1])
            if (parts.size >= 3) actvCode.setText(parts[2])
            if (parts.size >= 4) actvTrain.setText(parts.drop(3).joinToString("-"))
        }
        updatePreview()
    }
}
