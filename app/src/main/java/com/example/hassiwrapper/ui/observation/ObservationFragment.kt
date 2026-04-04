package com.example.hassiwrapper.ui.observation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.example.hassiwrapper.services.ObservationService
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

class ObservationFragment : Fragment() {

    companion object {
        const val ARG_UNIQUE_ID = "unique_id_value"
        const val ARG_NAME = "observed_name"
        const val ARG_BADGE = "observed_badge"
        const val ARG_DEPARTMENT = "observed_department"
        const val ARG_POSITION = "observed_position"
        const val ARG_CONTRACTOR = "observed_contractor"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_observation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val uniqueId = arguments?.getString(ARG_UNIQUE_ID)
        val name = arguments?.getString(ARG_NAME) ?: ""
        val badge = arguments?.getString(ARG_BADGE) ?: ""
        val department = arguments?.getString(ARG_DEPARTMENT) ?: ""
        val position = arguments?.getString(ARG_POSITION) ?: ""
        val contractor = arguments?.getString(ARG_CONTRACTOR) ?: ""

        view.findViewById<TextView>(R.id.txtObsWorkerName).text = name.ifBlank { "\u2014" }
        view.findViewById<TextView>(R.id.txtObsBadge).text = badge.ifBlank { "\u2014" }
        view.findViewById<TextView>(R.id.txtObsContractor).text = contractor.ifBlank { "\u2014" }
        view.findViewById<TextView>(R.id.txtObsPosition).text = position.ifBlank { "\u2014" }

        // Build category chips (labels from string resources for i18n)
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupCategories)
        ObservationService.CATEGORY_CODES.forEach { code ->
            val chip = Chip(requireContext()).apply {
                text = getCategoryLabel(code)
                isCheckable = true
                tag = code
            }
            chipGroup.addView(chip)
        }

        view.findViewById<MaterialButton>(R.id.btnSaveObservation).setOnClickListener {
            saveObservation(view, uniqueId, badge, department, position, contractor)
        }

