package com.example.hassiwrapper.ui.login

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.BuildConfig
import com.example.hassiwrapper.LocaleHelper
import com.example.hassiwrapper.MainActivity
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private val webViewLoginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val token = result.data?.getStringExtra("token")
            if (!token.isNullOrEmpty()) {
                lifecycleScope.launch {
                    ServiceLocator.configRepo.set("user_token", token)
                    goToMain()
                }
            } else {
                findViewById<TextView>(R.id.txtLoginError).apply {
                    text = getString(R.string.login_error_token)
                    visibility = View.VISIBLE
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.applyLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            ServiceLocator.apiClient.seedDefaults()
            if (ServiceLocator.authRepo.isAuthenticated()) {
                goToMain()
                return@launch
            }
            setupLoginUI()
        }
    }

    private fun setupLoginUI() {
        setContentView(R.layout.activity_login)

        val inputTerminalCode = findViewById<EditText>(R.id.inputTerminalCode)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val txtError = findViewById<TextView>(R.id.txtLoginError)
        val progress = findViewById<ProgressBar>(R.id.progressLogin)
        val btnConfigToggle = findViewById<TextView>(R.id.btnConfigToggle)
        val panelApiConfig = findViewById<View>(R.id.panelApiConfig)
        val inputApiUrl = findViewById<EditText>(R.id.inputApiUrl)
        val btnSaveApiUrl = findViewById<Button>(R.id.btnSaveApiUrl)
        val txtApiSaved = findViewById<TextView>(R.id.txtApiSaved)
        val btnMicrosoftLogin = findViewById<Button>(R.id.btnMicrosoftLogin)

        findViewById<TextView>(R.id.txtVersion).text = BuildConfig.BUILD_TAG

        btnMicrosoftLogin.setOnClickListener {
            val intent = Intent(this, WebViewLoginActivity::class.java)
            webViewLoginLauncher.launch(intent)
        }

        lifecycleScope.launch {
            val savedUrl = ServiceLocator.configRepo.get("api_base_url")
            if (!savedUrl.isNullOrEmpty()) inputApiUrl.setText(savedUrl)
        }

        btnConfigToggle.setOnClickListener {
            panelApiConfig.visibility = if (panelApiConfig.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        btnSaveApiUrl.setOnClickListener {
            lifecycleScope.launch {
                val url = inputApiUrl.text.toString().trim()
                ServiceLocator.configRepo.set("api_base_url", url)
                ServiceLocator.apiClient.resetResolvedBase()
                txtApiSaved.visibility = View.VISIBLE
                txtApiSaved.postDelayed({ txtApiSaved.visibility = View.GONE }, 2000)
            }
        }

        btnLogin.setOnClickListener {
            val code = inputTerminalCode.text.toString().trim().uppercase()

            if (code.isEmpty()) {
                txtError.text = "Error: Introduce el Código del Terminal."
                txtError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val email = "${code.lowercase()}@atlas.com"
            val password = "Tr.Atlas_${code}!"

            btnLogin.isEnabled = false
            progress.visibility = View.VISIBLE
            txtError.visibility = View.GONE

            lifecycleScope.launch {
                try {
                    val api = ServiceLocator.apiClient.getService()
                    val result = ServiceLocator.authRepo.loginWithCredentials(email, password, api)
                    if (result.isSuccess) {
                        goToMain()
                    } else {
                        txtError.text = result.exceptionOrNull()?.message ?: getString(R.string.login_error_auth)
                        txtError.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    txtError.text = getString(R.string.login_error_format, e.message)
                    txtError.visibility = View.VISIBLE
                } finally {
                    btnLogin.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
