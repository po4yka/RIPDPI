"""Retain bounded, private-data-free receipts from run-owned local peers."""

from __future__ import annotations

import http.client
import json
from pathlib import Path
import re
import socket
import threading
import time

MAX_RESPONSE_BYTES = 65_536
CAPTURE_TIMEOUT_SECONDS = 2.0
PEER_NAMES = frozenset({"main", "reserve", "replacement"})
ENDPOINTS = ("dns-receipts", "transport-receipts")


class CaptureError(Exception):
    pass


def _number(value: object) -> int:
    if type(value) is not int or not 0 <= value <= 2**63 - 1:
        raise CaptureError("invalid-receipt")
    return value


def _sanitize(endpoint: str, payload: object) -> dict:
    if endpoint == "transport-receipts":
        if not isinstance(payload, list) or len(payload) > 128:
            raise CaptureError("invalid-receipt")
        items = []
        for item in payload:
            if not isinstance(item, dict) or not re.fullmatch(
                r"[a-f0-9]{64}", str(item.get("sha256", ""))
            ):
                raise CaptureError("invalid-receipt")
            if item.get("operation") not in ("download", "upload", "stream"):
                raise CaptureError("invalid-receipt")
            items.append(
                {
                    "operation": item["operation"],
                    "bytes": _number(item.get("bytes")),
                    "elapsedMs": _number(item.get("elapsedMs")),
                    "sha256": item["sha256"],
                }
            )
        return {"count": len(items), "receipts": items}
    if not isinstance(payload, dict):
        raise CaptureError("invalid-receipt")
    if "count" in payload:
        return {
            "count": _number(payload["count"]),
            "ownedQueryObserved": payload.get("lastQuery")
            in ("owned.test", "owned.test."),
        }
    items = payload.get("receipts")
    if not isinstance(items, list) or len(items) > 128:
        raise CaptureError("invalid-receipt")
    owned = []
    for item in items:
        if not isinstance(item, dict):
            raise CaptureError("invalid-receipt")
        if item.get("query") == "acceptance.test.":
            if _number(item.get("type")) not in (1, 28):
                raise CaptureError("invalid-receipt")
            owned.append({"query": "acceptance.test.", "type": item["type"]})
    result = {
        "count": len(owned),
        "errors": _number(payload.get("errors")),
        "receipts": owned,
    }
    if "rejected" in payload:
        result["rejected"] = _number(payload["rejected"])
    return result


def _fetch(port: int, endpoint: str) -> dict:
    started = time.monotonic()
    connection = http.client.HTTPConnection(
        "127.0.0.1", port, timeout=CAPTURE_TIMEOUT_SECONDS
    )
    timer = None
    expired = threading.Event()
    try:
        connection.connect()
        transport = connection.sock

        def expire():
            expired.set()
            try:
                transport.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

        remaining = CAPTURE_TIMEOUT_SECONDS - (time.monotonic() - started)
        if remaining <= 0:
            raise CaptureError("capture-timeout")
        timer = threading.Timer(remaining, expire)
        timer.start()
        connection.request("GET", "/" + endpoint)
        with connection.getresponse() as response:
            if response.status != 200:
                raise CaptureError("http-status")
            body = response.read(MAX_RESPONSE_BYTES + 1)
        if expired.is_set():
            raise CaptureError("capture-timeout")
        if len(body) > MAX_RESPONSE_BYTES:
            raise CaptureError("response-too-large")
        try:
            payload = json.loads(body)
        except (ValueError, UnicodeError):
            raise CaptureError("invalid-json") from None
        return _sanitize(endpoint, payload)
    except (OSError, http.client.HTTPException):
        raise CaptureError(
            "capture-timeout" if expired.is_set() else "endpoint-unavailable"
        ) from None
    finally:
        if timer is not None:
            timer.cancel()
            timer.join()
        connection.close()


class OwnedPeerDiagnostics:
    def __init__(self):
        self.peers: dict[str, int] = {}

    def register(self, name: str, control_port: int) -> None:
        """Register only after this run has started the peer and read its ready receipt."""
        if name not in PEER_NAMES or name in self.peers:
            raise ValueError("invalid owned diagnostic peer")
        if type(control_port) is not int or not 1 <= control_port <= 65535:
            raise ValueError("invalid owned diagnostic control port")
        self.peers[name] = control_port

    def capture(self, out: Path) -> dict[str, str]:
        outcomes = {}
        for name, port in self.peers.items():
            for endpoint in ENDPOINTS:
                key = f"{name}-{endpoint}"
                evidence = {
                    "schema_version": 1,
                    "scope": "diagnostic-only",
                    "status": "unavailable",
                }
                try:
                    evidence.update(receipt=_fetch(port, endpoint), status="captured")
                except CaptureError as error:
                    evidence["reason"] = str(error)
                except Exception:
                    evidence["reason"] = "capture-failed"
                outcomes[key] = evidence["status"]
                try:
                    (out / f"diagnostic-{key}.json").write_text(
                        json.dumps(evidence, indent=2) + "\n"
                    )
                except OSError:
                    outcomes[key] = "write-failed"
        return outcomes