        view.findViewById<MaterialButton>(R.id.btnBackObservation).setOnClickListener {
            findNavController().popBackStack()
        }
    }

    private val categoryStringIds = mapOf(
        "PPE" to R.string.obs_cat_ppe,
        "SITUATIONAL_AWARENESS" to R.string.obs_cat_situational_awareness,
        "SAFETY_DEVICES" to R.string.obs_cat_safety_devices,
        "ISOLATION_LOCKOUT" to R.string.obs_cat_isolation_lockout,
        "SAFETY_SIGNAGE" to R.string.obs_cat_safety_signage,
        "TOOLS_EQUIPMENT" to R.string.obs_cat_tools_equipment,
        "LINE_OF_FIRE" to R.string.obs_cat_line_of_fire,
        "HEALTH_HYGIENE" to R.string.obs_cat_health_hygiene,
        "WORKPLACE_ENVIRONMENT" to R.string.obs_cat_workplace_environment,
        "LIFTING" to R.string.obs_cat_lifting,
        "MANUAL_HANDLING" to R.string.obs_cat_manual_handling,
        "HOUSEKEEPING" to R.string.obs_cat_housekeeping,
        "TOXIC_FLAMMABLE" to R.string.obs_cat_toxic_flammable,
        "WORK_PLANNING" to R.string.obs_cat_work_planning,
        "WORKING_AT_HEIGHT" to R.string.obs_cat_working_at_height,
        "CONFINED_SPACE" to R.string.obs_cat_confined_space,
        "HOT_WORK" to R.string.obs_cat_hot_work,
        "EXCAVATION" to R.string.obs_cat_excavation,
        "DRIVING_VEHICLES" to R.string.obs_cat_driving_vehicles,
        "SUPERVISION" to R.string.obs_cat_supervision,
        "PROCEDURES" to R.string.obs_cat_procedures,
        "SECURITY" to R.string.obs_cat_security,
        "IMPROVEMENT_OPPORTUNITY" to R.string.obs_cat_improvement_opportunity,
        "EMERGENCY_RESPONSE" to R.string.obs_cat_emergency_response
    )

    private fun getCategoryLabel(code: String): String {
        val resId = categoryStringIds[code] ?: return code
        return getString(resId)
    }

    private fun saveObservation(view: View, uniqueId: String?, badge: String, department: String, position: String, contractor: String) {
        val description = view.findViewById<EditText>(R.id.editDescription).text.toString().trim()
        if (description.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.obs_description_required), Toast.LENGTH_SHORT).show()
            return
        }

        val obsTypeGroup = view.findViewById<RadioGroup>(R.id.rgObservationType)
        val obsType = when (obsTypeGroup.checkedRadioButtonId) {
            R.id.rbCondition -> "CONDITION"
            R.id.rbBehaviour -> "BEHAVIOUR"
            else -> { Toast.makeText(requireContext(), getString(R.string.obs_type_required), Toast.LENGTH_SHORT).show(); return }
        }

        val safetyGroup = view.findViewById<RadioGroup>(R.id.rgSafetyType)
        val safetyType = when (safetyGroup.checkedRadioButtonId) {
            R.id.rbSafe -> "SAFE"
            R.id.rbUnsafe -> "UNSAFE"
            else -> { Toast.makeText(requireContext(), getString(R.string.obs_safety_required), Toast.LENGTH_SHORT).show(); return }
        }

        val interventionGroup = view.findViewById<RadioGroup>(R.id.rgIntervention)
        val intervention = when (interventionGroup.checkedRadioButtonId) {
            R.id.rbNoIntervene -> "DID_NOT_INTERVENE"
            R.id.rbIntervened -> "INTERVENED"
            R.id.rbSpotCoaching -> "SPOT_COACHING"
            R.id.rbPositiveReinforcement -> "POSITIVE_REINFORCEMENT"
            R.id.rbPositiveObservation -> "POSITIVE_OBSERVATION"
            R.id.rbNonPeer -> "NON_PEER"
            else -> null
        }

        val outcomeGroup = view.findViewById<RadioGroup>(R.id.rgOutcome)
        val outcome = when (outcomeGroup.checkedRadioButtonId) {
            R.id.rbCorrected -> "CORRECTED"
            R.id.rbPartlyCorrected -> "PARTLY_CORRECTED"
            R.id.rbNotCorrected -> "NOT_CORRECTED"
            R.id.rbOutcomePositive -> "POSITIVE_REINFORCEMENT"
            else -> null
        }

        val coachingGroup = view.findViewById<RadioGroup>(R.id.rgCoaching)
        val coaching = when (coachingGroup.checkedRadioButtonId) {
            R.id.rbCoachingNotRequired -> "NOT_REQUIRED"
            R.id.rbCoachingPending -> "TO_BE_CONDUCTED"
            R.id.rbCoachingDone -> "CONDUCTED"
            else -> "NOT_REQUIRED"
        }

        val location = view.findViewById<EditText>(R.id.editLocation).text.toString().trim().ifBlank { null }
        val areaAuthority = view.findViewById<EditText>(R.id.editAreaAuthority).text.toString().trim().ifBlank { null }
        val actionTaken = view.findViewById<EditText>(R.id.editActionTaken).text.toString().trim().ifBlank { null }
        val additionalComments = view.findViewById<EditText>(R.id.editAdditionalComments).text.toString().trim().ifBlank { null }

        val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupCategories)
        val selectedCategories = mutableListOf<String>()
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip
            if (chip?.isChecked == true) {
                selectedCategories.add(chip.tag as String)
            }
        }

        val workerName = view.findViewById<TextView>(R.id.txtObsWorkerName).text.toString()

        viewLifecycleOwner.lifecycleScope.launch {
            ServiceLocator.observationService.createObservation(
                uniqueIdValue = uniqueId,
                observedName = workerName.takeIf { it != "\u2014" },
                observedBadge = badge.ifBlank { null },
                observedDepartment = department.ifBlank { null },
                observedPosition = position.ifBlank { null },
                observedContractor = contractor.ifBlank { null },
                description = description,
                observationType = obsType,
                safetyType = safetyType,
                location = location,
                areaAuthority = areaAuthority,
                interventionAction = intervention,
                outcome = outcome,
                actionTaken = actionTaken,
                coachingStatus = coaching,
                additionalComments = additionalComments,
                categories = selectedCategories
            )
            Toast.makeText(requireContext(), getString(R.string.obs_saved), Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }
}
