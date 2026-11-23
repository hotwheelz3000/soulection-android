package com.hotwheelz3000.soulection

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    companion object {
        private const val HOME = "https://radio.soulection.com/"
        private val SOULECTION = setOf("https://radio.soulection.com", "https://*.soulection.com", "https://soulection.com")
        private val PLAYERS = setOf("https://w.soundcloud.com", "https://*.soundcloud.com")
    }

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private lateinit var favorites: FavoritesBridge
    private val main = Handler(Looper.getMainLooper())
    private var documentStart = false
    private var loadRetries = 0

    private fun asset(name: String) = assets.open(name).bufferedReader().use { it.readText() }
    private val shimJs by lazy { asset("media-shim.js") }
    private val nowPlayingJs by lazy { asset("nowplaying.js") }
    private val favoritesJs by lazy { asset("favorites.js") }

    // File screens for backups (save / pick).
    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        favorites.onCreated(it)
    }
    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        favorites.onOpened(it)
    }
    private val notifyPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) NewShows.schedule(this)
    }

    fun createDocument(name: String) = try { createDoc.launch(name) } catch (e: ActivityNotFoundException) { favorites.toast("No file app found.") }
    fun openDocument() = try { openDoc.launch(arrayOf("application/json", "text/*", "application/octet-stream", "*/*")) } catch (e: ActivityNotFoundException) { favorites.toast("No file app found.") }
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Light icons on the dark page.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val bg = getSharedPreferences("app", MODE_PRIVATE).getInt("PageBackground", Color.BLACK)
        webView = WebView(this)
        webView.setBackgroundColor(bg)
        root = FrameLayout(this).apply {
            setBackgroundColor(bg)
            addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        // The page sits between the status bar and the navigation bar (and above the keyboard).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        MediaHub.init(this)
        MediaHub.runJs = { script -> runJs(script) }
        favorites = FavoritesBridge(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
        }
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        // Keep the page's process at full priority with the screen off, so the music
        // (and the app's playlists) keep going.
        if (Build.VERSION.SDK_INT >= 26) webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        // Page ↔ app messages, only from Soulection's page (favorites) and the players (media).
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "SRBridge", SOULECTION) { _, message, _, isMainFrame, _ ->
                if (!isMainFrame) return@addWebMessageListener
                val body = try { JSONObject(message.data ?: return@addWebMessageListener) } catch (e: Exception) { return@addWebMessageListener }
                favorites.handle(body)
            }
            WebViewCompat.addWebMessageListener(webView, "SRMedia", SOULECTION + PLAYERS) { _, message, _, _, reply ->
                MediaHub.onMessage(message.data ?: return@addWebMessageListener, reply)
            }
        }
        // The iPhone app's scripts, in every frame they're for, before the site's own code.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentStart = true
            WebViewCompat.addDocumentStartJavaScript(webView, shimJs, SOULECTION + PLAYERS)
            WebViewCompat.addDocumentStartJavaScript(webView, nowPlayingJs, SOULECTION + PLAYERS)
            WebViewCompat.addDocumentStartJavaScript(webView, favoritesJs, SOULECTION)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    openExternally(url)
                    return true
                }
                if (!request.isForMainFrame) return false
                if (!isInternal(url)) {
                    openExternally(url)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                MediaHub.pageReloaded()
                if (!documentStart) {
                    // Older WebView: main page only (no lock screen info from the players).
                    view.evaluateJavascript(shimJs, null)
                    view.evaluateJavascript(nowPlayingJs, null)
                    view.evaluateJavascript(favoritesJs, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loadRetries = 0
                sampleBackground()
                main.postDelayed({ sampleBackground() }, 1500)
                sendCanGoBack()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                sendCanGoBack()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // No connection when the app opened: try again by itself a few times.
                if (!request.isForMainFrame || loadRetries >= 6) return
                loadRetries++
                main.postDelayed({ view.loadUrl(HOME) }, 5000)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                recreate()
                return true
            }
        }
        webView.webChromeClient = WebChromeClient()

        // Back: a page back, else leave the app with the music still playing.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else moveTaskToBack(true)
            }
        })

        val path = intent?.getStringExtra(NewShows.EXTRA_PATH)
        webView.loadUrl(if (path != null && path.startsWith("/episodes/")) HOME.trimEnd('/') + path else HOME)

        NewShows.schedule(this)
        UpdateChecker.check(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A new-show notification tapped while the app is open: go to that show.
        val path = intent.getStringExtra(NewShows.EXTRA_PATH) ?: return
        if (!path.startsWith("/episodes/")) return
        val literal = JSONObject.quote(path)
        webView.evaluateJavascript(
            "(function(p){if(window.next&&window.next.router&&window.next.router.push){window.next.router.push(p);return true;}return false;})($literal)"
        ) { result ->
            if (result != "true") webView.loadUrl(HOME.trimEnd('/') + path)
        }
    }

    override fun onStart() {
        super.onStart()
        NewShows.checkSoon(this, "opening the app")
    }

    override fun onStop() {
        super.onStop()
        NewShows.checkSoon(this, "leaving the app")
    }

    override fun onDestroy() {
        MediaHub.runJs = null
        if (isFinishing) PlaybackService.stop(this)
        webView.destroy()
        super.onDestroy()
    }

    fun runJs(script: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) webView.evaluateJavascript(script, null)
        else main.post { webView.evaluateJavascript(script, null) }
    }

    fun goBack() { if (webView.canGoBack()) webView.goBack() }
    fun goForward() { if (webView.canGoForward()) webView.goForward() }

    /** Tells the page whether Back and Forward can do anything (dims its buttons). */
    fun sendCanGoBack() {
        runJs("window.__soulectionFavorites && window.__soulectionFavorites.canGoBack && window.__soulectionFavorites.canGoBack(${webView.canGoBack()}, ${webView.canGoForward()});")
    }

    private fun isInternal(url: Uri): Boolean {
        val host = url.host?.lowercase() ?: return true
        return host == "soulection.com" || host.endsWith(".soulection.com")
    }

    private fun openExternally(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (e: Exception) { }
    }

    /** The page's background color, for the bars and loading area, so they blend in. */
    private fun sampleBackground() {
        val script = """
            (function () {
              function rgb(v) {
                var m = v && v.match(/rgba?\(([^)]+)\)/); if (!m) return null;
                var p = m[1].split(/[\s,\/]+/).filter(Boolean).map(parseFloat);
                if (p.length < 3 || (p.length > 3 && p[3] < 0.99)) return null;
                return p.slice(0, 3).map(Math.round).join(',');
              }
              function bg(el) { return el ? rgb(getComputedStyle(el).backgroundColor) : null; }
              return bg(document.body) || bg(document.documentElement);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            val parts = result?.trim('"')?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: return@evaluateJavascript
            if (parts.size != 3 || parts.any { it !in 0..255 }) return@evaluateJavascript
            val color = Color.rgb(parts[0], parts[1], parts[2])
            root.setBackgroundColor(color)
            webView.setBackgroundColor(color)
            getSharedPreferences("app", MODE_PRIVATE).edit().putInt("PageBackground", color).apply()
        }
    }
}
