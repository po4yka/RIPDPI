"""Generate app/.../ui/theme/RipDpiSymbolPaths.kt from Material Symbols Outlined android XML files.

Fetch only the needed files from https://github.com/google/material-design-icons with a sparse clone
(`git clone --depth 1 --filter=blob:none --sparse ...`, then `git sparse-checkout set --no-cone` with
`/symbols/android/<name>/materialsymbolsoutlined/<name>_24px.xml` for every name in ICONS).

usage: python3 -I scripts/gen_material_symbols.py <checkout> <commit> app/src/main/kotlin/com/poyka/ripdpi/ui/theme/RipDpiSymbolPaths.kt
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "{http://schemas.android.com/apk/res/android}"

# Kotlin constant name -> Material Symbols name, or "<name>/<file stem without _24px>" for a file variant
ICONS = {
    "Add": "add", "Archive": "archive", "ArrowBack": "arrow_back", "Article": "article",
    "Cancel": "cancel", "Check": "check", "Close": "close", "ContentCopy": "content_copy",
    "ContentPaste": "content_paste", "Delete": "delete", "Dns": "dns", "DriveFileMove": "drive_file_move",
    "Edit": "edit", "FileOpen": "file_open", "History": "history", "Home": "home", "Image": "image",
    "Info": "info", "KeyboardArrowDown": "keyboard_arrow_down",
    "KeyboardArrowRight": "keyboard_arrow_right", "KeyboardArrowUp": "keyboard_arrow_up",
    "LocationOn": "location_on", "Lock": "lock", "LockOpen": "lock_open", "MoreVert": "more_vert",
    "NetworkCheck": "network_check", "NoPhotography": "no_photography", "OpenInNew": "open_in_new",
    "PhotoCamera": "photo_camera", "Public": "public", "QrCodeScanner": "qr_code_scanner",
    "Refresh": "refresh", "Remove": "remove", "Science": "science", "Search": "search",
    "Settings": "settings", "SettingsBackupRestore": "settings_backup_restore", "Share": "share",
    "Shield": "shield", "Star": "star", "StarFilled": "star/star_fill1", "TaskAlt": "task_alt", "Timer": "timer", "Tune": "tune",
    "Videocam": "videocam", "Visibility": "visibility", "VpnKey": "vpn_key", "Warning": "warning",
}

root, commit, out = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
lines = [
    "package com.poyka.ripdpi.ui.theme",
    "",
    f"// Generated from google/material-design-icons {commit[:12]}, Material Symbols Outlined, weight 400,",
    "// grade 0, 24dp optical size (Apache License 2.0). Regenerate instead of editing paths by hand.",
    "@Suppress(\"MaxLineLength\")",
    "internal object RipDpiSymbolPaths {",
]
for const, name in sorted(ICONS.items()):
    folder, _, stem = name.partition("/")
    xml = root / f"symbols/android/{folder}/materialsymbolsoutlined/{stem or folder}_24px.xml"
    vector = ET.parse(xml).getroot()
    assert vector.get(f"{ANDROID}viewportWidth") == "960", name
    assert vector.get(f"{ANDROID}viewportHeight") == "960", name
    paths = [p.get(f"{ANDROID}pathData") for p in vector.iter("path")]
    assert len(paths) == 1 and paths[0], f"{name}: expected one path, got {len(paths)}"
    assert not list(vector.iter("group")), f"{name}: groups are not supported"
    lines.append(f'    const val {const}: String = "{paths[0]}"')
lines.append("}")
out.write_text("\n".join(lines) + "\n")
print(f"wrote {len(ICONS)} icons to {out}")
