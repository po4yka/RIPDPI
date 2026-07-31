#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <artifact.tar.gz> <destination>" >&2
  exit 64
fi

archive="$1"
destination="$2"

test -f "$archive"
python3 - "$archive" <<'PY'
import sys
import tarfile
from pathlib import PurePosixPath

with tarfile.open(sys.argv[1], "r:gz") as archive:
    for member in archive.getmembers():
        path = PurePosixPath(member.name)
        if (
            path.is_absolute()
            or ".." in path.parts
            or member.issym()
            or member.islnk()
            or not (member.isfile() or member.isdir())
        ):
            raise SystemExit(f"unsafe pluggable transport artifact member: {member.name}")
PY

rm -rf "$destination"
mkdir -p "$destination"
tar --no-same-owner -xzf "$archive" -C "$destination"
