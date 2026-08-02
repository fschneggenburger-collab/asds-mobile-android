package ch.asds.mobile

import android.content.Context

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
}
