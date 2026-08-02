#!/usr/bin/env python3
"""Apply the visible and testable ASDS Mobile 1.7.1 corrections.

The 1.7.0 generator creates the complete offline/security implementation. This
script makes the security controls visible in the native app, enables the app
lock by default, and forces readable light date/time controls.
"""
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


build = Path("app/build.gradle.kts")
replace_once(build, 'versionCode = 70', 'versionCode = 71', 'version code')
replace_once(build, 'versionName = "1.7.0"', 'versionName = "1.7.1"', 'version name')

settings = Path("app/src/main/java/ch/asds/mobile/AppSettings.kt")
replace_once(
    settings,
    'getBoolean("biometric_enabled", false)',
    'getBoolean("biometric_enabled", true)',
    'default biometric lock',
)

main = Path("app/src/main/java/ch/asds/mobile/MainActivity.kt")
replace_once(
    main,
    'import androidx.appcompat.app.AppCompatActivity\n',
    'import androidx.appcompat.app.AppCompatActivity\nimport androidx.appcompat.app.AppCompatDelegate\n',
    'AppCompatDelegate import',
)
replace_once(
    main,
    'import androidx.swiperefreshlayout.widget.SwipeRefreshLayout\n',
    'import androidx.swiperefreshlayout.widget.SwipeRefreshLayout\nimport androidx.webkit.WebSettingsCompat\nimport androidx.webkit.WebViewFeature\n',
    'WebView darkening imports',
)
replace_once(
    main,
    '    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)',
    '    override fun onCreate(savedInstanceState: Bundle?) {\n        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)\n        super.onCreate(savedInstanceState)',
    'force light mode',
)
replace_once(
    main,
    '''            getString(R.string.quick_task),
            getString(R.string.quick_customer)
''',
    '''            getString(R.string.quick_task),
            getString(R.string.quick_customer),
            getString(R.string.quick_security)
''',
    'security quick action label',
)
replace_once(
    main,
    '''                    5 -> navigateTo("tasks.php", "action=create")
                    6 -> navigateTo("customers.php")
''',
    '''                    5 -> navigateTo("tasks.php", "action=create")
                    6 -> navigateTo("customers.php")
                    7 -> showNativeSecurityMenu()
''',
    'security quick action route',
)
replace_once(
    main,
    '    override fun onSaveInstanceState(outState: Bundle) {',
    '''    private fun showNativeSecurityMenu() {
        val biometricOn = AppSettings.biometricEnabled(this)
        val notificationsOn = AppSettings.notificationsEnabled(this)
        val options = arrayOf(
            getString(if (biometricOn) R.string.security_disable_lock else R.string.security_enable_lock),
            getString(R.string.security_lock_now),
            getString(if (notificationsOn) R.string.security_disable_notifications else R.string.security_enable_notifications),
            getString(R.string.security_sync_now),
            getString(R.string.security_open_center)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.security_title)
            .setMessage(getString(R.string.security_status, offlineRepository.draftCount(), offlineRepository.queueCount()))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> securityController.setBiometricEnabled(!biometricOn)
                    1 -> securityController.lockNow()
                    2 -> {
                        if (notificationsOn) {
                            AppSettings.setNotificationsEnabled(this, false)
                            reportDeviceState()
                        } else {
                            requestNotificationPermission()
                        }
                    }
                    3 -> {
                        WorkerScheduler.retryNow(this)
                        Toast.makeText(this, R.string.security_sync_started, Toast.LENGTH_SHORT).show()
                    }
                    4 -> navigateTo("security.php")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {''',
    'native security menu',
)
replace_once(
    main,
    '''            userAgentString = "$userAgentString ASDSMobile/1.7.0"
        }

        webView.webViewClient''',
    '''            userAgentString = "$userAgentString ASDSMobile/1.7.1"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }

        webView.webViewClient''',
    'user agent and WebView darkening',
)
replace_once(
    main,
    '''                        input[type=date],input[type=time],input[type=datetime-local],input[type=month],input[type=week]{color:#17253a!important;-webkit-text-fill-color:#17253a!important;background:#fff!important;color-scheme:light!important}
                        input[type=date]::-webkit-datetime-edit,input[type=time]::-webkit-datetime-edit,input[type=datetime-local]::-webkit-datetime-edit{color:#17253a!important}
''',
    '''                        input[type=date],input[type=time],input[type=datetime-local],input[type=month],input[type=week]{color:#17253a!important;-webkit-text-fill-color:#17253a!important;background-color:#fff!important;color-scheme:light!important;opacity:1!important;font-weight:600!important}
                        input[type=date]::-webkit-datetime-edit,input[type=time]::-webkit-datetime-edit,input[type=datetime-local]::-webkit-datetime-edit,input[type=month]::-webkit-datetime-edit,input[type=week]::-webkit-datetime-edit,
                        input::-webkit-datetime-edit-fields-wrapper,input::-webkit-datetime-edit-text,input::-webkit-datetime-edit-month-field,input::-webkit-datetime-edit-day-field,input::-webkit-datetime-edit-year-field,input::-webkit-datetime-edit-hour-field,input::-webkit-datetime-edit-minute-field,input::-webkit-datetime-edit-ampm-field{color:#17253a!important;-webkit-text-fill-color:#17253a!important;opacity:1!important}
                        input[type=date]::-webkit-calendar-picker-indicator,input[type=datetime-local]::-webkit-calendar-picker-indicator,input[type=month]::-webkit-calendar-picker-indicator,input[type=week]::-webkit-calendar-picker-indicator,input[type=time]::-webkit-calendar-picker-indicator{opacity:1!important;filter:none!important}
''',
    'date and time CSS',
)
replace_once(
    main,
    "                var serverHeader = document.querySelector('body > header');",
    '''                function enforceReadableDateTimeControls() {
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
                var serverHeader = document.querySelector('body > header');''',
    'date and time DOM enforcement',
)
print("ASDS Mobile Android 1.7.1 corrections applied")
