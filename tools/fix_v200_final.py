#!/usr/bin/env python3
"""Finalize ASDS Mobile Android 2.0.0 on top of the tested 1.9.5 release candidate."""
from pathlib import Path


def replace_once(path, old, new, label):
    p = Path(path)
    text = p.read_text(encoding='utf-8')
    if new in text:
        return
    if text.count(old) != 1:
        raise SystemExit(f'{label}: expected one match, found {text.count(old)}')
    p.write_text(text.replace(old, new, 1), encoding='utf-8')

replace_once('app/build.gradle.kts', 'versionCode = 105', 'versionCode = 200', 'version code')
replace_once('app/build.gradle.kts', 'versionName = "1.9.5"', 'versionName = "2.0.0"', 'version name')
replace_once('app/src/main/java/ch/asds/mobile/MainActivity.kt', 'ASDSMobile/1.9.5', 'ASDSMobile/2.0.0', 'user agent')

for rel in [
    'app/src/main/java/ch/asds/mobile/ASDSNativeBridge.kt',
    'app/src/main/java/ch/asds/mobile/ServerClient.kt',
]:
    p = Path(rel)
    text = p.read_text(encoding='utf-8')
    old = '.put("release_candidate_v2").put("configured_trip_labels")'
    new = '.put("v2_final").put("configured_trip_labels").put("suite_first_trip_view")'
    if new not in text:
        if old not in text:
            raise SystemExit(f'1.9.5 capability marker not found in {rel}')
        text = text.replace(old, new, 1)
    p.write_text(text, encoding='utf-8')

print('ASDS Mobile Android 2.0.0 final applied (versionCode 200)')
