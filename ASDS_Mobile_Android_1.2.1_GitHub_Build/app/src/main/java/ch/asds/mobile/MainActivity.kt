package ch.asds.mobile

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * ASDS Mobile – thin WebView shell for the ASDS Mobility portal.
 *
 * File inputs are handled natively:
 *  - image/* + capture → system camera (TakePicture / FileProvider)
 *  - image/* only      → Android Photo Picker (PickVisualMedia)
 *  - image/* + pdf / mixed → native chooser: Photos / Camera / PDF
 *  - application/pdf   → OpenDocument
 *
 * No broad media or camera permissions are requested.
 * ValueCallback is answered exactly once; a previous open callback is cancelled first.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ASDSMobile"
        private const val PORTAL_URL =
            "https://portal.ihre-wegbegleiterin.ch/custom/asds_mobile/mobile/index.php?stage=10"
    }

    private lateinit var webView: WebView

    /** Pending WebView file-chooser callback (must be answered exactly once). */
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    /** URI written by the system camera app (TakePicture). */
    private var cameraOutputUri: Uri? = null
    private var cameraOutputFile: File? = null

    // ---------- Activity Result launchers ----------

    private val photoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest> =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            deliverCallback(if (uri != null) arrayOf(uri) else null)
        }

    private val cameraLauncher: ActivityResultLauncher<Uri> =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = cameraOutputUri
            if (success && uri != null) {
                deliverCallback(arrayOf(uri))
            } else {
                // Capture cancelled or failed – delete empty placeholder if present
                cleanupCameraFile()
                deliverCallback(null)
            }
        }

    private val pdfLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    // Persist read permission for the lifetime of this process if possible
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {
                    // Some providers do not support persistable grants – ignore
                }
                deliverCallback(arrayOf(uri))
            } else {
                deliverCallback(null)
            }
        }

    // ---------- Lifecycle ----------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        configureWebView()

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

        cleanupOldCameraFiles()

        // Restore previous WebView state (cookies / DOM storage survive process death via CookieManager)
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(PORTAL_URL)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        // Ensure no dangling callback holds a reference after activity death
        cancelPendingCallback()
        webView.destroy()
        super.onDestroy()
    }

    // ---------- WebView setup ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "$userAgentString ASDSMobile/1.2.1"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                // Keep navigation inside the ASDS portal / same host
                return if (url.startsWith("https://portal.ihre-wegbegleiterin.ch/") ||
                    url.startsWith("https://ihre-wegbegleiterin.ch/")
                ) {
                    false
                } else {
                    // External links → system browser
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: ActivityNotFoundException) {
                        Log.w(TAG, "No browser for $url", e)
                    }
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
                // Always cancel any previous pending callback first
                cancelPendingCallback()
                if (filePathCallback == null) return false
                fileCallback = filePathCallback

                // WebView may return mixed types either as separate array items or as one
                // comma/semicolon separated string, for example "image/*,application/pdf".
                val acceptTypes = fileChooserParams?.acceptTypes
                    ?.asSequence()
                    ?.flatMap { it.split(',', ';').asSequence() }
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?.toList()
                    ?.toTypedArray()
                    ?: emptyArray()
                val captureEnabled = fileChooserParams?.isCaptureEnabled == true
                val acceptsImage = acceptTypes.isEmpty() ||
                    acceptTypes.any { it == "*/*" || it.startsWith("image/") }
                val acceptsPdf = acceptTypes.any {
                    it.equals("application/pdf", ignoreCase = true) ||
                        it.equals("application/*", ignoreCase = true) ||
                        it == "*/*"
                }

                return try {
                    when {
                        // Pure camera capture (receipt_camera: accept=image/* capture=environment)
                        captureEnabled && acceptsImage && !acceptsPdf -> {
                            launchCamera()
                            true
                        }
                        // Mixed image + PDF (receipt_file)
                        acceptsImage && acceptsPdf -> {
                            showSourceChooser(acceptsImage = true, acceptsPdf = true)
                            true
                        }
                        // Image only, no capture → Photo Picker
                        acceptsImage && !acceptsPdf -> {
                            launchPhotoPicker()
                            true
                        }
                        // PDF only
                        acceptsPdf -> {
                            launchPdfPicker()
                            true
                        }
                        else -> {
                            // Fallback: show chooser with all options
                            showSourceChooser(acceptsImage = true, acceptsPdf = true)
                            true
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onShowFileChooser failed", e)
                    toast(getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
                    deliverCallback(null)
                    true
                }
            }
        }
    }

    // ---------- File source selection ----------

    private fun showSourceChooser(acceptsImage: Boolean, acceptsPdf: Boolean) {
        val options = mutableListOf<String>()
        if (acceptsImage) {
            options += getString(R.string.option_photo)
            options += getString(R.string.option_camera)
        }
        if (acceptsPdf) {
            options += getString(R.string.option_pdf)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_source_title)
            .setItems(options.toTypedArray()) { _, which ->
                val label = options[which]
                when (label) {
                    getString(R.string.option_photo) -> launchPhotoPicker()
                    getString(R.string.option_camera) -> launchCamera()
                    getString(R.string.option_pdf) -> launchPdfPicker()
                    else -> deliverCallback(null)
                }
            }
            .setOnCancelListener { deliverCallback(null) }
            .setNegativeButton(R.string.cancel) { d, _ ->
                d.dismiss()
                deliverCallback(null)
            }
            .show()
    }

    private fun launchPhotoPicker() {
        try {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Photo Picker not available", e)
            toast(R.string.error_no_picker)
            deliverCallback(null)
        } catch (e: Exception) {
            Log.e(TAG, "Photo Picker launch failed", e)
            toast(getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            deliverCallback(null)
        }
    }

    private fun launchCamera() {
        try {
            val file = createCameraFile()
            if (file == null) {
                toast(R.string.error_create_file)
                deliverCallback(null)
                return
            }
            cameraOutputFile = file
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            cameraOutputUri = uri
            // TakePicture contract grants read/write to the camera app automatically
            cameraLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Camera app not available", e)
            cleanupCameraFile()
            toast(R.string.error_no_camera)
            deliverCallback(null)
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera SecurityException", e)
            cleanupCameraFile()
            toast(getString(R.string.error_generic, e.message ?: "SecurityException"))
            deliverCallback(null)
        } catch (e: Exception) {
            Log.e(TAG, "Camera launch failed", e)
            cleanupCameraFile()
            toast(getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            deliverCallback(null)
        }
    }

    private fun launchPdfPicker() {
        try {
            pdfLauncher.launch(arrayOf("application/pdf"))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "OpenDocument not available", e)
            toast(R.string.error_no_pdf)
            deliverCallback(null)
        } catch (e: Exception) {
            Log.e(TAG, "PDF picker failed", e)
            toast(getString(R.string.error_generic, e.message ?: e.javaClass.simpleName))
            deliverCallback(null)
        }
    }

    // ---------- Callback helpers ----------

    /**
     * Deliver result to WebView exactly once, then clear the pending callback.
     */
    private fun deliverCallback(uris: Array<Uri>?) {
        val cb = fileCallback
        fileCallback = null
        cameraOutputUri = null
        try {
            cb?.onReceiveValue(uris)
        } catch (e: Exception) {
            Log.e(TAG, "onReceiveValue failed", e)
        }
    }

    /** Cancel any open callback with null (required before starting a new chooser). */
    private fun cancelPendingCallback() {
        val cb = fileCallback
        fileCallback = null
        cameraOutputUri = null
        try {
            cb?.onReceiveValue(null)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun createCameraFile(): File? {
        return try {
            val dir = File(cacheDir, "camera").also { it.mkdirs() }
            File.createTempFile("ASDS_Beleg_", ".jpg", dir)
        } catch (e: Exception) {
            Log.e(TAG, "createCameraFile failed", e)
            null
        }
    }


    /** Remove abandoned camera placeholders from earlier sessions without touching fresh uploads. */
    private fun cleanupOldCameraFiles() {
        try {
            val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            File(cacheDir, "camera").listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupOldCameraFiles failed", e)
        }
    }

    private fun cleanupCameraFile() {
        try {
            cameraOutputFile?.delete()
        } catch (_: Exception) {
            // ignore
        }
        cameraOutputFile = null
        cameraOutputUri = null
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
    }
}
