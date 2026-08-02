#!/usr/bin/env python3
"""Extract private ordinary observations from a bounded instrumentation result."""

from __future__ import annotations

import argparse
import base64
import binascii
import json
import os
import stat
import sys
from pathlib import Path


RESULT_KEY = "ripdpi.ordinaryObservationsBase64"
RESULT_PREFIX = f"INSTRUMENTATION_RESULT: {RESULT_KEY}=".encode()
MAX_TRANSCRIPT_BYTES = 4 * 1024 * 1024
MAX_OBSERVATIONS_BYTES = 512 * 1024
MAX_ENCODED_BYTES = ((MAX_OBSERVATIONS_BYTES + 2) // 3) * 4


class ExtractionError(ValueError):
    pass


def _read_pinned_private_file(path: Path) -> bytes:
    flags = os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise ExtractionError("instrumentation transcript is not readable") from error
    try:
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_nlink != 1
            or stat.S_IMODE(before.st_mode) != 0o600
            or not 0 < before.st_size <= MAX_TRANSCRIPT_BYTES
        ):
            raise ExtractionError(
                "instrumentation transcript must be a bounded private single-link file"
            )
        payload = bytearray()
        while chunk := os.read(descriptor, 64 * 1024):
            payload.extend(chunk)
            if len(payload) > MAX_TRANSCRIPT_BYTES:
                raise ExtractionError("instrumentation transcript exceeds its bound")
        after = os.fstat(descriptor)
        if (
            before.st_dev,
            before.st_ino,
            before.st_mode,
            before.st_nlink,
            before.st_size,
            before.st_mtime_ns,
        ) != (
            after.st_dev,
            after.st_ino,
            after.st_mode,
            after.st_nlink,
            after.st_size,
            after.st_mtime_ns,
        ):
            raise ExtractionError("instrumentation transcript changed while reading")
        return bytes(payload)
    finally:
        os.close(descriptor)


def extract_observations(transcript: bytes) -> bytes:
    matches = [
        line.removeprefix(RESULT_PREFIX)
        for line in transcript.splitlines()
        if line.startswith(RESULT_PREFIX)
    ]
    if len(matches) != 1:
        raise ExtractionError(
            "instrumentation transcript must contain exactly one observation result"
        )
    encoded = matches[0]
    if not 0 < len(encoded) <= MAX_ENCODED_BYTES:
        raise ExtractionError("encoded observations are outside their bound")
    try:
        payload = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ExtractionError("instrumentation observations are not strict Base64") from error
    if not 0 < len(payload) <= MAX_OBSERVATIONS_BYTES or not payload.endswith(b"\n"):
        raise ExtractionError("decoded observations are outside their bound")
    try:
        value = json.loads(payload)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ExtractionError("decoded observations are not valid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise ExtractionError("decoded observations must contain one JSON object")
    return payload


def extract_to_path(transcript: Path, output: Path) -> None:
    if not transcript.is_absolute() or not output.is_absolute():
        raise ExtractionError("transcript and output paths must be absolute")
    payload = extract_observations(_read_pinned_private_file(transcript))
    parent = output.parent
    parent_metadata = parent.stat(follow_symlinks=False)
    if (
        not stat.S_ISDIR(parent_metadata.st_mode)
        or stat.S_IMODE(parent_metadata.st_mode) != 0o700
    ):
        raise ExtractionError("observations parent must be a private directory")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | os.O_NOFOLLOW
    try:
        descriptor = os.open(output, flags, 0o600)
    except OSError as error:
        raise ExtractionError("observations output must be a new regular file") from error
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
            raise ExtractionError("observations output is not a single-link regular file")
        view = memoryview(payload)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise ExtractionError("observations output write did not make progress")
            view = view[written:]
        os.fsync(descriptor)
        os.fchmod(descriptor, 0o600)
    finally:
        os.close(descriptor)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--transcript", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        extract_to_path(args.transcript, args.output)
    except (ExtractionError, OSError) as error:
        print(
            f"Android ordinary observation extraction failed closed: {error}",
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
