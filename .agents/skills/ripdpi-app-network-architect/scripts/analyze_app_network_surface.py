#!/usr/bin/env python3
"""
Heuristic network-surface scanner for the RIPDPI Android app repository.

This scanner is intentionally conservative: it emits leads for a human/Codex
reviewer, not proof of a bug. It does not run network traffic or modify files.
"""
from __future__ import annotations

import argparse
import json
import os
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, List, Sequence

DEFAULT_EXCLUDES = {
    ".agents", ".claude", ".codex", ".git", ".github", ".gradle", ".idea",
    ".kotlin", "build", "dist", "out", "node_modules", "coverage", ".tmp",
    "tmp", "target", ".venv", "venv",
}
DEFAULT_EXCLUDED_FILES = {"AGENTS.md", "CLAUDE.md"}
SOURCE_EXTS = {
    ".kt", ".kts", ".java", ".rs", ".toml", ".proto", ".json", ".yaml", ".yml",
    ".md", ".xml", ".sh", ".py",
}

@dataclass(frozen=True)
class Finding:
    severity: str
    confidence: str
    kind: str
    path: str
    line: int
    finding: str
    suggestion: str

PATTERNS = [
    (
        "high", "medium", "vpn_protect_invariant", re.compile(r"\b(connect|bind)\s*\(|TcpStream::connect|UdpSocket::bind|Socket\("),
        "Socket connect/bind path. Verify VpnService.protect(fd) or an equivalent bypass is applied before upstream connection in VPN mode.",
        "Trace this path through JNI protect callback and Unix-socket fallback; add a regression test if protection is implicit.",
        {".kt", ".java", ".rs"},
    ),
    (
        "high", "high", "payload_logging_risk", re.compile(r"(?i)(pcap|payload|tls secret|traffic body|full packet|raw packet capture|dump_packet)"),
        "Payload/capture-related term. Ensure diagnostics do not persist user traffic payloads or TLS secrets by default.",
        "Confirm redaction and opt-in boundaries; add archive-content tests when export behavior changes.",
        None,
    ),
    (
        "medium", "medium", "hardcoded_network_constant", re.compile(r"(?i)(mtu\s*[=:]\s*\d+|timeout\s*[=:]\s*\d+|port\s*[=:]\s*\d+|ttl\s*[=:]\s*\d+)"),
        "Hardcoded network constant. It may be valid, but constants often need diagnostics evidence, config, or comments.",
        "Check whether this should be configurable, capability-gated, or covered by a network regression fixture.",
        None,
    ),
    (
        "medium", "medium", "dns_resilience_surface", re.compile(r"(?i)(DoH|DoT|DoQ|DNSCrypt|resolver|dns fail|dns_fallback|DnsFailover|rcode|cname|edns)"),
        "DNS resilience surface. Review resolver failover, leak boundaries, timeout budget, and tamper classification.",
        "Map this to the DNS failure model and verify with fixtures for tamper, timeout, and fallback exhaustion.",
        None,
    ),
    (
        "medium", "medium", "quic_udp_surface", re.compile(r"(?i)(QUIC|UDP|Hysteria|TUIC|MASQUE|CONNECT-UDP|datagram|h3|http/3)"),
        "QUIC/UDP transport surface. Review MTU, NAT rebinding, timeout, congestion, and UDP-block behavior.",
        "Check whether diagnostics can distinguish UDP block, path MTU issue, and protocol fingerprint failure.",
        None,
    ),
    (
        "medium", "medium", "tls_strategy_surface", re.compile(r"(?i)(TLS|SNI|JA3|JA4|ClientHello|ServerHello|Reality|ShadowTLS|NaiveProxy|xhttp|VLESS)"),
        "TLS/proxy strategy surface. Review fingerprint consistency, target selection, and fallback behavior.",
        "Validate against protocol-specific fixtures and avoid brittle magic behavior without evidence.",
        None,
    ),
    (
        "medium", "medium", "root_helper_surface", re.compile(r"(?i)(root helper|root_mode|su -c|SCM_RIGHTS|TCP_REPAIR|raw socket|IP_HDRINCL|CAP_NET_RAW)"),
        "Root/raw-socket surface. Confirm opt-in gating and graceful non-root fallback.",
        "Verify capability checks and negative tests on non-rooted devices/emulators.",
        None,
    ),
    (
        "low", "medium", "handover_surface", re.compile(r"(?i)(handover|roaming|ConnectivityManager|NetworkCallback|network fingerprint|reconnect|restart|backoff)"),
        "Network handover/restart surface. Review race conditions, stale policy replay, and restart backoff.",
        "Test Wi-Fi↔cellular transition, captive-portal transition, and resolver/network-scope changes.",
        None,
    ),
    (
        "low", "medium", "jni_boundary_surface", re.compile(r"(?i)(external fun|JNI|System\.loadLibrary|jniRegister|native\w*\()"),
        "JNI/control-plane boundary. Confirm no per-packet JNI calls and that contracts are covered by fixtures.",
        "Check JSON/protobuf/JNI compatibility tests and native/Kotlin ownership boundaries.",
        {".kt", ".java", ".rs"},
    ),
    (
        "info", "medium", "observability_surface", re.compile(r"(?i)(telemetry|tracing|logger|logcat|snapshot|export|archive|redact|metric)"),
        "Observability surface. Review cost, cardinality, privacy, redaction, and usefulness during incident triage.",
        "Ensure telemetry is metadata-only and not on the per-packet hot path.",
        None,
    ),
]

