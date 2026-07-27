#!/usr/bin/env python3
"""Verify source-owned Android physical-producer attestation off device."""

from __future__ import annotations

import base64
import hashlib
import json
import re
import urllib.request
from datetime import datetime, timezone
from typing import Any


ATTESTATION_VERSION = "android_ordinary_physical_producer_attestation_v1"
HARDWARE_VERSION = "android_key_attestation_v1"
CHALLENGE_PREFIX = "RIPDPI-ORDINARY-ATTESTATION-V1"
ANDROID_ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"
ROOTS_URL = "https://android.googleapis.com/attestation/root"
STATUS_URL = "https://android.googleapis.com/attestation/status"
SHA1_RE = re.compile(r"[0-9a-f]{40}\Z")
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")


class AttestationError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


def canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode()


def expected_challenge(
    source_sha: str, app_apk_sha256: str, test_apk_sha256: str, run_id: str
) -> bytes:
    return hashlib.sha256(
        "\0".join(
            (
                CHALLENGE_PREFIX,
                source_sha,
                app_apk_sha256,
                test_apk_sha256,
                run_id,
            )
        ).encode()
    ).digest()


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise AttestationError(
            "PHYSICAL_ATTESTATION_INVALID",
            f"{label} keys differ; missing={sorted(expected - set(value))}, "
            f"extra={sorted(set(value) - expected)}",
        )


