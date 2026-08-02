#!/usr/bin/env python3
from pathlib import Path

path = Path("app/build.gradle.kts")
text = path.read_text(encoding="utf-8")
if "versionCode = 81" not in text:
    if text.count("versionCode = 71") != 1:
        raise SystemExit("Expected versionCode 71 after the 1.7.1 correction step")
    text = text.replace("versionCode = 71", "versionCode = 81", 1)
    path.write_text(text, encoding="utf-8")
print("ASDS Mobile 1.7.1 uses versionCode 81 for update compatibility")
