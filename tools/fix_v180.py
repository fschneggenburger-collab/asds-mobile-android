#!/usr/bin/env python3
"""Build ASDS Mobile 1.8.0 on top of the verified 1.7.1 security release."""
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
replace_once(build, "versionCode = 81", "versionCode = 90", "version code")
replace_once(build, 'versionName = "1.7.1"', 'versionName = "1.8.0"', "version name")

strings = Path("app/src/main/res/values/strings.xml")
text = strings.read_text(encoding="utf-8")
leadership_strings = '''
    <string name="quick_leadership">Leitung &amp; Administration</string>
    <string name="leadership_title">Leitung &amp; Administration</string>
    <string name="leadership_cockpit">Leitungs-Cockpit</string>
    <string name="leadership_staff">Mitarbeitendenübersicht</string>
    <string name="leadership_approvals">Freigaben</string>
    <string name="leadership_protocols">Offene Protokolle</string>
    <string name="leadership_calendar">Teamtermine</string>
    <string name="leadership_month">Monatskontrolle</string>
'''
if 'name="quick_leadership"' not in text:
    if text.count("</resources>") != 1:
        raise SystemExit("strings.xml closing tag not found")
    strings.write_text(text.replace("</resources>", leadership_strings + "</resources>", 1), encoding="utf-8")

main = Path("app/src/main/java/ch/asds/mobile/MainActivity.kt")
replace_once(
    main,
    'userAgentString = "$userAgentString ASDSMobile/1.7.1"',
    'userAgentString = "$userAgentString ASDSMobile/1.8.0"',
    "user agent",
)
replace_once(
    main,
    '''            getString(R.string.quick_customer),
            getString(R.string.quick_security)
''',
    '''            getString(R.string.quick_customer),
            getString(R.string.quick_security),
            getString(R.string.quick_leadership)
''',
    "leadership quick action label",
)
replace_once(
    main,
    '''                    6 -> navigateTo("customers.php")
                    7 -> showNativeSecurityMenu()
''',
    '''                    6 -> navigateTo("customers.php")
                    7 -> showNativeSecurityMenu()
                    8 -> showNativeLeadershipMenu()
''',
    "leadership quick action route",
)
replace_once(
    main,
    '    private fun showNativeSecurityMenu() {',
    '''    private fun showNativeLeadershipMenu() {
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

    private fun showNativeSecurityMenu() {''',
    "native leadership menu",
)

text = main.read_text(encoding="utf-8")
if '"leadership.php" -> "Leitung"' not in text:
    old = '''            "security.php" -> "Sicherheit & Offline"
            "devices.php" -> "Geräteverwaltung"
            "more.php" -> "Mehr"
'''
    new = '''            "security.php" -> "Sicherheit & Offline"
            "devices.php" -> "Geräteverwaltung"
            "leadership.php" -> "Leitung"
            "staff.php" -> "Mitarbeitende"
            "approvals.php" -> "Freigaben"
            "open_protocols.php" -> "Offene Protokolle"
            "team_calendar.php" -> "Teamtermine"
            "month_control.php" -> "Monatskontrolle"
            "more.php" -> "Mehr"
'''
    if text.count(old) != 1:
        raise SystemExit("leadership page-label insertion point not found")
    text = text.replace(old, new, 1)
elif '"open_protocols.php" -> "Offene Protokolle"' not in text:
    text = text.replace('            "approvals.php" -> "Freigaben"\n', '            "approvals.php" -> "Freigaben"\n            "open_protocols.php" -> "Offene Protokolle"\n', 1)

if '"open_protocols.php"' not in text.split('bottomNavigation.menu.findItem(R.id.navMore).isChecked = true')[0].split('when (page) {')[-1]:
    old = '''            "more.php", "expenses.php", "manual_trip.php", "protocols.php", "protocol_file.php",
            "customers.php", "customer_file.php", "tasks.php", "security.php", "devices.php" ->
'''
    new = '''            "more.php", "expenses.php", "manual_trip.php", "protocols.php", "protocol_file.php",
            "customers.php", "customer_file.php", "tasks.php", "security.php", "devices.php",
            "leadership.php", "staff.php", "approvals.php", "open_protocols.php", "team_calendar.php", "month_control.php" ->
'''
    if old in text:
        text = text.replace(old, new, 1)
    else:
        old2 = '''            "leadership.php", "staff.php", "approvals.php", "team_calendar.php", "month_control.php" ->
'''
        new2 = '''            "leadership.php", "staff.php", "approvals.php", "open_protocols.php", "team_calendar.php", "month_control.php" ->
'''
        if old2 not in text:
            raise SystemExit("leadership navigation insertion point not found")
        text = text.replace(old2, new2, 1)
main.write_text(text, encoding="utf-8")
print("ASDS Mobile Android 1.8.0 leadership functions applied")
