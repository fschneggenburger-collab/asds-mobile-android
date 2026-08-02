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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ASDSMobile"
        private const val PORTAL_URL =
            "https://portal.ihre-wegbegleiterin.ch/custom/asds_mobile/mobile/index.php?stage=10"
    }

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null
    private var cameraOutputFile: File? = null

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
                    // Some providers do not offer persistable grants.
                }
                deliver(arrayOf(uri))
            } else {
                deliver(null)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        configureWebView()
        cleanupOldCameraFiles()

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
            userAgentString = "$userAgentString ASDSMobile/1.2.1"
        }

        webView.webViewClient = object : WebViewClient() {
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
        cameraOutputUri = null
        try {
            callback?.onReceiveValue(null)
        } catch (_: Exception) {
        }
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
