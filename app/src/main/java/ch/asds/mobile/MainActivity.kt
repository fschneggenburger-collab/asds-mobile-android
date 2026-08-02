package ch.asds.mobile

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ASDSMobile"
        private const val BASE_URL =
            "https://portal.ihre-wegbegleiterin.ch/custom/asds_mobile/mobile/"
        private const val PORTAL_URL = "${BASE_URL}index.php?stage=10"
    }

    private lateinit var root: View
    private lateinit var appHeader: LinearLayout
    private lateinit var appSubtitle: TextView
    private lateinit var headerMore: ImageButton
    private lateinit var pageProgress: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var webView: WebView
    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var errorPanel: View
    private lateinit var retryButton: MaterialButton

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var cameraOutputFile: File? = null
    private var currentUserName = ""
    private var currentPageLabel = "Übersicht"

    private val photoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            deliver(if (uri != null) arrayOf(uri) else null)
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraOutputUri
            if (success && uri != null) {
                deliver(arrayOf(uri))
            } else {
                cleanupCameraFile()
                deliver(null)
            }
        }

    private val pdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Not every provider supports persistent permissions.
                }
                deliver(arrayOf(uri))
            } else {
                deliver(null)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        setContentView(R.layout.activity_main)
        bindViews()
        applySystemBarInsets()
        configureNativeShell()
        configureWebView()
        cleanupOldCameraFiles()

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(PORTAL_URL)
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        appHeader = findViewById(R.id.appHeader)
        appSubtitle = findViewById(R.id.appSubtitle)
        headerMore = findViewById(R.id.headerMore)
        pageProgress = findViewById(R.id.pageProgress)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView = findViewById(R.id.webView)
        bottomNavigation = findViewById(R.id.bottomNavigation)
        errorPanel = findViewById(R.id.errorPanel)
        retryButton = findViewById(R.id.retryButton)
    }

    private fun applySystemBarInsets() {
        val headerLeft = appHeader.paddingLeft
        val headerTop = appHeader.paddingTop
        val headerRight = appHeader.paddingRight
        val headerBottom = appHeader.paddingBottom
        val navLeft = bottomNavigation.paddingLeft
        val navTop = bottomNavigation.paddingTop
        val navRight = bottomNavigation.paddingRight
        val navBottom = bottomNavigation.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            appHeader.setPadding(
                headerLeft,
                headerTop + bars.top,
                headerRight,
                headerBottom
            )
            bottomNavigation.setPadding(
                navLeft,
                navTop,
                navRight,
                navBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun configureNativeShell() {
        swipeRefresh.setColorSchemeResources(R.color.asds_gold, R.color.asds_blue)
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.asds_surface)
        swipeRefresh.setOnRefreshListener { webView.reload() }

        retryButton.setOnClickListener {
            errorPanel.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.reload()
        }

        headerMore.setOnClickListener { showMoreMenu() }

        bottomNavigation.selectedItemId = R.id.navHome
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navHome -> {
                    navigateTo("index.php")
                    true
                }
                R.id.navTrips -> {
                    navigateTo("trips.php")
                    true
                }
                R.id.navTime -> {
                    navigateTo("time.php")
                    true
                }
                R.id.navMore -> {
                    showMoreMenu()
                    false
                }
                else -> false
            }
        }
    }

    private fun navigateTo(page: String, extraQuery: String = "") {
        val suffix = if (extraQuery.isBlank()) "" else "&$extraQuery"
        webView.loadUrl("$BASE_URL$page?stage=10$suffix")
    }

    private fun showMoreMenu() {
        val options = arrayOf(
            getString(R.string.menu_expenses),
            getString(R.string.menu_manual_trip),
            getString(R.string.menu_appointments),
            getString(R.string.menu_protocols),
            getString(R.string.menu_logout)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> navigateTo("expenses.php")
                    1 -> navigateTo("manual_trip.php")
                    2, 3 -> Toast.makeText(this, R.string.coming_soon, Toast.LENGTH_SHORT).show()
                    4 -> navigateTo("logout.php")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        cancelPending()
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.alpha = 0f
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            userAgentString = "$userAgentString ASDSMobile/1.3.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageProgress.visibility = View.VISIBLE
                errorPanel.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.alpha = 0f
                updateNativeShell(url.orEmpty())
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAsdsDesign()
                updateNativeShell(url.orEmpty())
                pageProgress.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                webView.animate().alpha(1f).setDuration(160L).start()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) showLoadError()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://portal.ihre-wegbegleiterin.ch/") ||
                    url.startsWith("https://ihre-wegbegleiterin.ch/")
                ) {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No app for external URL", e)
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                cancelPending()
                if (filePathCallback == null) return false
                fileCallback = filePathCallback

                val types = fileChooserParams?.acceptTypes
                    ?.asSequence()
                    ?.flatMap { it.split(',', ';').asSequence() }
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?.toList()
                    ?: emptyList()

                val capture = fileChooserParams?.isCaptureEnabled == true
                val acceptsImage = types.isEmpty() ||
                    types.any { it == "*/*" || it.startsWith("image/") }
                val acceptsPdf = types.any {
                    it == "*/*" || it == "application/*" || it == "application/pdf"
                }

                return try {
                    when {
                        capture && acceptsImage && !acceptsPdf -> launchCamera()
                        acceptsImage && acceptsPdf -> showSourceChooser()
                        acceptsImage -> launchPhotoPicker()
                        acceptsPdf -> launchPdfPicker()
                        else -> showSourceChooser()
                    }
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "File chooser failed", e)
                    showError(e)
                    deliver(null)
                    true
                }
            }
        }
    }

    private fun injectAsdsDesign() {
        val script = """
            (function() {
                var style = document.getElementById('asds-native-shell-style');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'asds-native-shell-style';
                    style.textContent = `
                        :root{--nav:#102c50!important;--blue:#1f5f96!important;--gold:#c8a13a!important;--bg:#f3f6fa!important;--card:#fff!important;--muted:#6b7785!important}
                        html,body{background:#f3f6fa!important;color:#17253a!important;overflow-x:hidden!important}
                        body{margin:0!important;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif!important}
                        body>header{display:none!important}
                        main{max-width:920px!important;margin:0 auto!important;padding:16px 12px 34px!important}
                        .card{background:#fff!important;border:1px solid #e7edf3!important;border-radius:20px!important;padding:18px!important;margin-bottom:14px!important;box-shadow:0 8px 28px rgba(16,44,80,.08)!important}
                        h1{font-size:25px!important;line-height:1.2!important;margin:0 0 16px!important;color:#17253a!important;letter-spacing:-.35px!important}
                        h2{font-size:19px!important;line-height:1.3!important;color:#17253a!important}
                        h3{color:#17253a!important}
                        .grid{gap:12px!important}
                        .tile{border:1px solid #e7edf3!important;border-radius:18px!important;padding:18px!important;box-shadow:0 7px 22px rgba(16,44,80,.07)!important}
                        .tile strong{font-size:18px!important;color:#17253a!important}
                        .tile span{font-size:13px!important;line-height:1.45!important}
                        .row{grid-template-columns:1fr!important;gap:6px!important;align-items:stretch!important;margin:14px 0!important}
                        label{font-size:15px!important;color:#26364b!important}
                        input,select,textarea{width:100%!important;min-height:52px!important;border:1px solid #cfd9e3!important;border-radius:14px!important;background:#fbfcfe!important;padding:12px 14px!important;color:#17253a!important;outline:none!important}
                        input:focus,select:focus,textarea:focus{border-color:#1f5f96!important;box-shadow:0 0 0 3px rgba(31,95,150,.12)!important;background:#fff!important}
                        textarea{min-height:110px!important;resize:vertical!important}
                        input[type=file]{padding:8px!important;background:#fff!important}
                        input[type=file]::file-selector-button{border:0!important;border-radius:10px!important;padding:10px 12px!important;margin-right:10px!important;background:#fff3cf!important;color:#5c4510!important;font-weight:700!important}
                        button,.btn{min-height:48px!important;border-radius:14px!important;padding:12px 16px!important;font-weight:750!important;box-shadow:none!important}
                        button.gold,.btn.gold{background:#c8a13a!important;color:#102c50!important}
                        .btn.secondary{background:#eaf0f6!important;color:#102c50!important}
                        .actions{gap:10px!important;margin-top:14px!important}
                        .actions>.btn{flex:1 1 auto!important;text-align:center!important}
                        .list{border-collapse:separate!important;border-spacing:0!important;overflow:hidden!important}
                        .list th,.list td{padding:12px 8px!important;border-bottom:1px solid #edf1f5!important}
                        .badge{padding:5px 9px!important}
                        .success,.error,.info{border-radius:14px!important;padding:13px 15px!important}
                        .muted{line-height:1.45!important}
                        .footer{padding:16px 4px 6px!important}
                        @media(max-width:600px){main{padding:14px 10px 28px!important}.card{padding:16px!important;border-radius:18px!important}.grid{grid-template-columns:1fr!important}.list{font-size:13px!important}}
                    `;
                    document.head.appendChild(style);
                }
                var serverHeader = document.querySelector('body > header');
                var user = serverHeader ? serverHeader.querySelector('.user') : null;
                return JSON.stringify({
                    user: user ? user.textContent.trim() : '',
                    title: document.title || ''
                });
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { raw ->
            try {
                if (raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
                val decoded = JSONObject("{\"value\":$raw}").getString("value")
                val data = JSONObject(decoded)
                currentUserName = data.optString("user", currentUserName)
                updateSubtitle()
            } catch (e: Exception) {
                Log.d(TAG, "Could not read web page identity", e)
            }
        }
    }

    private fun updateNativeShell(url: String) {
        val page = try {
            Uri.parse(url).lastPathSegment.orEmpty()
        } catch (_: Exception) {
            ""
        }

        val pairedArea = page != "pair.php" && page != "logout.php"
        bottomNavigation.visibility = if (pairedArea) View.VISIBLE else View.GONE
        headerMore.visibility = if (pairedArea) View.VISIBLE else View.INVISIBLE

        currentPageLabel = when (page) {
            "trips.php", "trip.php" -> "Fahrten"
            "time.php" -> "Arbeitszeit"
            "expenses.php" -> "Spesen & Ausgaben"
            "manual_trip.php" -> "Fahrt manuell"
            "pair.php" -> "Gerät koppeln"
            "logout.php" -> "Abmeldung"
            else -> "Übersicht"
        }
        updateSubtitle()

        if (!pairedArea) return
        when (page) {
            "trips.php", "trip.php" -> bottomNavigation.menu.findItem(R.id.navTrips).isChecked = true
            "time.php" -> bottomNavigation.menu.findItem(R.id.navTime).isChecked = true
            "index.php", "" -> bottomNavigation.menu.findItem(R.id.navHome).isChecked = true
        }
    }

    private fun updateSubtitle() {
        appSubtitle.text = if (currentUserName.isBlank()) {
            currentPageLabel
        } else {
            "$currentUserName · $currentPageLabel"
        }
    }

    private fun showLoadError() {
        pageProgress.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        webView.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
    }

    private fun showSourceChooser() {
        val options = arrayOf(
            getString(R.string.option_photo),
            getString(R.string.option_camera),
            getString(R.string.option_pdf)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_source_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchPhotoPicker()
                    1 -> launchCamera()
                    2 -> launchPdfPicker()
                    else -> deliver(null)
                }
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                deliver(null)
            }
            .setOnCancelListener { deliver(null) }
            .show()
    }

    private fun launchPhotoPicker() {
        try {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Photo Picker failed", e)
            Toast.makeText(this, R.string.error_no_picker, Toast.LENGTH_LONG).show()
            deliver(null)
        }
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("ASDS_Beleg_", ".jpg", dir)
            cameraOutputFile = file
            cameraOutputUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            cameraLauncher.launch(cameraOutputUri!!)
        } catch (e: Exception) {
            Log.e(TAG, "Camera failed", e)
            cleanupCameraFile()
            Toast.makeText(this, R.string.error_no_camera, Toast.LENGTH_LONG).show()
            deliver(null)
        }
    }

    private fun launchPdfPicker() {
        try {
            pdfLauncher.launch(arrayOf("application/pdf"))
        } catch (e: Exception) {
            Log.e(TAG, "PDF picker failed", e)
            Toast.makeText(this, R.string.error_no_pdf, Toast.LENGTH_LONG).show()
            deliver(null)
        }
    }

    private fun deliver(uris: Array<Uri>?) {
        val callback = fileCallback
        fileCallback = null
        cameraOutputUri = null
        cameraOutputFile = null
        try {
            callback?.onReceiveValue(uris)
        } catch (e: Exception) {
            Log.e(TAG, "WebView callback failed", e)
        }
    }

    private fun cancelPending() {
        val callback = fileCallback
        fileCallback = null
        try {
            callback?.onReceiveValue(null)
        } catch (_: Exception) {
        }
        cleanupCameraFile()
    }

    private fun cleanupCameraFile() {
        try {
            cameraOutputFile?.delete()
        } catch (_: Exception) {
        }
        cameraOutputFile = null
        cameraOutputUri = null
    }

    private fun cleanupOldCameraFiles() {
        try {
            val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            File(cacheDir, "camera").listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) file.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache cleanup failed", e)
        }
    }

    private fun showError(e: Exception) {
        Toast.makeText(
            this,
            getString(R.string.error_generic, e.message ?: e.javaClass.simpleName),
            Toast.LENGTH_LONG
        ).show()
    }
}
