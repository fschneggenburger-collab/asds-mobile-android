package ch.asds.mobile

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class SecurityController(private val activity: MainActivity) {
    private var promptActive = false
    private var authenticatedAt = 0L

    fun onResume() {
        if (!AppSettings.biometricEnabled(activity)) {
            activity.setAppContentLocked(false)
            return
        }
        val elapsed = System.currentTimeMillis() - AppSettings.lastBackgroundAt(activity)
        val needsLock = AppSettings.forceLock(activity) || authenticatedAt == 0L || elapsed >= AppSettings.lockTimeoutMillis(activity)
        if (needsLock) authenticate(false)
    }

    fun onPause() {
        AppSettings.setLastBackgroundAt(activity, System.currentTimeMillis())
    }

    fun setBiometricEnabled(enabled: Boolean) {
        if (!enabled) {
            AppSettings.setBiometricEnabled(activity, false)
            AppSettings.setForceLock(activity, false)
            activity.setAppContentLocked(false)
            activity.showSecurityMessage("App-Sperre wurde deaktiviert.")
            activity.reportDeviceState()
            return
        }
        authenticate(true)
    }

    fun lockNow() {
        AppSettings.setForceLock(activity, true)
        authenticate(false)
    }

    private fun authenticate(enableAfterSuccess: Boolean) {
        if (promptActive) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val status = BiometricManager.from(activity).canAuthenticate(authenticators)
        if (status != BiometricManager.BIOMETRIC_SUCCESS) {
            AppSettings.setBiometricEnabled(activity, false)
            activity.showSecurityMessage("Biometrie oder Geräte-PIN ist auf diesem Gerät nicht verfügbar.")
            activity.setAppContentLocked(false)
            return
        }

        promptActive = true
        activity.setAppContentLocked(true)
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    promptActive = false
                    authenticatedAt = System.currentTimeMillis()
                    if (enableAfterSuccess) {
                        AppSettings.setBiometricEnabled(activity, true)
                        activity.showSecurityMessage("App-Sperre wurde aktiviert.")
                    }
                    AppSettings.setForceLock(activity, false)
                    activity.setAppContentLocked(false)
                    activity.reportDeviceState()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    promptActive = false
                    if (enableAfterSuccess) AppSettings.setBiometricEnabled(activity, false)
                    if (enableAfterSuccess) {
                        activity.setAppContentLocked(false)
                        activity.showSecurityMessage(errString.toString())
                    } else if (AppSettings.biometricEnabled(activity)) {
                        activity.finish()
                    } else {
                        activity.setAppContentLocked(false)
                    }
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("ASDS Mobile entsperren")
            .setSubtitle("Mit Fingerabdruck, Gesicht oder Geräte-PIN fortfahren")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }
}
