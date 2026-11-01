package ch.asds.mobile

import android.content.Context
import java.util.UUID

object AppSettings {
    private const val PREFS = "asds_app_settings"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun biometricEnabled(context: Context): Boolean = prefs(context).getBoolean("biometric_enabled", true)
    fun setBiometricEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("biometric_enabled", enabled).apply()
    fun notificationsEnabled(context: Context): Boolean = prefs(context).getBoolean("notifications_enabled", false)
    fun setNotificationsEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("notifications_enabled", enabled).apply()
    fun forceLock(context: Context): Boolean = prefs(context).getBoolean("force_lock", false)
    fun setForceLock(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("force_lock", enabled).apply()
    fun lastBackgroundAt(context: Context): Long = prefs(context).getLong("last_background_at", 0L)
    fun setLastBackgroundAt(context: Context, value: Long) = prefs(context).edit().putLong("last_background_at", value).apply()
    fun lockTimeoutMillis(context: Context): Long = prefs(context).getLong("lock_timeout_ms", 60_000L)

    fun ensureV171SecurityDefaults(context: Context) {
        val preferences = prefs(context)
        if (!preferences.getBoolean("v171_security_defaults_applied", false)) {
            preferences.edit().putBoolean("biometric_enabled", true).putBoolean("v171_security_defaults_applied", true).apply()
        }
    }

    fun instanceId(context: Context): String {
        val preferences = prefs(context)
        val existing = preferences.getString("instance_id", "").orEmpty()
        if (existing.isNotBlank()) return existing
        val created = UUID.randomUUID().toString()
        preferences.edit().putString("instance_id", created).apply()
        return created
    }

    fun setLastServerState(context: Context, value: String) = prefs(context).edit().putString("last_server_state", value.take(16000)).apply()
    fun lastServerState(context: Context): String = prefs(context).getString("last_server_state", "{}").orEmpty()
    fun setCompatibilityState(context: Context, value: String) = prefs(context).edit().putString("compatibility_state", value.take(1000)).apply()
    fun compatibilityState(context: Context): String = prefs(context).getString("compatibility_state", "").orEmpty()
}
