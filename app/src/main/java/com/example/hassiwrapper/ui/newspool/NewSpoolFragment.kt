package com.example.hassiwrapper.ui.newspool

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.data.db.entities.SmsSpoolEntity
import com.example.hassiwrapper.data.db.entities.SmsSpoolPropertyEntity
import com.example.hassiwrapper.network.dto.CreateSpoolRequest
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
    private lateinit var etUnit: TextInputEditText
    private lateinit var etLine: TextInputEditText
    private lateinit var etCode: TextInputEditText
    private lateinit var etTrain: TextInputEditText
    private lateinit var txtCodePreview: TextView
    private lateinit var txtSuffixPreview: TextView
    private lateinit var etDiameterInches: TextInputEditText
    private lateinit var etDiameter: TextInputEditText
    private lateinit var etBoreSizeId: TextInputEditText
    private lateinit var etWeightKg: TextInputEditText
    private lateinit var btnSave: MaterialButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_new_spool, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tilUnit          = view.findViewById(R.id.tilUnit)
        tilLine          = view.findViewById(R.id.tilLine)
        tilCode          = view.findViewById(R.id.tilCode)
        tilTrain         = view.findViewById(R.id.tilTrain)
        etUnit           = view.findViewById(R.id.etUnit)
        etLine           = view.findViewById(R.id.etLine)
        etCode           = view.findViewById(R.id.etCode)
        etTrain          = view.findViewById(R.id.etTrain)
        txtCodePreview   = view.findViewById(R.id.txtSpoolCodePreview)
        txtSuffixPreview = view.findViewById(R.id.txtSuffixPreview)
        etDiameterInches = view.findViewById(R.id.etDiameterInches)
        etDiameter       = view.findViewById(R.id.etDiameter)
        etBoreSizeId     = view.findViewById(R.id.etBoreSizeId)
        etWeightKg       = view.findViewById(R.id.etWeightKg)
        btnSave          = view.findViewById(R.id.btnSave)

        val codeWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        }
        etUnit.addTextChangedListener(codeWatcher)
        etLine.addTextChangedListener(codeWatcher)
        etCode.addTextChangedListener(codeWatcher)
        etTrain.addTextChangedListener(codeWatcher)

        txtSuffixPreview.setTextColor(requireContext().getColor(R.color.primary))
        updatePreview()
        btnSave.setOnClickListener { saveSpool() }
    }

    private fun buildSpoolCode(): String {
        val unit  = etUnit.text?.toString()?.trim().orEmpty()
        val line  = etLine.text?.toString()?.trim().orEmpty()
        val code  = etCode.text?.toString()?.trim().orEmpty()
        val train = etTrain.text?.toString()?.trim().orEmpty()
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
        val unit  = etUnit.text?.toString()?.trim().orEmpty()
        val line  = etLine.text?.toString()?.trim().orEmpty()
        val code  = etCode.text?.toString()?.trim().orEmpty()
        val train = etTrain.text?.toString()?.trim().orEmpty()

        tilUnit.error  = null
        tilLine.error  = null
        tilCode.error  = null
        tilTrain.error = null

        var valid = true
        if (unit.isEmpty())  { tilUnit.error  = getString(R.string.new_spool_error_field_required); valid = false }
        if (line.isEmpty())  { tilLine.error  = getString(R.string.new_spool_error_field_required); valid = false }
        if (code.isEmpty())  { tilCode.error  = getString(R.string.new_spool_error_field_required); valid = false }
        if (train.isEmpty()) { tilTrain.error = getString(R.string.new_spool_error_field_required); valid = false }
        if (!valid) return

        btnSave.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val projectId = ServiceLocator.configRepo.getInt("selected_project_id") ?: 6
                val spoolCode = "$unit-$line-$code-$train"
                val count     = ServiceLocator.smsSpoolDao.countByProjectAndCode(projectId, spoolCode)
                val suffix    = "SP%02d".format(count + 1)
                val spoolId   = (ServiceLocator.smsSpoolDao.getMaxId() ?: 0L) + 1L
                val now       = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

                val spool = SmsSpoolEntity(
                    spool_id   = spoolId,
                    project_id = projectId,
                    spool_code = spoolCode,
                    spool_suffix = suffix,
                    line_code  = line,
                    is_active  = true,
                    created_at = now,
                    created_by = ""
                )
                ServiceLocator.smsSpoolDao.insertAll(listOf(spool))

                // Intentar subir inmediatamente; si falla, SyncService reintenta
                try {
                    val project = ServiceLocator.projectDao.getById(projectId)
                    val projectCode = project?.project_code
                    if (!projectCode.isNullOrBlank()) {
                        val body = CreateSpoolRequest(
                            spoolCode   = spoolCode,
                            spoolSuffix = suffix,
                            lineCode    = line,
                            projectId   = projectId,
                            createdAt   = now,
                            createdBy   = ""
                        )
                        val response = ServiceLocator.apiClient.getService().createSpool(projectCode, body)
                        if (response.isSuccessful) {
                            ServiceLocator.smsSpoolDao.markSynced(listOf(spoolId))
                        }
                    }
                } catch (_: Exception) {
                    // Sin red — quedará synced=false, SyncService lo reintenta
                }

                val property = SmsSpoolPropertyEntity(
                    spool_id        = spoolId,
                    diameter_inches = etDiameterInches.text?.toString()?.trim()?.toDoubleOrNull(),
                    diameter        = etDiameter.text?.toString()?.trim()?.toDoubleOrNull(),
                    bore_size_id    = etBoreSizeId.text?.toString()?.trim()?.toIntOrNull(),
                    weight_kg       = etWeightKg.text?.toString()?.trim()?.toDoubleOrNull(),
                    updated_at      = now
                )
                ServiceLocator.smsSpoolPropertyDao.insertAll(listOf(property))

                Toast.makeText(requireContext(), getString(R.string.new_spool_success), Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            } catch (e: Exception) {
                btnSave.isEnabled = true
                Toast.makeText(requireContext(), getString(R.string.new_spool_error_save, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
