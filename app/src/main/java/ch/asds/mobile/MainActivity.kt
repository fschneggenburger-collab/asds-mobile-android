package ch.asds.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.URLUtil
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
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
    private lateinit var offlineRepository: OfflineRepository
    private lateinit var securityController: SecurityController
    private lateinit var nativeBridge: ASDSNativeBridge

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var cameraOutputFile: File? = null
    private var currentUserName = ""
    private var currentPageLabel = "Übersicht"

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            AppSettings.setNotificationsEnabled(this, granted)
            if (granted) WorkerScheduler.retryNow(this)
            reportDeviceState()
        }

    private val photoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            deliver(if (uri != null) arrayOf(uri) else null)
        }

    private val documentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                rememberReadPermission(uri)
                deliver(arrayOf(uri))
            } else {
                deliver(null)
            }
        }

    private val multipleDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach(::rememberReadPermission)
            deliver(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        AppSettings.ensureV171SecurityDefaults(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContentView(R.layout.activity_main)
        bindViews()
        offlineRepository = OfflineRepository(this)
        securityController = SecurityController(this)
        nativeBridge = ASDSNativeBridge(this, offlineRepository, securityController)

        applySystemBarInsets()
        configureNativeShell()
        configureWebView()
        cleanupOldCameraFiles()
        WorkerScheduler.schedule(this)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (savedInstanceState != null) webView.restoreState(savedInstanceState)
        else webView.loadUrl(intent.getStringExtra("asds_url")?.takeIf(::isInternalUrl) ?: PORTAL_URL)
    }

    override fun onResume() {
        super.onResume()
        if (::securityController.isInitialized) securityController.onResume()
        if (::offlineRepository.isInitialized) WorkerScheduler.retryNow(this)
    }

    override fun onPause() {
        if (::securityController.isInitialized) securityController.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("asds_url")?.takeIf(::isInternalUrl)?.let {
            if (::webView.isInitialized) webView.loadUrl(it)
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
            appHeader.setPadding(headerLeft, headerTop + bars.top, headerRight, headerBottom)
            bottomNavigation.setPadding(navLeft, navTop, navRight, navBottom + bars.bottom)
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
        headerMore.setOnClickListener { showQuickActions() }
        headerMore.setOnLongClickListener {
            startActivity(Intent(this, R1CaptureActivity::class.java))
            true
        }

        bottomNavigation.selectedItemId = R.id.navHome
        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navHome -> { navigateTo("index.php"); true }
                R.id.navAppointments -> { navigateTo("appointments.php"); true }
                R.id.navTime -> { navigateTo("time.php"); true }
                R.id.navTrips -> { navigateTo("trips.php"); true }
                R.id.navMore -> { navigateTo("more.php"); true }
                else -> false
            }
        }
    }

    private fun navigateTo(page: String, extraQuery: String = "") {
        val suffix = if (extraQuery.isBlank()) "" else "&$extraQuery"
        webView.loadUrl("$BASE_URL$page?stage=10$suffix")
    }

    private fun showQuickActions() {
        val options = arrayOf(
            getString(R.string.quick_time),
            getString(R.string.quick_appointment),
            getString(R.string.quick_protocol),
            getString(R.string.quick_expense),
            getString(R.string.quick_trip),
            getString(R.string.quick_task),
            getString(R.string.quick_customer),
            getString(R.string.quick_security),
            getString(R.string.quick_leadership),
            getString(R.string.quick_r1_time)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> navigateTo("time.php", "action=start")
                    1 -> navigateTo("appointments.php", "action=create")
                    2 -> navigateTo("protocols.php", "action=create")
                    3 -> navigateTo("expenses.php")
                    4 -> navigateTo("manual_trip.php")
                    5 -> navigateTo("tasks.php", "action=create")
                    6 -> navigateTo("customers.php")
                    7 -> showNativeSecurityMenu()
                    8 -> showNativeLeadershipMenu()
                    9 -> startActivity(Intent(this, R1CaptureActivity::class.java))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNativeLeadershipMenu() {
        val options = arrayOf(
            getString(R.string.leadership_cockpit),
            getString(R.string.leadership_staff),
            getString(R.string.leadership_approvals),
            getString(R.string.leadership_protocols),
            getString(R.string.leadership_calendar),
            getString(R.string.leadership_month)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.leadership_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> navigateTo("leadership.php")
                    1 -> navigateTo("staff.php")
                    2 -> navigateTo("approvals.php")
                    3 -> navigateTo("open_protocols.php")
                    4 -> navigateTo("team_calendar.php")
                    5 -> navigateTo("month_control.php")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNativeSecurityMenu() {
        val biometricOn = AppSettings.biometricEnabled(this)
        val notificationsOn = AppSettings.notificationsEnabled(this)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels += getString(if (biometricOn) R.string.security_disable_lock else R.string.security_enable_lock)
        actions += { securityController.setBiometricEnabled(!biometricOn) }
        labels += getString(R.string.security_lock_now)
        actions += { securityController.lockNow() }
        labels += getString(if (notificationsOn) R.string.security_disable_notifications else R.string.security_enable_notifications)
        actions += { if (notificationsOn) { AppSettings.setNotificationsEnabled(this, false); reportDeviceState() } else requestNotificationPermission() }
        labels += getString(R.string.security_sync_now)
        actions += { offlineRepository.resetQueueRetries(); WorkerScheduler.retryNow(this); Toast.makeText(this, R.string.security_sync_started, Toast.LENGTH_SHORT).show() }
        labels += getString(R.string.security_open_center)
        actions += { navigateTo("security.php") }
        if (BuildConfig.VERSION_CODE >= 100) { labels += getString(R.string.security_diagnostics); actions += { showNativeDiagnostics() } }
        AlertDialog.Builder(this).setTitle(R.string.security_title)
            .setMessage(getString(R.string.security_status, offlineRepository.draftCount(), offlineRepository.queueCount()))
            .setItems(labels.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun showNativeDiagnostics() {
        val diagnostic = ASDSNativeBridge(this, offlineRepository, securityController).getDiagnostics()
        AlertDialog.Builder(this).setTitle(R.string.diagnostics_title).setMessage(diagnostic)
            .setPositiveButton(R.string.diagnostics_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ASDS Mobile Diagnose", diagnostic))
                Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    fun openOfflineCapture() {
        try {
            val baseHtml = assets.open("offline_capture.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            // WebView uses the portal URL as base; a relative <script src> would
            // attempt a remote download and fail offline. Inject the packaged
            // JS asset directly instead. No server or file:// access required.
            val scriptTag = "<script src=\"offline_time_202.js\"></script>"
            val offlineFunctions = assets.open("offline_time_202.js")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(baseHtml.contains(scriptTag)) { "Offline time JS marker missing" }
            val html = baseHtml.replace(scriptTag, "<script>" + offlineFunctions + "</script>")
            currentPageLabel = "Offline-Erfassung"
            updateSubtitle()
            errorPanel.visibility = View.GONE
            webView.visibility = View.VISIBLE
            pageProgress.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            webView.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
        } catch (error: Exception) {
            Log.e(TAG, "Offline capture could not be opened", error)
            showLoadError()
            Toast.makeText(this, "Offline-Erfassung konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show()
        }
    }

    private fun cacheOfflineConfig() {
        val script = """
            (function(){try{fetch('https://portal.ihre-wegbegleiterin.ch/custom/asds_mobile/mobile/offline_config.php?stage=10',{credentials:'include',cache:'no-store'}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json()}).then(function(j){if(window.ASDSNative&&window.ASDSNative.setOfflineConfig)window.ASDSNative.setOfflineConfig(JSON.stringify(j))}).catch(function(){})}catch(e){}})();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        cancelPending()
        webView.removeJavascriptInterface("ASDSNative")
        webView.destroy()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.addJavascriptInterface(nativeBridge, "ASDSNative")
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
            userAgentString = "$userAgentString ASDSMobile/${BuildConfig.VERSION_NAME}"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
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
                injectOfflineSupport()
                updateNativeShell(url.orEmpty())
                if (BuildConfig.VERSION_CODE >= 103 && url.orEmpty().startsWith(BASE_URL)) cacheOfflineConfig()
                pageProgress.visibility = View.GONE
                swipeRefresh.isRefreshing = false
                webView.animate().alpha(1f).setDuration(160L).start()
                reportDeviceState()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    if (BuildConfig.VERSION_CODE >= 103) openOfflineCapture() else showLoadError()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (isInternalUrl(url)) return false
                return openExternalUrl(url)
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

                val acceptTypes = normalizeAcceptTypes(fileChooserParams?.acceptTypes)
                val capture = fileChooserParams?.isCaptureEnabled == true
                val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                val acceptsImage = acceptTypes.any { it == "*/*" || it.startsWith("image/") }
                val acceptsPdf = acceptTypes.any {
                    it == "*/*" || it == "application/*" || it == "application/pdf"
                }

                return try {
                    when {
                        capture && acceptsImage && !acceptsPdf -> launchCamera()
                        allowMultiple -> showSourceChooser(acceptTypes, true, acceptsImage)
                        acceptsImage && !acceptsPdf -> launchPhotoPicker()
                        acceptsPdf && !acceptsImage -> launchDocumentPicker(acceptTypes, false)
                        else -> showSourceChooser(acceptTypes, false, acceptsImage)
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

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startAuthenticatedDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun isInternalUrl(url: String): Boolean {
        return url.startsWith("https://portal.ihre-wegbegleiterin.ch/") ||
            url.startsWith("https://ihre-wegbegleiterin.ch/")
    }

    private fun openExternalUrl(url: String): Boolean {
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app for external URL", e)
            Toast.makeText(this, R.string.external_app_missing, Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "External URL failed", e)
            Toast.makeText(this, R.string.external_app_missing, Toast.LENGTH_LONG).show()
            true
        }
    }

    private fun normalizeAcceptTypes(rawTypes: Array<String>?): List<String> {
        val types = rawTypes
            ?.asSequence()
            ?.flatMap { it.split(',', ';').asSequence() }
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toMutableList()
            ?: mutableListOf()
        if (types.isEmpty()) types += "*/*"
        return types
    }

    private fun pickerMimeTypes(types: List<String>): Array<String> {
        if (types.any { it == "*/*" }) return arrayOf("*/*")
        return types.map {
            if (it == "application/*") "application/pdf" else it
        }.distinct().toTypedArray()
    }

    private fun showSourceChooser(types: List<String>, allowMultiple: Boolean, acceptsImage: Boolean) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        labels += getString(if (allowMultiple) R.string.option_multiple_files else R.string.option_files)
        actions += { launchDocumentPicker(types, allowMultiple) }
        if (acceptsImage) {
            labels += getString(R.string.option_camera)
            actions += { launchCamera() }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_source_title)
            .setItems(labels.toTypedArray()) { _, which ->
                actions.getOrNull(which)?.invoke() ?: deliver(null)
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

    private fun launchDocumentPicker(types: List<String>, allowMultiple: Boolean) {
        try {
            val mimeTypes = pickerMimeTypes(types)
            if (allowMultiple) multipleDocumentLauncher.launch(mimeTypes)
            else documentLauncher.launch(mimeTypes)
        } catch (e: Exception) {
            Log.e(TAG, "Document picker failed", e)
            Toast.makeText(this, R.string.error_no_picker, Toast.LENGTH_LONG).show()
            deliver(null)
        }
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("ASDS_Beleg_", ".jpg", dir)
            cameraOutputFile = file
            cameraOutputUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraLauncher.launch(cameraOutputUri!!)
        } catch (e: Exception) {
            Log.e(TAG, "Camera failed", e)
            cleanupCameraFile()
            Toast.makeText(this, R.string.error_no_camera, Toast.LENGTH_LONG).show()
            deliver(null)
        }
    }

    private fun rememberReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private fun startAuthenticatedDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setDescription(getString(R.string.app_name))
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) addRequestHeader("Cookie", cookies)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
            }
            val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
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
                        html,body{background:#f3f6fa!important;color:#17253a!important;overflow-x:hidden!important}
                        body>header{display:none!important}
                        main{max-width:920px!important;margin:0 auto!important;padding:14px 10px 30px!important}
                        .footer{padding-bottom:10px!important}
                        input[type=date],input[type=time],input[type=datetime-local],input[type=month],input[type=week]{color:#17253a!important;-webkit-text-fill-color:#17253a!important;background-color:#fff!important;color-scheme:light!important;opacity:1!important;font-weight:600!important}
                        input[type=date]::-webkit-datetime-edit,input[type=time]::-webkit-datetime-edit,input[type=datetime-local]::-webkit-datetime-edit,input[type=month]::-webkit-datetime-edit,input[type=week]::-webkit-datetime-edit,
                        input::-webkit-datetime-edit-fields-wrapper,input::-webkit-datetime-edit-text,input::-webkit-datetime-edit-month-field,input::-webkit-datetime-edit-day-field,input::-webkit-datetime-edit-year-field,input::-webkit-datetime-edit-hour-field,input::-webkit-datetime-edit-minute-field,input::-webkit-datetime-edit-ampm-field{color:#17253a!important;-webkit-text-fill-color:#17253a!important;opacity:1!important}
                        input[type=date]::-webkit-calendar-picker-indicator,input[type=datetime-local]::-webkit-calendar-picker-indicator,input[type=month]::-webkit-calendar-picker-indicator,input[type=week]::-webkit-calendar-picker-indicator,input[type=time]::-webkit-calendar-picker-indicator{opacity:1!important;filter:none!important}
                        @media(max-width:600px){.list{font-size:13px!important}.actions>.btn,.actions>button{flex:1 1 auto!important}}
                    `;
                    document.head.appendChild(style);
                }
                function enforceReadableDateTimeControls() {
                    document.querySelectorAll('input[type="date"],input[type="time"],input[type="datetime-local"],input[type="month"],input[type="week"]').forEach(function(el) {
                        el.style.setProperty('color', '#17253a', 'important');
                        el.style.setProperty('-webkit-text-fill-color', '#17253a', 'important');
                        el.style.setProperty('background-color', '#ffffff', 'important');
                        el.style.setProperty('color-scheme', 'light', 'important');
                        el.style.setProperty('opacity', '1', 'important');
                    });
                }
                enforceReadableDateTimeControls();
                if (!window.__asdsDateTimeObserver) {
                    window.__asdsDateTimeObserver = new MutationObserver(enforceReadableDateTimeControls);
                    window.__asdsDateTimeObserver.observe(document.documentElement, {childList:true, subtree:true, attributes:true, attributeFilter:['type','class','style']});
                }
                var serverHeader = document.querySelector('body > header');
                var user = serverHeader ? serverHeader.querySelector('.user') : null;
                return JSON.stringify({user:user ? user.textContent.trim() : '',title:document.title || ''});
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

    private fun injectOfflineSupport() {
        val script = """
            (function(){
                if(!window.ASDSNative || window.__asdsOfflineInstalled) return;
                window.__asdsOfflineInstalled=true;
                const bridge=window.ASDSNative;
                const page=location.pathname;
                const skipNames=new Set(['csrf','token','stage']);
                const allowedActions=new Set(['save','start_timer','stop_timer','manual_save','complete','reopen']);
                function label(){return (document.querySelector('h1')?.textContent || document.title || 'ASDS Eintrag').trim();}
                function formKey(form){
                    const action=form.querySelector('[name=action]')?.value || 'form';
                    const id=form.querySelector('[name=id]')?.value || new URL(location.href).searchParams.get('id') || 'new';
                    return page+'|'+action+'|'+id;
                }
                function snapshot(form){
                    const values=[];
                    form.querySelectorAll('input,select,textarea').forEach(el=>{
                        if(!el.name || skipNames.has(el.name) || el.type==='file' || el.type==='submit' || el.type==='button') return;
                        if((el.type==='checkbox'||el.type==='radio')&&!el.checked) return;
                        values.push({name:el.name,value:el.value,type:el.type});
                    });
                    return JSON.stringify(values);
                }
                function restore(form,key){
                    let raw='';try{raw=bridge.loadDraft(key)||''}catch(e){}
                    if(!raw)return;
                    let values=[];try{values=JSON.parse(raw)}catch(e){return;}
                    values.forEach(item=>{
                        const nodes=form.querySelectorAll('[name="'+CSS.escape(item.name)+'"]');
                        nodes.forEach(el=>{
                            if(el.type==='checkbox'||el.type==='radio')el.checked=String(el.value)===String(item.value);
                            else el.value=item.value;
                        });
                    });
                    const info=document.createElement('div');info.className='info';info.textContent='Lokaler Entwurf wurde wiederhergestellt.';form.prepend(info);
                }
                const success=document.querySelector('.success');
                if(success){try{bridge.clearDraftPrefix(page+'|')}catch(e){}}
                document.querySelectorAll('form').forEach(form=>{
                    const action=form.querySelector('[name=action]')?.value || '';
                    if(!allowedActions.has(action))return;
                    const key=formKey(form);restore(form,key);
                    let timer=0;const save=()=>{clearTimeout(timer);timer=setTimeout(()=>{try{bridge.saveDraft(key,label(),snapshot(form))}catch(e){}},350)};
                    form.addEventListener('input',save);form.addEventListener('change',save);
                    form.addEventListener('submit',event=>{
                        try{bridge.saveDraft(key,label(),snapshot(form))}catch(e){}
                        if(navigator.onLine)return;
                        event.preventDefault();
                        const hasFiles=Array.from(form.querySelectorAll('input[type=file]')).some(input=>input.files&&input.files.length);
                        if(hasFiles){alert('Der Text wurde lokal gespeichert. Dateianhänge können erst mit Internetverbindung übertragen werden.');return;}
                        const params=new URLSearchParams();
                        new FormData(form).forEach((value,name)=>{if(typeof value==='string')params.append(name,value)});
                        try{bridge.enqueueForm(form.action||location.href,(form.method||'POST').toUpperCase(),params.toString(),label(),key);alert('Keine Verbindung: Der Eintrag wurde in die Synchronisationswarteschlange gelegt.');}
                        catch(e){alert('Der Eintrag wurde als lokaler Entwurf gespeichert.');}
                    });
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun updateNativeShell(url: String) {
        val page = try { Uri.parse(url).lastPathSegment.orEmpty() } catch (_: Exception) { "" }
        val pairedArea = page != "pair.php" && page != "logout.php"
        bottomNavigation.visibility = if (pairedArea) View.VISIBLE else View.GONE
        headerMore.visibility = if (pairedArea) View.VISIBLE else View.INVISIBLE
        currentPageLabel = when (page) {
            "appointments.php" -> "Termine"
            "time.php" -> "Zeit"
            "trips.php", "trip.php" -> "Fahrten"
            "expenses.php" -> "Spesen & Ausgaben"
            "manual_trip.php" -> "Fahrt manuell"
            "protocols.php", "protocol_file.php" -> "Protokolle"
            "customers.php", "customer_file.php" -> "Kunden"
            "tasks.php" -> "Aufgaben"
            "security.php" -> "Sicherheit & Offline"
            "devices.php" -> "Geräteverwaltung"
            "leadership.php" -> "Leitung"
            "staff.php" -> "Mitarbeitende"
            "approvals.php" -> "Freigaben"
            "open_protocols.php" -> "Offene Protokolle"
            "team_calendar.php" -> "Teamtermine"
            "month_control.php" -> "Monatskontrolle"
            "more.php" -> "Mehr"
            "pair.php" -> "Gerät koppeln"
            "logout.php" -> "Abmeldung"
            else -> "Übersicht"
        }
        updateSubtitle()
        if (!pairedArea) return
        when (page) {
            "appointments.php" -> bottomNavigation.menu.findItem(R.id.navAppointments).isChecked = true
            "time.php" -> bottomNavigation.menu.findItem(R.id.navTime).isChecked = true
            "trips.php", "trip.php" -> bottomNavigation.menu.findItem(R.id.navTrips).isChecked = true
            "more.php", "expenses.php", "manual_trip.php", "protocols.php", "protocol_file.php",
            "customers.php", "customer_file.php", "tasks.php", "security.php", "devices.php",
            "leadership.php", "staff.php", "approvals.php", "open_protocols.php", "team_calendar.php", "month_control.php" ->
                bottomNavigation.menu.findItem(R.id.navMore).isChecked = true
            "index.php", "" -> bottomNavigation.menu.findItem(R.id.navHome).isChecked = true
        }
    }

    private fun updateSubtitle() {
        appSubtitle.text = if (currentUserName.isBlank()) currentPageLabel
        else "$currentUserName · $currentPageLabel"
    }

    private fun showLoadError() {
        pageProgress.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        webView.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
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

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            AppSettings.setNotificationsEnabled(this, true)
            WorkerScheduler.retryNow(this)
            reportDeviceState()
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun setAppContentLocked(locked: Boolean) {
        root.alpha = if (locked) 0f else 1f
        root.isEnabled = !locked
    }

    fun showSecurityMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    fun reportDeviceState() {
        if (::offlineRepository.isInitialized) WorkerScheduler.retryNow(this)
    }
}
