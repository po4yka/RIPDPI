from __future__ import annotations

import hashlib
from datetime import date
from pathlib import Path
from typing import Any

from .common import (
    DEFAULT_BLESSED_DEVICE_FINGERPRINT_CATALOG,
    DEFAULT_BLESSED_WINNER_MAPPING_CATALOG,
    load_json,
    write_json,
)


def bless_candidate_artifacts(
    *,
    device_catalog_candidate: Path,
    winner_catalog_candidate: Path,
    blessed_device_catalog_path: Path = DEFAULT_BLESSED_DEVICE_FINGERPRINT_CATALOG,
    blessed_winner_mapping_path: Path = DEFAULT_BLESSED_WINNER_MAPPING_CATALOG,
) -> dict[str, Path]:
    blessed_device = bless_payload(load_json(device_catalog_candidate), device_catalog_candidate)
    blessed_winner = bless_payload(load_json(winner_catalog_candidate), winner_catalog_candidate)
    write_json(blessed_device_catalog_path, blessed_device)
    write_json(blessed_winner_mapping_path, blessed_winner)
    return {
        "deviceCatalog": blessed_device_catalog_path,
        "winnerCatalog": blessed_winner_mapping_path,
    }


def bless_payload(payload: dict[str, Any], source_path: Path) -> dict[str, Any]:
    blessed = dict(payload)
    blessed["reviewStatus"] = "reviewed"
    blessed["reviewedAt"] = date.today().isoformat()
    blessed["candidateSha256"] = hashlib.sha256(source_path.read_bytes()).hexdigest()
    return blessed

