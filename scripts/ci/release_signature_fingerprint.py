#!/usr/bin/env python3
from __future__ import annotations

import base64
import binascii
import hashlib
import re
import sys


APK_LABEL = "Signer #1 certificate SHA-256 digest:"
KEYTOOL_LABEL = "SHA256:"
SHA256_RE = re.compile(r"[0-9a-f]{64}")
PEM_CERTIFICATE_RE = re.compile(
    r"-----BEGIN CERTIFICATE-----\s*(?P<body>[A-Za-z0-9+/=\s]+?)"
    r"\s*-----END CERTIFICATE-----",
)


def extract_certificate_sha256(output: str) -> str:
    pem_certificates = list(PEM_CERTIFICATE_RE.finditer(output))
    if pem_certificates:
        if len(pem_certificates) != 1:
            raise ValueError(
                "expected exactly one PEM signing certificate, "
                f"found {len(pem_certificates)}",
            )
        encoded = re.sub(r"\s", "", pem_certificates[0].group("body"))
        try:
            certificate_der = base64.b64decode(encoded, validate=True)
        except binascii.Error as error:
            raise ValueError("invalid PEM signing certificate") from error
        if not certificate_der:
            raise ValueError("empty PEM signing certificate")
        return hashlib.sha256(certificate_der).hexdigest()

    candidates: list[str] = []
    for line in output.splitlines():
        stripped = line.strip()
        for label in (APK_LABEL, KEYTOOL_LABEL):
            if stripped.startswith(label):
                normalized = re.sub(r"[:\s]", "", stripped.removeprefix(label)).lower()
                if SHA256_RE.fullmatch(normalized):
                    candidates.append(normalized)
                break

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
