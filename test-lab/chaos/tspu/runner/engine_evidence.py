"""Current-engine raw capture gate for the declared per-segment TCP SNI model.

This is scoped host evidence, not NFQUEUE, Android, QUIC or carrier acceptance.
The validator derives classifiers from TCP bytes, not report verdict labels.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys

from .packet_parser import extract_tls_sni, parse_outbound_packet

PATTERNS = ["rst-after-sni-match", "sni-replace"]
COVERAGE = {"transport": "tcp", "dpiModel": "per-segment-sni", "patterns": PATTERNS,
            "combinations": ["rst-after-sni-match+sni-replace"],
            "profiles": {"control": [], "candidate": ["-s", "3"]},
            "platform": "linux-loopback", "fullMatrixAcceptance": False}
SCENARIOS = {"control": "cli_packet_smoke_tls_unsplit_l7", "candidate": "cli_packet_smoke_tls_split_l7"}
ARTIFACTS = ["fixture-manifest.json", "fixture-events.json", "cli-stderr.log", "test-output.txt",
             "capture.pcap", "capture.tshark.json", "cli-command.json"]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_identity(root: Path) -> dict:
    """Hash native build inputs and the capture/classifier implementation.

    HEAD is recorded for provenance; content is authoritative across a rebase.
    Include untracked inputs so an uncommitted Rust source cannot evade the hash.
    """
    paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root).decode().split("\0")
    selected = sorted({p for p in paths if p and (
        p.startswith("native/rust/") and not p.endswith(".md") or
        p.startswith("test-lab/chaos/tspu/runner/") and p.endswith(".py") or
        p.startswith("test-lab/chaos/tspu/patterns/") and p.endswith(".py") or
        p in {"scripts/ci/run-l7-engine-evidence.sh", "scripts/ci/packet-smoke-scenarios.json", "rust-toolchain.toml", ".cargo/config.toml"}
    )})
    hashes = {p: sha(root / p) for p in selected}
    digest = hashlib.sha256(json.dumps(hashes, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return {"head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
            "treeSha256": digest, "files": hashes}


def field(layers: dict, group: str, key: str):
    value = layers.get(group, {}).get(key)
    if isinstance(value, list):
        if len(value) != 1:
            raise ValueError(f"ambiguous {key}")
        return value[0]
    return value


def analyze_packets(packets: list, port: int, domain: str, split: bool) -> dict:
    """Validate complete ClientHello bytes and derive per-segment classifier input."""
    if not isinstance(packets, list) or not packets:
        raise ValueError("missing decoded capture packets")
    segments = []
    flows = set()
    for packet in packets:
        layers = packet["_source"]["layers"]
        if int(field(layers, "tcp", "tcp.dstport") or 0) != port:
            continue
        raw = field(layers, "tcp", "tcp.payload")
        length = int(field(layers, "tcp", "tcp.len") or 0)
        if not length:
            if raw:
                raise ValueError("zero length cannot hide TCP payload")
            continue
        if not isinstance(raw, str):
            raise ValueError("missing raw TCP payload")
        payload = bytes.fromhex(raw.replace(":", ""))
        if len(payload) != length:
            raise ValueError("TCP payload length mismatch")
        seq = int(field(layers, "tcp", "tcp.seq_raw"))
        src_ip = field(layers, "ip", "ip.src")
        dst_ip = field(layers, "ip", "ip.dst")
        src_port = int(field(layers, "tcp", "tcp.srcport"))
        stream = field(layers, "tcp", "tcp.stream")
        if src_ip != "127.0.0.1" or dst_ip != "127.0.0.1" or not 0 < src_port < 65536 or stream is None:
            raise ValueError("missing or non-loopback upstream flow metadata")
        flows.add((src_ip, src_port, dst_ip, port, stream))
        segments.append((seq, payload, src_port))
    if len(flows) != 1 or not segments:
        raise ValueError("expected one captured outbound TLS flow")
    # Locate the start by signed wrapping distances. Retransmissions must agree.
    origin = segments[0][0]
    offsets = [((seq - origin + 2**31) % 2**32) - 2**31 for seq, _, _ in segments]
    start = min(offsets)
    data = {}
    for offset, (_, payload, _) in zip(offsets, segments):
        for index, byte in enumerate(payload, offset - start):
            if index in data and data[index] != byte:
                raise ValueError("contradictory TCP overlap")
            data[index] = byte
    if not all(i in data for i in range(5)):
        raise ValueError("missing TLS record header")
    header = bytes(data[i] for i in range(5))
    if header[0] != 22 or header[1] != 3:
        raise ValueError("capture does not start with TLS handshake")
    size = 5 + int.from_bytes(header[3:5], "big")
    if size < 9 or size > 65540 or not all(i in data for i in range(size)):
        raise ValueError("incomplete TLS record or capture gap")
    hello = bytes(data[i] for i in range(size))
    if hello[5] != 1 or int.from_bytes(hello[6:9], "big") != size - 9 or extract_tls_sni(hello) != domain:
        raise ValueError("complete ClientHello does not contain the actual fixture SNI")
    first_segments = [(offset - start, payload) for offset, (_, payload, _) in zip(offsets, segments) if offset - start < size]
    if split:
        if not any(offset == 0 and len(payload) == 3 for offset, payload in first_segments):
            raise ValueError("missing strict three-byte split boundary")
        if any(offset < 3 < offset + len(payload) for offset, payload in first_segments):
            raise ValueError("a segment crosses the strict split boundary")
    trace = {"packets": [parse_outbound_packet(transport="tcp", payload=payload, src_port=src_port, dst_port=port)
                         for _, payload, src_port in segments]}
    matched = {}
    for pattern in PATTERNS:
        module = importlib.import_module("patterns." + pattern.replace("-", "_"))
        matched[pattern] = bool(module.classify(trace, {"sni_blocklist": [domain], "replacement_sni": "sinkhole.test"})["matched"])
    expected = not split
    if any(value != expected for value in matched.values()):
        raise ValueError("unexpected blocked candidate or ineffective control")
    return {"clientHelloSha256": hashlib.sha256(hello).hexdigest(), "clientHelloBytes": size,
            "sni": domain, "matched": matched, "combinationMatched": any(matched.values())}


def safe_file(base: Path, name: str) -> Path:
    path = base / name
    if path.is_symlink() or not path.is_file() or path.resolve().parent != base.resolve():
        raise ValueError(f"missing or unsafe artifact: {name}")
    return path


def validate_report(report_path: Path, repo_root: Path) -> dict:
    receipt = {"purpose": "engine-release", "gateVerdict": "fail", "releaseAcceptance": False,
               "errors": [], "coverage": COVERAGE}
    try:
        report = json.loads(report_path.read_text())
        if not isinstance(report, dict):
            raise ValueError("report must be an object")
        if type(report.get("schemaVersion")) is not int or report["schemaVersion"] != 1 or report.get("mode") != "engine-capture" or report.get("purpose") != "engine-release":
            raise ValueError("report is not current-engine capture evidence")
        if report.get("coverage") != COVERAGE or report.get("platform", {}).get("system") != "Linux":
            raise ValueError("unsupported or incomplete coverage/platform")
        identity = source_identity(repo_root)
        recorded = report["source"]
        if recorded["treeSha256"] != identity["treeSha256"] or recorded["files"] != identity["files"] or not re.fullmatch(r"[0-9a-f]{40}", recorded["head"]):
            raise ValueError("source identity changed or missing")
        base = report_path.parent
        if sha(safe_file(base, "ripdpi")) != report["binarySha256"]:
            raise ValueError("binary identity mismatch")
        if set(report["scenarios"]) != set(SCENARIOS):
            raise ValueError("incomplete or unknown scenario")
        for role, selector in SCENARIOS.items():
            entry = report["scenarios"][role]
            if entry["selector"] != selector or entry["profile"] != COVERAGE["profiles"][role] or type(entry["exitCode"]) is not int or entry["exitCode"] != 0:
                raise ValueError("incorrect selector/profile or failed test")
            expected_command = ["cargo", "test", "--locked", "--manifest-path", "native/rust/Cargo.toml", "-p", "ripdpi-cli", "--test", "packet_smoke", selector, "--", "--ignored", "--exact", "--nocapture"]
            if entry["command"] != expected_command or set(entry["hashes"]) != set(ARTIFACTS):
                raise ValueError("wrong command or incomplete artifact set")
            directory = base / selector
            for name in ARTIFACTS:
                if sha(safe_file(directory, name)) != entry["hashes"][name]:
                    raise ValueError(f"artifact hash mismatch: {role}/{name}")
            cli_args = json.loads((directory / "cli-command.json").read_text())
            if not isinstance(cli_args, list) or not all(isinstance(arg, str) for arg in cli_args) or len(cli_args) < 6 or cli_args[:3] != ["--ip", "127.0.0.1", "--port"] or cli_args[4:6] != ["--debug", "2"] or cli_args[6:] != COVERAGE["profiles"][role] or not 0 < int(cli_args[3]) < 65536:
                raise ValueError("actual engine command does not match the declared profile")
            log = (directory / "test-output.txt").read_text()
            if not re.search(r"test result: ok\. 1 passed; 0 failed; 0 ignored;", log) or f"test {selector} ... ok" not in log or "l7-engine tls-echo-ok" not in log or "skipping " in log:
                raise ValueError("test was skipped, absent, or did not complete strict TLS echo")
            manifest = json.loads((directory / "fixture-manifest.json").read_text())
            port, domain = manifest["tlsEchoPort"], manifest["fixtureDomain"]
            if type(port) is not int or not 0 < port < 65536 or domain != "fixture.test" or manifest.get("bindHost") != "127.0.0.1":
                raise ValueError("invalid actual fixture endpoint")
            events = json.loads((directory / "fixture-events.json").read_text())
            if not any(e.get("service") == "tls_echo" and e.get("detail") == "handshake" and e.get("sni") == domain and e.get("target", "").endswith(f":{port}") for e in events):
                raise ValueError("no actual successful fixture TLS handshake")
            if f"l7-engine fixture-handshake-ok sni={domain}" not in log:
                raise ValueError("missing fixture handshake assertion")
            pcap = (directory / "capture.pcap").read_bytes()
            if len(pcap) <= 24 or pcap[:4] not in [b"\xd4\xc3\xb2\xa1", b"\xa1\xb2\xc3\xd4", b"\x4d\x3c\xb2\xa1", b"\xa1\xb2\x3c\x4d"]:
                raise ValueError("missing raw pcap")
            # Decode again; a replaced JSON trace must not substitute for raw pcap.
            decoded = subprocess.run(["tshark", "-r", str(directory / "capture.pcap"), "-T", "json"], capture_output=True, check=True, timeout=60)
            packets = json.loads(decoded.stdout)
            saved = json.loads((directory / "capture.tshark.json").read_text())
            actual = analyze_packets(packets, port, domain, role == "candidate")
            if actual != analyze_packets(saved, port, domain, role == "candidate"):
                raise ValueError("decoded raw pcap disagrees with saved capture")
        receipt.update(gateVerdict="pass", releaseAcceptance=True)
    except (OSError, ValueError, TypeError, KeyError, IndexError, AttributeError, subprocess.SubprocessError) as error:
        receipt["errors"].append(str(error))
    return receipt


def capture(root: Path, out: Path) -> dict:
    if platform.system() != "Linux":
        raise ValueError("current-engine capture requires Linux; no acceptance on unsupported platforms")
    for executable in ["cargo", "git", "tcpdump", "tshark"]:
        if not shutil.which(executable):
            raise ValueError(f"missing required executable: {executable}")
    out.mkdir(parents=True, exist_ok=True)
    if any(out.iterdir()):
        raise ValueError("engine artifact directory must be empty; do not reuse stale captures")
    identity = source_identity(root)
    report = {"schemaVersion": 1, "mode": "engine-capture", "purpose": "engine-release", "coverage": COVERAGE,
              "platform": {"system": platform.system(), "machine": platform.machine(), "kernel": platform.release()},
              "source": identity, "scenarios": {},
              "capturedAtUtc": datetime.now(timezone.utc).isoformat(),
              "buildEnvironment": {key: os.environ.get(key, "") for key in ["RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_BUILD_TARGET"]},
              "tools": {command: subprocess.check_output([command, "--version"], text=True).strip()
                        for command in ["rustc", "cargo", "tcpdump", "tshark"]}}
    for role, selector in SCENARIOS.items():
        directory = out / selector
        directory.mkdir()
        env = dict(os.environ, RIPDPI_RUN_PACKET_SMOKE="1", RIPDPI_PACKET_SMOKE_ARTIFACT_DIR=str(directory), RIPDPI_PACKET_SMOKE_IFACE="lo")
        command = ["cargo", "test", "--locked", "--manifest-path", "native/rust/Cargo.toml", "-p", "ripdpi-cli", "--test", "packet_smoke", selector, "--", "--ignored", "--exact", "--nocapture"]
        with (directory / "test-output.txt").open("wb") as log:
            result = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
        print((directory / "test-output.txt").read_text(), flush=True)
        if result.returncode:
            raise ValueError(f"actual engine test failed: {selector}")
        report["scenarios"][role] = {"selector": selector, "profile": COVERAGE["profiles"][role], "command": command,
                                     "exitCode": result.returncode, "hashes": {name: sha(directory / name) for name in ARTIFACTS}}
    metadata = json.loads(subprocess.check_output(["cargo", "metadata", "--locked", "--no-deps", "--format-version", "1", "--manifest-path", "native/rust/Cargo.toml"], cwd=root))
    shutil.copyfile(Path(metadata["target_directory"]) / "debug/ripdpi", out / "ripdpi")
    report["binarySha256"] = sha(out / "ripdpi")
    if identity != source_identity(root):
        raise ValueError("source changed during capture")
    path = out / "verdict-report.json"
    path.write_text(json.dumps(report, indent=2) + "\n")
    return validate_report(path, root)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["capture", "validate"])
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        if args.action == "capture":
            if args.out is None:
                parser.error("capture requires --out")
            receipt = capture(args.repo_root.resolve(), args.out.resolve())
        else:
            if args.report is None:
                parser.error("validate requires --report")
            receipt = validate_report(args.report, args.repo_root)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        receipt = {"purpose": "engine-release", "gateVerdict": "fail", "releaseAcceptance": False, "errors": [str(error)], "coverage": COVERAGE}
    print(json.dumps(receipt, indent=2))
    return 0 if receipt["gateVerdict"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
