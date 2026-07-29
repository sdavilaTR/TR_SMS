package com.example.hassiwrapper.ui.bugreport

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Title + description form for a Bug Report. The screenshot is already captured and saved to
 * disk before this sheet opens (see MainActivity's FAB handler) — this only previews it. Logs
 * are captured fresh right before send, so they cover the moment the user is actually reporting.
 */
class BugReportBottomSheet : BottomSheetDialogFragment() {

    private var screenshotPath: String? = null
    private var screenName: String? = null

    companion object {
        private const val ARG_SCREENSHOT_PATH = "screenshot_path"
        private const val ARG_SCREEN_NAME = "screen_name"

        fun newInstance(screenshotPath: String?, screenName: String?) = BugReportBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_SCREENSHOT_PATH, screenshotPath)
                putString(ARG_SCREEN_NAME, screenName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenshotPath = arguments?.getString(ARG_SCREENSHOT_PATH)
        screenName = arguments?.getString(ARG_SCREEN_NAME)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_bug_report, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imgScreenshot = view.findViewById<ImageView>(R.id.imgBugReportScreenshot)
        val etTitle = view.findViewById<TextInputEditText>(R.id.etBugReportTitle)
        val etDescription = view.findViewById<TextInputEditText>(R.id.etBugReportDescription)
        val btnSend = view.findViewById<MaterialButton>(R.id.btnSendBugReport)

        screenshotPath?.let { path ->
            if (File(path).exists()) imgScreenshot.setImageURI(Uri.fromFile(File(path)))
        }

        btnSend.setOnClickListener {
            val title = etTitle.text?.toString()?.trim().orEmpty()
            val description = etDescription.text?.toString()?.trim().orEmpty()
            if (title.isEmpty()) {
                etTitle.error = getString(R.string.bug_report_error_missing_title)
                return@setOnClickListener
            }
            if (description.isEmpty()) {
                etDescription.error = getString(R.string.bug_report_error_missing_description)
                return@setOnClickListener
            }

            btnSend.isEnabled = false
            lifecycleScope.launch {
                try {
                    val logs = withContext(Dispatchers.IO) { BugReportCapture.captureLogsTail() }
                    ServiceLocator.bugReportService.createBugReport(
                        title = title,
                        description = description,
                        logs = logs,
                        screenshotPath = screenshotPath,
                        screenName = screenName
                    )
                    Toast.makeText(requireContext(), R.string.bug_report_sent_ok, Toast.LENGTH_LONG).show()
                    dismiss()
                } catch (e: Exception) {
                    btnSend.isEnabled = true
                    Toast.makeText(requireContext(), getString(R.string.bug_report_error_send, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
