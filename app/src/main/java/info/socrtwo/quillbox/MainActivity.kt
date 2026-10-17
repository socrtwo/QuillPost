package info.socrtwo.quillbox

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Hosts the Outlook-style web client full-screen in a WebView, served by the in-process
 * backend ([LocalServer]). Everything the desktop and web versions do — setup wizard,
 * three-pane / stacked layout, junk verdicts, "Analyse & make rule", settings — is the same
 * code; this Activity only adds what a browser tab cannot: the Android back button,
 * attachment saving, file picking for compose, external links and printing.
 */
class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private lateinit var topStrip: View
    private lateinit var bottomStrip: View
    private lateinit var webView: WebView
    private lateinit var splash: LinearLayout
    private lateinit var splashText: TextView
    private lateinit var splashRetry: Button
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingMailto: String? = null
    private var loadedUrl: String? = null
    /** False until the web UI has rendered once (or start-up failed): keeps the launch splash on screen. */
    private var uiReady = false

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val cb = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        val data = result.data
        val uris: Array<Uri>? = if (result.resultCode != RESULT_OK || data == null) null else {
            val clip = data.clipData
            if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        }
        cb.onReceiveValue(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val launchSplash = installSplashScreen()
        launchSplash.setKeepOnScreenCondition { !uiReady }
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Never hold the system splash for more than a few seconds; the in-app screen takes over.
        window.decorView.postDelayed({ uiReady = true }, 4000)
        buildLayout()
        setContentView(root)
        // The launch theme paints the window in the splash blue. Replace it with the page colour
        // so nothing blue shows through when the keyboard resizes the layout.
        window.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.quillbox_background)))
        configureWebView()
        pendingMailto = mailtoFrom(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!::webView.isInitialized || splash.visibility == View.VISIBLE) { moveTaskToBack(true); return }
                webView.evaluateJavascript("(function(){try{return !!(window.quillboxBack&&window.quillboxBack())}catch(e){return false}})()") { r ->
                    if (r != "true") moveTaskToBack(true)
                }
            }
        })
        startBackend()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mailtoFrom(intent)?.let { deliverMailto(it) }
    }

    override fun onResume() {
        super.onResume()
        // The process may have been kept alive while the server died (or vice versa); make
        // sure a backend is running and the page points at it.
        if (loadedUrl != null && LocalServer.url == null) startBackend()
        QuillPenAndroid.forget() // QuillPen may have been installed or removed meanwhile.
    }

    override fun onDestroy() {
        if (::webView.isInitialized) { (webView.parent as? ViewGroup)?.removeView(webView); webView.destroy() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun buildLayout() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val topbar = ContextCompat.getColor(this, R.color.quillbox_topbar)
        val bg = ContextCompat.getColor(this, R.color.quillbox_background)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        topStrip = View(this).apply { setBackgroundColor(topbar) }
        bottomStrip = View(this).apply { setBackgroundColor(bg) }
        webView = WebView(this).apply { setBackgroundColor(bg) }
        val body = FrameLayout(this)
        body.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The in-app starting screen continues the launch splash: the same blue, white text.
        splash = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(topbar)
            val pad = (24 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.ic_splash)
            val size = (120 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
        splash.addView(logo)
        splash.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 28f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        splash.addView(ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE) })
        splashText = TextView(this).apply {
            text = getString(R.string.starting); textSize = 16f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        splash.addView(splashText)
        splashRetry = Button(this).apply { text = getString(R.string.retry); visibility = View.GONE; setOnClickListener { startBackend() } }
        splash.addView(splashRetry)
        body.addView(splash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(topStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        // Edge-to-edge: the strips take exactly the space of the system bars (and the keyboard),
        // painted in the web UI's own colours, so the page never sits under them.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            topStrip.layoutParams = topStrip.layoutParams.apply { height = bars.top }
            // The strip under the page is sized to whatever covers the bottom (navigation bar, or the
            // keyboard while it is up) and painted in the page colour, never the splash blue.
            bottomStrip.layoutParams = bottomStrip.layoutParams.apply { height = maxOf(bars.bottom, ime.bottom) }
            bottomStrip.setBackgroundColor(ContextCompat.getColor(this, R.color.quillbox_background))
            root.setPadding(bars.left, 0, bars.right, 0)
            topStrip.requestLayout(); bottomStrip.requestLayout()
            WindowInsetsCompat.CONSUMED
        }
    }

    // ------------------------------------------------------------------ backend

    private fun startBackend() {
        splash.visibility = View.VISIBLE
        splashRetry.visibility = View.GONE
        splashText.text = getString(R.string.starting)
        LocalServer.start(this) { base ->
            if (isFinishing || isDestroyed) return@start
            if (base == null) {
                uiReady = true
                splashText.text = getString(R.string.start_failed) + "\n" + (LocalServer.error ?: "")
                splashRetry.visibility = View.VISIBLE
                return@start
            }
            if (loadedUrl != base) { loadedUrl = base; webView.loadUrl(base) } else splash.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // The web UI reads this to know it runs inside the app (in-app dialogs instead of
            // pop-up windows, "this phone" wording on the setup screen, native printing).
            userAgentString = "$userAgentString QuillboxApp/${BuildConfig.VERSION_NAME} (Android)"
        }
        webView.addJavascriptInterface(Bridge(), "QuillboxAndroid")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (isLocal(url)) return false
                // The embedded QuillPen editor (compose iframe) stays inside the app.
                if (QuillPenAndroid.isEmbedUrl(url) && !request.isForMainFrame) return false
                if (url.scheme == "mailto") { deliverMailto(url.toString()); return true }
                openExternal(url.toString())
                return true
            }
            // QuillPen embedded editor: https://quillpen.embed/<path> is answered from QuillPen's ContentProvider.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                QuillPenAndroid.intercept(this@MainActivity, request)
            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && isLocal(Uri.parse(url))) {
                    uiReady = true
                    splash.visibility = View.GONE
                    pendingMailto?.let { m -> pendingMailto = null; deliverMailto(m) }
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && isLocal(request.url)) {
                    // The backend went away (process partly reclaimed): restart it and reload.
                    loadedUrl = null
                    startBackend()
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = params.createIntent().apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    if (params.acceptTypes.isNullOrEmpty() || params.acceptTypes.all { it.isBlank() }) type = "*/*"
                }
                return try { filePicker.launch(Intent.createChooser(intent, null)); true }
                catch (_: ActivityNotFoundException) { fileCallback = null; callback.onReceiveValue(null); false }
            }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // target="_blank" links (e.g. inside a message body) open in the system browser.
                val hit = view.hitTestResult
                val direct = hit.extra?.takeIf { hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE }
                if (direct != null) { openExternal(direct); return false }
                val temp = WebView(view.context)
                temp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        if (isLocal(request.url)) webView.loadUrl(request.url.toString()) else openExternal(request.url.toString())
                        v.post { v.destroy() }
                        return true
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = temp
                resultMsg.sendToTarget()
                return true
            }
        }
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            saveAttachment(url, name, mimeType ?: "application/octet-stream")
        }
    }

    private fun isLocal(uri: Uri): Boolean {
        val base = LocalServer.url ?: return false
        val b = Uri.parse(base)
        return uri.scheme == b.scheme && uri.host == b.host && uri.port == b.port
    }

    private fun openExternal(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show() }
    }

    private fun mailtoFrom(intent: Intent?): String? =
        intent?.data?.takeIf { it.scheme == "mailto" }?.toString()

    private fun deliverMailto(mailto: String) {
        if (LocalServer.url == null || splash.visibility == View.VISIBLE) { pendingMailto = mailto; return }
        val js = "window.quillboxMailto && window.quillboxMailto(" + jsString(mailto) + ")"
        webView.evaluateJavascript(js, null)
    }

    private fun jsString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\""

    // ------------------------------------------------------------------ attachments

    /** Fetches an attachment from the local backend and saves it under Downloads/Quillbox. */
    private fun saveAttachment(url: String, fileName: String, mimeType: String) {
        thread(name = "quillbox-download") {
            try {
                val bytes = (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 5000; readTimeout = 60_000
                    try { if (responseCode != 200) throw IllegalStateException("HTTP $responseCode"); inputStream.use { it.readBytes() } } finally { disconnect() }
                }
                val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "attachment" }
                val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Quillbox")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore refused the file")
                    contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
                    contentResolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    u
                } else {
                    val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Quillbox").apply { mkdirs() }
                    val f = File(dir, safeName); f.writeBytes(bytes)
                    FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
                }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.saved_to, safeName), Toast.LENGTH_LONG).show()
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try { startActivity(Intent.createChooser(view, safeName)) } catch (_: ActivityNotFoundException) { Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, getString(R.string.download_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ------------------------------------------------------------------ JS bridge

    /** Called from app.js as `QuillboxAndroid.<method>()`; every method is safe to call from the page thread. */
    inner class Bridge {
        @JavascriptInterface fun platform(): String = "android"
        @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
        @JavascriptInterface fun openExternal(url: String) { runOnUiThread { this@MainActivity.openExternal(url) } }
        @JavascriptInterface fun print(jobName: String?) {
            runOnUiThread {
                val pm = getSystemService(PRINT_SERVICE) as PrintManager
                val name = (jobName ?: "Quillbox message").take(80)
                pm.print(name, webView.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
            }
        }
        /** True when QuillPen is installed and its embed provider answers (compose "QuillPen" button). */
        @JavascriptInterface fun quillPenAvailable(): Boolean = QuillPenAndroid.isAvailable(this@MainActivity)
        @JavascriptInterface fun toast(text: String) { runOnUiThread { Toast.makeText(this@MainActivity, text, Toast.LENGTH_SHORT).show() } }
    }
}
