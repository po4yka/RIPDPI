#!/usr/bin/env python3
from __future__ import annotations

import re
import sys


ANSI_ESCAPE_RE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
CERTIFICATE_SHA256_LABEL_RE = re.compile(
    r"(?:signer\s+#?\d+\s+)?certificate\s+sha(?:-|\s)?256\s+digest\s*:",
    re.IGNORECASE,
)
KEYTOOL_SHA256_LABEL_RE = re.compile(r"\bsha(?:-|\s)?256\s*:", re.IGNORECASE)
SHA256_TOKEN_RE = re.compile(
    r"(?<![0-9a-f])(?:[0-9a-f]{2}(?:[:\s]?)){31}[0-9a-f]{2}(?![0-9a-f])",
    re.IGNORECASE,
)
SHA256_RE = re.compile(r"[0-9a-f]{64}")


def extract_certificate_sha256(output: str) -> str:
    candidates: list[str] = []
    for line in output.splitlines():
        stripped = ANSI_ESCAPE_RE.sub("", line).strip()
        label_match = CERTIFICATE_SHA256_LABEL_RE.search(stripped)
        if label_match is None:
            label_match = KEYTOOL_SHA256_LABEL_RE.search(stripped)
        if label_match is None:
            continue

        digest_tokens = SHA256_TOKEN_RE.findall(stripped[label_match.end() :])
        if len(digest_tokens) != 1:
            continue
        normalized = re.sub(r"[:\s]", "", digest_tokens[0]).lower()
        if SHA256_RE.fullmatch(normalized):
            candidates.append(normalized)

    if len(candidates) != 1:
        raise ValueError(
            "expected exactly one valid certificate SHA-256 fingerprint, "
            f"found {len(candidates)}",
        )
    return candidates[0]


def main() -> int:
    try:
        fingerprint = extract_certificate_sha256(sys.stdin.read())
    except ValueError as error:
        print(error, file=sys.stderr)
        return 2
    print(fingerprint)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
