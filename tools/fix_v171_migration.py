#!/usr/bin/env python3
from pathlib import Path

settings = Path("app/src/main/java/ch/asds/mobile/AppSettings.kt")
text = settings.read_text(encoding="utf-8")
method = '''
    fun ensureV171SecurityDefaults(context: Context) {
        val preferences = prefs(context)
        if (!preferences.getBoolean("v171_security_defaults_applied", false)) {
            preferences.edit()
                .putBoolean("biometric_enabled", true)
                .putBoolean("v171_security_defaults_applied", true)
                .apply()
        }
    }
'''
if "fun ensureV171SecurityDefaults" not in text:
    if not text.rstrip().endswith("}"):
        raise SystemExit("AppSettings.kt has no object closing brace")
    text = text.rstrip()[:-1] + method + "}\n"
    settings.write_text(text, encoding="utf-8")

main = Path("app/src/main/java/ch/asds/mobile/MainActivity.kt")
text = main.read_text(encoding="utf-8")
old = '''        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
'''
new = '''        super.onCreate(savedInstanceState)
        AppSettings.ensureV171SecurityDefaults(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
'''
if new not in text:
    if text.count(old) != 1:
        raise SystemExit("MainActivity migration insertion point not found")
    main.write_text(text.replace(old, new, 1), encoding="utf-8")
print("ASDS Mobile 1.7.1 security defaults migration applied")