KEY_FILE_HINTS = [
    "AGENTS.md", "docs/architecture/ARCHITECTURE.md", "docs/architecture/JNI_CONTRACT.md",
    "docs/architecture/CONFIG_CONTRACTS.md", "docs/architecture/DIAGNOSTICS_ARCHITECTURE.md",
    "docs/native/proxy-engine.md", "docs/native/tunnel.md", "docs/testing.md",
]


def iter_files(
    root: Path,
    excludes: set[str],
    excluded_files: set[str] = DEFAULT_EXCLUDED_FILES,
) -> Iterable[Path]:
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in excludes]
        for filename in filenames:
            path = Path(dirpath) / filename
            if filename not in excluded_files and path.suffix in SOURCE_EXTS:
                yield path


def scan_file(root: Path, path: Path) -> List[Finding]:
    rel = path.relative_to(root).as_posix()
    findings: List[Finding] = []
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return findings
    for lineno, line in enumerate(text.splitlines(), 1):
        for severity, confidence, kind, regex, finding, suggestion, exts in PATTERNS:
            if exts is not None and path.suffix not in exts:
                continue
            if regex.search(line):
                findings.append(Finding(severity, confidence, kind, rel, lineno, finding, suggestion))
    return findings


def key_file_findings(root: Path) -> List[Finding]:
    out: List[Finding] = []
    for key in KEY_FILE_HINTS:
        if not (root / key).exists():
            out.append(Finding(
                "info", "high", "missing_reference_doc", key, 0,
                f"Expected reference document not found: {key}.",
                "If the doc was renamed, read the replacement before auditing; otherwise document the missing context in the report.",
            ))
    return out


def rank_key(f: Finding) -> tuple[int, int, str, int]:
    severity_rank = {"critical": 0, "high": 1, "medium": 2, "low": 3, "info": 4}.get(f.severity, 5)
    confidence_rank = {"high": 0, "medium": 1, "low": 2}.get(f.confidence, 3)
    return (severity_rank, confidence_rank, f.path, f.line)


def dedupe(findings: Sequence[Finding]) -> List[Finding]:
    seen = set()
    out = []
    for f in sorted(findings, key=rank_key):
        key = (f.kind, f.path, f.line)
        if key not in seen:
            seen.add(key)
            out.append(f)
    return out


def render_markdown(findings: Sequence[Finding]) -> str:
    lines = ["# RIPDPI App Network Surface Scanner", "", "Scanner output is heuristic leads, not proof.", ""]
    if not findings:
        lines.append("No findings emitted by the heuristic scanner.")
        return "\n".join(lines)
    for f in findings:
        loc = f"{f.path}:{f.line}" if f.line else f.path
        lines.extend([
            f"## {f.severity.upper()} / {f.confidence} — {f.kind}",
            f"- Location: `{loc}`",
            f"- Finding: {f.finding}",
            f"- Suggestion: {f.suggestion}",
            "",
        ])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Scan RIPDPI app repo for network-review leads.")
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument("--exclude", action="append", default=[], help="Additional directory name to exclude")
    parser.add_argument("--max-findings", type=int, default=120)
    parser.add_argument("--format", choices=("markdown", "json"), default="markdown")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    excludes = DEFAULT_EXCLUDES | set(args.exclude)
    findings: List[Finding] = []
    findings.extend(key_file_findings(root))
    for path in iter_files(root, excludes):
        findings.extend(scan_file(root, path))
    findings = dedupe(findings)[: args.max_findings]

    if args.format == "json":
        print(json.dumps([asdict(f) for f in findings], indent=2, ensure_ascii=False))
    else:
        print(render_markdown(findings))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
