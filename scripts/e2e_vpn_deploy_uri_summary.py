#!/usr/bin/env python3
"""Build a redacted display string for vpn-deploy E2E profile URIs."""

from __future__ import annotations

import argparse
import hashlib
from urllib.parse import urlsplit


def summarize_uri(uri: str) -> str:
    parsed = urlsplit(uri)
    if not parsed.scheme or not parsed.hostname:
        raise ValueError("URI must include a scheme and host")

    host = parsed.hostname
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    port = f":{parsed.port}" if parsed.port is not None else ""
    digest = hashlib.sha256(uri.encode("utf-8")).hexdigest()[:12]
    return f"{parsed.scheme}://{host}{port}#sha256={digest}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("uri", help="Full URI to summarize without exposing credentials")
    args = parser.parse_args()
    print(summarize_uri(args.uri))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
