package com.example.hassiwrapper.ui.login

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hassiwrapper.ProfileManager
import com.example.hassiwrapper.R
import com.example.hassiwrapper.ServiceLocator
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class WebViewLoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorLayout: View
    private lateinit var retryButton: View
    private var loginUrl: String? = null

    // Poll every second after each page load to grab the token as soon as it lands in storage
    private val tokenPoller = Handler(Looper.getMainLooper())
    private var pollingActive = false
    private var pollCount = 0

    // JavaScript injected to grab any JWT-looking value from localStorage / sessionStorage
    private val JS_FIND_TOKEN = """
        (function() {
            var keys = ['atlas_token','authToken','auth_token','token','user_token',
                        'jwt','access_token','id_token','Bearer'];
            function extract(store) {
                for (var i = 0; i < keys.length; i++) {
                    try {
                        var v = store.getItem(keys[i]);
                        if (!v) continue;
                        try {
                            var p = JSON.parse(v);
                            var t = p.token || p.access_token || p.jwt || p.id_token;
                            if (t && t.split('.').length === 3) return t;
                        } catch(e) {}
                        if (v.split('.').length === 3 && v.length > 30) return v;
                    } catch(e) {}
                }
                // Scan all keys for any JWT-shaped value
                try {
                    for (var j = 0; j < store.length; j++) {
                        var k = store.key(j);
                        var val = store.getItem(k);
                        if (val && val.split('.').length === 3 && val.length > 50) return val;
                    }
                } catch(e) {}
                return null;
            }
            return extract(localStorage) || extract(sessionStorage) || null;
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_login)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarWebView)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Microsoft Login"

        webView = findViewById(R.id.webViewLogin)
        progressBar = findViewById(R.id.progressWebView)
        errorLayout = findViewById(R.id.layoutWebViewError)
        retryButton = findViewById(R.id.buttonWebViewRetry)
        retryButton.setOnClickListener { loadLoginUrl() }

        setupWebView()

        lifecycleScope.launch {
            val apiBase = ProfileManager.getApiUrl()

            // Derive Web App URL:
            //   Old: https://web-atlas-api-pre.azurewebsites.net -> https://web-atlas-pre.azurewebsites.net
            //   New: https://atlas.tecnicasreunidas.es/api       -> https://atlas.tecnicasreunidas.es/
            val webBase = apiBase.replace("-api", "").removeSuffix("/api")

            // Clear cookies AND DOM storage to ensure a fresh Microsoft login.
            // MSAL caches its tokens in localStorage/sessionStorage (not cookies), so
            // clearing only cookies leaves a stale token behind that JS_FIND_TOKEN grabs
            // on the very first onPageFinished — instantly finishing this activity and
            // bouncing the user back to the menu without ever showing the login page.
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()

            loginUrl = if (webBase.endsWith("/")) "${webBase}login" else "$webBase/login"
            loadLoginUrl()
        }
    }

    private fun loadLoginUrl() {
        val url = loginUrl ?: return
        errorLayout.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Spoof a real Chrome mobile user agent so Microsoft OAuth doesn't
            // detect this as an embedded WebView and block the login flow.
            userAgentString = "Mozilla/5.0 (Linux; Android 12; Pixel 6) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true
            useWideViewPort = true
            // Force a live network fetch for the login page so a dead connection surfaces as
            // onReceivedError (and our overlay) instead of silently serving a stale cached shell
            // that hangs forever once its JS tries to reach the API.
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.clearCache(true)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                if (errorLayout.visibility == View.VISIBLE) return
                // Check immediately and start polling
                checkForToken()
                startPolling()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) showNetworkError()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val urlStr = request?.url?.toString() ?: return false
                // Let http/https through (including Microsoft OAuth redirects)
                if (urlStr.startsWith("http://") || urlStr.startsWith("https://")) return false
                // Handle custom URI schemes (intent://, msal://, etc.)
                return try {
                    val intent = Intent.parseUri(urlStr, Intent.URI_INTENT_SCHEME)
                    startActivity(intent)
                    true
                } catch (e: Exception) {
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress == 100) progressBar.visibility = View.GONE
            }
        }
    }

    private fun showNetworkError() {
        stopPolling()
        progressBar.visibility = View.GONE
        webView.visibility = View.GONE
        errorLayout.visibility = View.VISIBLE
    }

    private fun startPolling() {
        stopPolling()
        pollingActive = true
        pollCount = 0
        scheduleNextPoll()
    }

    private fun stopPolling() {
        pollingActive = false
        tokenPoller.removeCallbacksAndMessages(null)
    }

    private fun scheduleNextPoll() {
        if (!pollingActive || pollCount >= 60) { // stop after ~60 seconds
            stopPolling()
            return
        }
        tokenPoller.postDelayed({
            checkForToken()
            pollCount++
            scheduleNextPoll()
        }, 1000L)
    }

    private fun checkForToken() {
        webView.evaluateJavascript(JS_FIND_TOKEN) { result ->
            if (result != null && result != "null" && result.isNotBlank()) {
                val token = result.trim('"')
                if (token.split(".").size == 3 && token.length > 30) {
                    stopPolling()
                    returnToken(token)
                }
            }
        }
    }

    private fun returnToken(token: String) {
        lifecycleScope.launch {
            ServiceLocator.configRepo.set("user_token", token)
        }
        setResult(Activity.RESULT_OK, Intent().putExtra("token", token))
        finish()
    }

    override fun onDestroy() {
        stopPolling()
        webView.destroy()
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
