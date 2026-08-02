#!/usr/bin/env python3
"""Prepare the Android shell for ASDS Mobile 1.6.0.

This script is intentionally idempotent. It updates only the small native shell
parts required for the customer record and task modules while leaving the
working WebView, camera, file upload and download implementation untouched.
"""
from pathlib import Path

PATH = Path("app/src/main/java/ch/asds/mobile/MainActivity.kt")
text = PATH.read_text(encoding="utf-8")
original = text


def replace_once(old: str, new: str, label: str) -> None:
    global text
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source occurrence, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    'userAgentString = "$userAgentString ASDSMobile/1.5.0"',
    'userAgentString = "$userAgentString ASDSMobile/1.6.0"',
    "user agent",
)

replace_once(
    """            getString(R.string.quick_expense),
            getString(R.string.quick_trip)
""",
    """            getString(R.string.quick_expense),
            getString(R.string.quick_trip),
            getString(R.string.quick_task),
            getString(R.string.quick_customer)
""",
    "quick action labels",
)

replace_once(
    """                    3 -> navigateTo(\"expenses.php\")
                    4 -> navigateTo(\"manual_trip.php\")
""",
    """                    3 -> navigateTo(\"expenses.php\")
                    4 -> navigateTo(\"manual_trip.php\")
                    5 -> navigateTo(\"tasks.php\", \"action=create\")
                    6 -> navigateTo(\"customers.php\")
""",
    "quick action routes",
)

replace_once(
    """            \"protocols.php\", \"protocol_file.php\" -> \"Protokolle\"
            \"more.php\" -> \"Mehr\"
""",
    """            \"protocols.php\", \"protocol_file.php\" -> \"Protokolle\"
            \"customers.php\", \"customer_file.php\" -> \"Kunden\"
            \"tasks.php\" -> \"Aufgaben\"
            \"more.php\" -> \"Mehr\"
""",
    "page labels",
)

replace_once(
    """            \"more.php\", \"expenses.php\", \"manual_trip.php\", \"protocols.php\", \"protocol_file.php\" ->
                bottomNavigation.menu.findItem(R.id.navMore).isChecked = true
""",
    """            \"more.php\", \"expenses.php\", \"manual_trip.php\", \"protocols.php\", \"protocol_file.php\",
            \"customers.php\", \"customer_file.php\", \"tasks.php\" ->
                bottomNavigation.menu.findItem(R.id.navMore).isChecked = true
""",
    "more navigation pages",
)

if text != original:
    PATH.write_text(text, encoding="utf-8")
    print("MainActivity.kt updated for ASDS Mobile 1.6.0")
else:
    print("MainActivity.kt already prepared for ASDS Mobile 1.6.0")
