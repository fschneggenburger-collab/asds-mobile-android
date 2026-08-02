#!/usr/bin/env python3
"""Reconstruct and run the ASDS Mobile 1.7/1.8 source generator."""
from pathlib import Path
import base64
import gzip
import subprocess
import sys

if len(sys.argv) != 2 or sys.argv[1] not in {"1.7.0", "1.8.0"}:
    raise SystemExit("Usage: python3 tools/prepare_mobile.py 1.7.0|1.8.0")

parts_dir = Path("tools/payload_v170_v180")
parts = sorted(parts_dir.glob("part*.b64"))
if not parts:
    raise SystemExit("No payload parts found")

payload = "".join(path.read_text(encoding="utf-8").strip() for path in parts)
source = gzip.decompress(base64.b64decode(payload))
temp = Path("/tmp/asds_prepare_v170_v180.py")
temp.write_bytes(source)
subprocess.run([sys.executable, str(temp), sys.argv[1]], check=True)
