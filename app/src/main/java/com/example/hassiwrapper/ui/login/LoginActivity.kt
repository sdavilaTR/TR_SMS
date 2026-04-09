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
        val btnMicrosoftLogin = findViewById<Button>(R.id.btnMicrosoftLogin)
        val btnSkipLogin = findViewById<Button>(R.id.btnSkipLogin)

        findViewById<TextView>(R.id.txtVersion).text = BuildConfig.BUILD_TAG

        btnSkipLogin.setOnClickListener {
            goToMain()
        }

        btnMicrosoftLogin.setOnClickListener {
            val intent = Intent(this, WebViewLoginActivity::class.java)
            webViewLoginLauncher.launch(intent)
        }

        // Configurator logic removed

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
                        // Store device code for auto-re-login during sync
                        ServiceLocator.configRepo.set("device_code", code)
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
