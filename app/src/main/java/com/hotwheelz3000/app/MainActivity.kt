package com.hotwheelz3000.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var refresh: SwipeRefreshLayout
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val startHost: String? by lazy { Uri.parse(BuildConfig.START_URL).host }

    // Loaded once from app/src/main/assets/inject.js
    private val injectedJs: String by lazy {
        assets.open("inject.js").bufferedReader().use { it.readText() }
    }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            fileCallback?.onReceiveValue(uris.toTypedArray())
            fileCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        refresh = SwipeRefreshLayout(this).apply {
            addView(webView)
            setOnRefreshListener { webView.reload() }
        }
        setContentView(refresh)

        // Only allow pull-to-refresh when the page is scrolled to the top
        refresh.setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        // JS can call: window.Android.postMessage("text")
        webView.addJavascriptInterface(Bridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val isWeb = url.scheme == "http" || url.scheme == "https"
                val sameSite = startHost != null && url.host?.endsWith(startHost!!) == true
                if (isWeb && sameSite) return false
                // Other sites and links like mailto:, tel:, intent: open outside the app
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: Exception) {
                    !isWeb
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                view.evaluateJavascript(injectedJs, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                refresh.isRefreshing = false
                view.evaluateJavascript(injectedJs, null)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val types = params.acceptTypes.filter { it.isNotBlank() }.ifEmpty { listOf("*/*") }
                filePicker.launch(types.toTypedArray())
                return true
            }
        }

        // Back button goes back in the page history before leaving the app
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(BuildConfig.START_URL)
            UpdateChecker.check(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    inner class Bridge {
        @JavascriptInterface
        fun postMessage(message: String) {
            Log.d("Bridge", message)
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
