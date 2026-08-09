#!/usr/bin/env python3
"""Apply ASDS Mobile Android 1.9.4 on top of generated 1.9.3 source."""
from pathlib import Path


def replace_once(path, old, new, label):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if new in text:
        return
    if text.count(old) != 1:
        raise SystemExit(f'{label}: expected one match, found {text.count(old)}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

replace_once('app/build.gradle.kts', 'versionCode = 103', 'versionCode = 104', 'version code')
replace_once('app/build.gradle.kts', 'versionName = "1.9.3"', 'versionName = "1.9.4"', 'version name')
replace_once('app/src/main/java/ch/asds/mobile/MainActivity.kt', 'ASDSMobile/1.9.3', 'ASDSMobile/1.9.4', 'user agent')

for rel in [
    'app/src/main/java/ch/asds/mobile/ASDSNativeBridge.kt',
    'app/src/main/java/ch/asds/mobile/ServerClient.kt',
]:
    p = Path(rel)
    text = p.read_text(encoding='utf-8')
    old = 'if (BuildConfig.VERSION_CODE >= 103) '
    # Add the new capability beside the 1.9.3 feature group without disturbing its behavior.
    if 'more_v2' not in text:
        marker = 'put("feedback").put("announcements").put("offline_capture_v1")'
        if marker not in text:
            raise SystemExit(f'1.9.3 capability marker not found in {rel}')
        text = text.replace(marker, marker + '.put("more_v2")', 1)
    p.write_text(text, encoding='utf-8')

print('ASDS Mobile Android 1.9.4 UI polish applied (versionCode 104)')