def _digest(value: Any, pattern: re.Pattern[str], label: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise AttestationError("PHYSICAL_ATTESTATION_INVALID", f"{label} is malformed")
    return value


def _fetch_json(url: str) -> Any:
    request = urllib.request.Request(
        url, headers={"User-Agent": "ripdpi-release-evidence/1"}
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            if response.status != 200:
                raise AttestationError(
                    "ATTESTATION_TRUST_UNAVAILABLE",
                    f"official endpoint returned HTTP {response.status}",
                )
            raw = response.read(2 * 1024 * 1024 + 1)
    except AttestationError:
        raise
    except OSError as error:
        raise AttestationError(
            "ATTESTATION_TRUST_UNAVAILABLE",
            "official Android attestation data is unavailable",
        ) from error
    if len(raw) > 2 * 1024 * 1024:
        raise AttestationError(
            "ATTESTATION_TRUST_UNAVAILABLE",
            "official attestation response is oversized",
        )
    try:
        return json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AttestationError(
            "ATTESTATION_TRUST_UNAVAILABLE",
            "official attestation response is malformed",
        ) from error


def _read_length(raw: bytes, offset: int) -> tuple[int, int]:
    if offset >= len(raw):
        raise AttestationError(
            "HARDWARE_ATTESTATION_INVALID", "attestation DER is truncated"
        )
    first = raw[offset]
    if first < 0x80:
        return first, offset + 1
    width = first & 0x7F
    if width == 0 or width > 4 or offset + 1 + width > len(raw):
        raise AttestationError(
            "HARDWARE_ATTESTATION_INVALID", "attestation DER length is invalid"
        )
    length = int.from_bytes(raw[offset + 1 : offset + 1 + width], "big")
    if length < 0x80:
        raise AttestationError(
            "HARDWARE_ATTESTATION_INVALID", "attestation DER is non-minimal"
        )
    return length, offset + 1 + width


def _read_tlv(raw: bytes, offset: int, expected_tag: int) -> tuple[bytes, int]:
    if offset >= len(raw) or raw[offset] != expected_tag:
        raise AttestationError(
            "HARDWARE_ATTESTATION_INVALID", "attestation DER schema differs"
        )
    length, payload_offset = _read_length(raw, offset + 1)
    end = payload_offset + length
    if end > len(raw):
        raise AttestationError(
            "HARDWARE_ATTESTATION_INVALID", "attestation DER is truncated"
        )
    return raw[payload_offset:end], end


def _positive_der_integer(raw: bytes, label: str) -> int:
    if not raw or raw[0] & 0x80:
        raise AttestationError(
            "HARDWARE_ATTESTATION_INVALID", f"{label} is not positive"
        )
    return int.from_bytes(raw, "big")


def _key_description(extension: bytes) -> tuple[int, int, bytes]:
    sequence, end = _read_tlv(extension, 0, 0x30)
    if end != len(extension):
        raise AttestationError(
            "HARDWARE_ATTESTATION_INVALID", "attestation extension has trailing bytes"
        )
    offset = 0
    _, offset = _read_tlv(sequence, offset, 0x02)
    attestation_level_raw, offset = _read_tlv(sequence, offset, 0x0A)
    _, offset = _read_tlv(sequence, offset, 0x02)
    keymint_level_raw, offset = _read_tlv(sequence, offset, 0x0A)
    challenge, offset = _read_tlv(sequence, offset, 0x04)
    _, offset = _read_tlv(sequence, offset, 0x04)
    return (
        _positive_der_integer(attestation_level_raw, "attestationSecurityLevel"),
        _positive_der_integer(keymint_level_raw, "keyMintSecurityLevel"),
        challenge,
    )


def verify_hardware_attestation(
    hardware: dict[str, Any],
    *,
    challenge: bytes,
    roots_document: Any | None = None,
    status_document: Any | None = None,
) -> dict[str, Any]:
    try:
        from cryptography import x509
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa
        from cryptography.x509.oid import ObjectIdentifier
    except ImportError as error:
        raise AttestationError(
            "ATTESTATION_VERIFIER_UNAVAILABLE",
            "Python cryptography is required for off-device verification",
        ) from error

    if not isinstance(hardware, dict):
        raise AttestationError(
            "PHYSICAL_ATTESTATION_INVALID", "hardwareAttestation must be an object"
        )
    _exact_keys(
        hardware,
        {
            "certificateChainDerBase64",
            "challengeSha256",
            "requestedStrongBox",
            "version",
        },
        "hardwareAttestation",
    )
    if hardware["version"] != HARDWARE_VERSION or not isinstance(
        hardware["requestedStrongBox"], bool
    ):
        raise AttestationError(
            "PHYSICAL_ATTESTATION_INVALID", "hardware attestation metadata is invalid"
        )
    if (
        _digest(hardware["challengeSha256"], SHA256_RE, "challengeSha256")
        != challenge.hex()
    ):
        raise AttestationError(
            "ATTESTATION_CHALLENGE_MISMATCH", "hardware challenge metadata is stale"
        )
    encoded_chain = hardware["certificateChainDerBase64"]
    if not isinstance(encoded_chain, list) or not 2 <= len(encoded_chain) <= 8:
        raise AttestationError(
            "HARDWARE_ATTESTATION_INVALID", "certificate chain length is invalid"
        )
    certificates = []
    for encoded in encoded_chain:
        if not isinstance(encoded, str) or len(encoded) > 32 * 1024:
            raise AttestationError(
                "HARDWARE_ATTESTATION_INVALID", "certificate encoding is invalid"
            )
        try:
            certificates.append(
                x509.load_der_x509_certificate(base64.b64decode(encoded, validate=True))
            )
        except (ValueError, TypeError) as error:
            raise AttestationError(
                "HARDWARE_ATTESTATION_INVALID", "certificate is not strict DER base64"
            ) from error

    for certificate, issuer in zip(certificates, certificates[1:], strict=False):
        if certificate.issuer != issuer.subject:
            raise AttestationError(
                "HARDWARE_ATTESTATION_INVALID",
                "certificate issuer chain is discontinuous",
            )
        key = issuer.public_key()
        try:
            if isinstance(key, rsa.RSAPublicKey):
                key.verify(
                    certificate.signature,
                    certificate.tbs_certificate_bytes,
                    padding.PKCS1v15(),
                    certificate.signature_hash_algorithm,
                )
            elif isinstance(key, ec.EllipticCurvePublicKey):
                key.verify(
                    certificate.signature,
                    certificate.tbs_certificate_bytes,
                    ec.ECDSA(certificate.signature_hash_algorithm),
                )
            else:
                raise AttestationError(
                    "HARDWARE_ATTESTATION_INVALID",
                    "certificate key algorithm is unsupported",
                )
        except AttestationError:
            raise
        except (
            Exception
        ) as error:  # cryptography exposes backend-specific InvalidSignature
            raise AttestationError(
                "HARDWARE_ATTESTATION_INVALID", "certificate signature is invalid"
            ) from error

    roots = _fetch_json(ROOTS_URL) if roots_document is None else roots_document
    if not isinstance(roots, list) or not roots:
        raise AttestationError(
            "ATTESTATION_TRUST_UNAVAILABLE", "official root inventory is malformed"
        )
    trusted_spki = set()
    for pem in roots:
        if not isinstance(pem, str):
            raise AttestationError(
                "ATTESTATION_TRUST_UNAVAILABLE", "official root inventory is malformed"
            )
        root = x509.load_pem_x509_certificate(pem.encode())
        trusted_spki.add(
            hashlib.sha256(
                root.public_key().public_bytes(
                    serialization.Encoding.DER,
                    serialization.PublicFormat.SubjectPublicKeyInfo,
                )
            ).hexdigest()
        )
    chain_root_spki = hashlib.sha256(
        certificates[-1]
        .public_key()
        .public_bytes(
            serialization.Encoding.DER,
            serialization.PublicFormat.SubjectPublicKeyInfo,
        )
    ).hexdigest()
    if chain_root_spki not in trusted_spki:
        raise AttestationError(
            "ATTESTATION_ROOT_UNTRUSTED",
            "chain is not rooted in an official Google key",
        )

    status = _fetch_json(STATUS_URL) if status_document is None else status_document
    if (
        not isinstance(status, dict)
        or set(status) != {"entries"}
        or not isinstance(status["entries"], dict)
    ):
        raise AttestationError(
            "ATTESTATION_TRUST_UNAVAILABLE", "official revocation status is malformed"
        )
    revoked_serials = {str(serial).lower() for serial in status["entries"]}
    for certificate in certificates[:-1]:
        if format(certificate.serial_number, "x").lower() in revoked_serials:
            raise AttestationError(
                "ATTESTATION_CERTIFICATE_REVOKED",
                "attestation chain contains a revoked key",
            )

    now = datetime.now(timezone.utc)
    for certificate in certificates:
        if (
            not certificate.not_valid_before_utc
            <= now
            <= certificate.not_valid_after_utc
        ):
            raise AttestationError(
                "HARDWARE_ATTESTATION_INVALID",
                "attestation certificate is outside validity",
            )

    extension_values: list[bytes] = []
    oid = ObjectIdentifier(ANDROID_ATTESTATION_OID)
    for certificate in certificates:
        try:
            extension_values.append(
                certificate.extensions.get_extension_for_oid(oid).value.value
            )
        except x509.ExtensionNotFound:
            continue
    if len(extension_values) != 1:
        raise AttestationError(
            "HARDWARE_ATTESTATION_INVALID",
            "chain must contain one trusted key description",
        )
    attestation_level, keymint_level, actual_challenge = _key_description(
        extension_values[0]
    )
    if attestation_level not in {1, 2} or keymint_level not in {1, 2}:
        raise AttestationError(
            "ATTESTATION_NOT_HARDWARE_BACKED",
            "key security level is not TEE or StrongBox",
        )
    if actual_challenge != challenge:
        raise AttestationError(
            "ATTESTATION_CHALLENGE_MISMATCH", "certificate challenge is not run-bound"
        )
    return {
        "attestationSecurityLevel": attestation_level,
        "certificateCount": len(certificates),
        "keyMintSecurityLevel": keymint_level,
        "rootSpkiSha256": chain_root_spki,
    }


def validate_physical_attestation(
    document: dict[str, Any],
    *,
    source_sha: str,
    app_apk_sha256: str,
    test_apk_sha256: str,
    manifest_sha256: str,
    capture_sha256: str,
) -> dict[str, Any]:
    if not isinstance(document, dict):
        raise AttestationError(
            "PHYSICAL_ATTESTATION_INVALID", "attestation must be an object"
        )
    _exact_keys(
        document,
        {
            "appApkSha256",
            "captureSha256",
            "device",
            "hardwareAttestation",
            "instrumentationTranscriptSha256",
            "manifestSha256",
            "observations",
            "producerSha256",
            "runId",
            "sourceSha",
            "testApkSha256",
            "version",
        },
        "physical attestation",
    )
    expected = {
        "version": ATTESTATION_VERSION,
        "sourceSha": source_sha,
        "appApkSha256": app_apk_sha256,
        "testApkSha256": test_apk_sha256,
        "manifestSha256": manifest_sha256,
        "captureSha256": capture_sha256,
    }
    for key, value in expected.items():
        if document[key] != value:
            raise AttestationError(
                "PHYSICAL_ATTESTATION_MISMATCH", f"{key} is not evidence-bound"
            )
    run_id = _digest(document["runId"], SHA256_RE, "runId")
    for key in ("instrumentationTranscriptSha256", "producerSha256"):
        _digest(document[key], SHA256_RE, key)
    observations = document["observations"]
    if not isinstance(observations, dict) or observations.get("runId") != run_id:
        raise AttestationError(
            "PHYSICAL_ATTESTATION_MISMATCH", "observations are not run-bound"
        )
    if observations.get("hardwareAttestation") != document["hardwareAttestation"]:
        raise AttestationError(
            "PHYSICAL_ATTESTATION_MISMATCH", "hardware attestation was copied"
        )
    device = document["device"]
    if not isinstance(device, dict):
        raise AttestationError(
            "PHYSICAL_ATTESTATION_INVALID", "device must be an object"
        )
    _exact_keys(
        device,
        {"apiLevel", "codename", "kernelRelease", "manufacturer", "serialSha256"},
        "device",
    )
    if (
        not isinstance(device["apiLevel"], int)
        or device["apiLevel"] < 24
        or not isinstance(device["codename"], str)
        or device["codename"].lower() in {"generic", "ranchu", "goldfish"}
        or str(device["manufacturer"]).lower() != "google"
        or not isinstance(device["kernelRelease"], str)
        or not device["kernelRelease"]
    ):
        raise AttestationError(
            "PHYSICAL_DEVICE_INVALID",
            "device identity is not an approved physical Android target",
        )
    _digest(device["serialSha256"], SHA256_RE, "device.serialSha256")
    if (
        observations.get("apiLevel") != device["apiLevel"]
        or observations.get("deviceCodename") != device["codename"]
        or observations.get("deviceManufacturer") != device["manufacturer"]
        or observations.get("kernelRelease") != device["kernelRelease"]
    ):
        raise AttestationError(
            "PHYSICAL_ATTESTATION_MISMATCH", "device facts differ from observations"
        )
    hardware_proof = verify_hardware_attestation(
        document["hardwareAttestation"],
        challenge=expected_challenge(
            source_sha, app_apk_sha256, test_apk_sha256, run_id
        ),
    )
    return {
        "deviceSerialSha256": device["serialSha256"],
        "hardware": hardware_proof,
        "producerSha256": document["producerSha256"],
        "runId": run_id,
        "version": ATTESTATION_VERSION,
    }
