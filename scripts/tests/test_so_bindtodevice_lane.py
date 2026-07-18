#!/usr/bin/env python3
"""Regression contracts for the physical SO_BINDTODEVICE lane."""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BUILD_WRAPPER = ROOT / "scripts/ci/build-so-bindtodevice-e2e.sh"
RUNTIME_WRAPPER = ROOT / "scripts/ci/run-so-bindtodevice-e2e.sh"
RUST_TEST = (
    ROOT
    / "native/rust/crates/ripdpi-tunnel-core/tests/so_bindtodevice_e2e.rs"
)
WORKFLOW = ROOT / ".github/workflows/ci.yml"
PHASES = (
    "ipv4_direct_tcp",
    "ipv4_direct_udp",
    "ipv4_allowed_tcp",
    "ipv4_allowed_udp",
    "ipv4_denied_tcp",
    "ipv4_denied_udp",
    "ipv6_direct_tcp",
    "ipv6_direct_udp",
    "ipv6_allowed_tcp",
    "ipv6_allowed_udp",
    "ipv6_denied_tcp",
    "ipv6_denied_udp",
)


def write_executable(path: Path, source: str) -> None:
    path.write_text(source, encoding="utf-8")
    path.chmod(0o755)


def canonical_pass_manifest() -> str:
    phases = []
    for phase_id in PHASES:
        family, contract, protocol = phase_id.split("_", 2)
        if contract == "direct":
            counts = (0, 0, 0, 0, 1)
        elif contract == "allowed":
            counts = (3, 2, 0, 1, 1)
        elif protocol == "tcp":
            counts = (3, 1, 1, 0, 0)
        else:
            counts = (1, 0, 0, 0, 0)
        inbound, outbound, resets, socks, peer = counts
        phases.append(
            {
                "family": family,
                "id": phase_id,
                "outcome": "PASS",
                "peerEvents": peer,
                "protocol": protocol,
                "socksEvents": socks,
                "tunInboundPackets": inbound,
                "tunOutboundPackets": outbound,
                "tunOutboundResets": resets,
            }
        )
    manifest = {
        "capabilities": {
            "ipv4": True,
            "ipv6": True,
            "realTun": True,
            "unprivilegedSoBindToDevice": True,
        },
        "cleanupVerified": True,
        "phases": phases,
        "provenance": {
            "sourceSha": "a" * 40,
            "testTarget": "so_bindtodevice_e2e",
            "workflowRunAttempt": "1",
            "workflowRunId": "123",
        },
        "result": "PASS",
        "version": "so_bindtodevice_tun_evidence_v1",
    }
    return json.dumps(manifest, sort_keys=True, separators=(",", ":"))


class SoBindToDeviceLaneTest(unittest.TestCase):
    def runtime_environment(
        self,
        *,
        root: Path,
        bin_dir: Path,
        test_binary: Path,
        evidence: Path,
    ) -> dict[str, str]:
        env = os.environ.copy()
        env["PATH"] = f"{bin_dir}:{env['PATH']}"
        env.update(
            {
                "RIPDPI_RUN_SO_BINDTODEVICE_E2E": "1",
                "RIPDPI_TUN_DEVICE": "/dev/null",
                "RIPDPI_SO_BIND_TEST_BINARY": str(test_binary),
                "RIPDPI_SO_BIND_EVIDENCE_PATH": str(evidence),
                "RIPDPI_EVIDENCE_SOURCE_SHA": "a" * 40,
                "RIPDPI_EVIDENCE_RUN_ID": "123",
                "RIPDPI_EVIDENCE_RUN_ATTEMPT": "1",
            }
        )
        return env

    def test_build_wrapper_resolves_exact_compiled_test_without_sudo(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            test_binary = root / "so_bindtodevice_e2e-deadbeef"
            write_executable(test_binary, "#!/usr/bin/env bash\nexit 0\n")
            cargo_log = root / "cargo.log"
            cargo_event = json.dumps(
                {
                    "reason": "compiler-artifact",
                    "target": {"name": "so_bindtodevice_e2e", "kind": ["test"]},
                    "executable": str(test_binary),
                }
            )
            write_executable(
                bin_dir / "cargo",
                "#!/usr/bin/env bash\n"
                "printf '%s\\n' \"$*\" > \"$FAKE_CARGO_LOG\"\n"
                "printf '%s\\n' \"$FAKE_CARGO_EVENT\"\n",
            )
            env = os.environ.copy()
            env["PATH"] = f"{bin_dir}:{env['PATH']}"
            env["FAKE_CARGO_LOG"] = str(cargo_log)
            env["FAKE_CARGO_EVENT"] = cargo_event

            completed = subprocess.run(
                ["bash", str(BUILD_WRAPPER)],
                capture_output=True,
                check=False,
                env=env,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(completed.stdout.strip(), str(test_binary.resolve()))
            cargo_args = cargo_log.read_text(encoding="utf-8")
            self.assertIn("test --locked --manifest-path", cargo_args)
            self.assertIn(
                "-p ripdpi-tunnel-core --test so_bindtodevice_e2e --no-run "
                "--message-format=json",
                cargo_args,
            )

    def test_build_wrapper_rejects_ambiguous_compiled_test(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            events = []
            for suffix in ("one", "two"):
                executable = root / f"so_bindtodevice_e2e-{suffix}"
                write_executable(executable, "#!/usr/bin/env bash\nexit 0\n")
                events.append(
                    json.dumps(
                        {
                            "reason": "compiler-artifact",
                            "target": {
                                "name": "so_bindtodevice_e2e",
                                "kind": ["test"],
                            },
                            "executable": str(executable),
                        }
                    )
                )
            write_executable(
                bin_dir / "cargo",
                "#!/usr/bin/env bash\nprintf '%s\\n' \"$FAKE_CARGO_EVENTS\"\n",
            )
            env = os.environ.copy()
            env["PATH"] = f"{bin_dir}:{env['PATH']}"
            env["FAKE_CARGO_EVENTS"] = "\n".join(events)

            completed = subprocess.run(
                ["bash", str(BUILD_WRAPPER)],
                capture_output=True,
                check=False,
                env=env,
                text=True,
            )

            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("expected exactly one compiled", completed.stderr)

    def test_runtime_wrapper_executes_prebuilt_binary_without_cargo(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            args_log = root / "binary.args"
            cargo_log = root / "cargo.invoked"
            evidence = root / "evidence/manifest.json"
            write_executable(bin_dir / "uname", "#!/usr/bin/env bash\necho Linux\n")
            write_executable(bin_dir / "id", "#!/usr/bin/env bash\necho 0\n")
            write_executable(bin_dir / "setpriv", "#!/usr/bin/env bash\nexit 0\n")
            write_executable(
                bin_dir / "ip",
                "#!/usr/bin/env bash\n"
                "if [ \"$*\" = '-o link show' ]; then\n"
                "  echo '1: lo: <LOOPBACK,UP>: mtu 65536'\n"
                "fi\n"
                "exit 0\n",
            )
            write_executable(
                bin_dir / "cargo",
                "#!/usr/bin/env bash\ntouch \"$FAKE_CARGO_LOG\"\nexit 97\n",
            )
            test_binary = root / "so_bindtodevice_e2e-deadbeef"
            write_executable(
                test_binary,
                "#!/usr/bin/env bash\n"
                "set -euo pipefail\n"
                "printf '%s\\n' \"$*\" > \"$FAKE_BINARY_ARGS\"\n"
                "mkdir -p \"$(dirname \"$RIPDPI_SO_BIND_EVIDENCE_PATH\")\"\n"
                "printf '%s\\n' \"$FAKE_PASS_MANIFEST\" > \"$RIPDPI_SO_BIND_EVIDENCE_PATH\"\n",
            )
            env = self.runtime_environment(
                root=root,
                bin_dir=bin_dir,
                test_binary=test_binary,
                evidence=evidence,
            )
            env.update(
                {
                    "FAKE_BINARY_ARGS": str(args_log),
                    "FAKE_CARGO_LOG": str(cargo_log),
                    "FAKE_PASS_MANIFEST": canonical_pass_manifest(),
                }
            )

            completed = subprocess.run(
                ["bash", str(RUNTIME_WRAPPER)],
                capture_output=True,
                check=False,
                env=env,
                text=True,
            )

            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(
                args_log.read_text(encoding="utf-8").strip(),
                "e2e_so_bindtodevice_tun_uid_guard --ignored --exact --nocapture",
            )
            self.assertTrue(evidence.is_file())
            self.assertFalse(cargo_log.exists(), "runtime wrapper must not invoke Cargo")

    def test_runtime_wrapper_classifies_missing_tun_as_infra_gap(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            evidence = root / "evidence" / "manifest.json"
            missing_tun = root / "missing-tun"
            write_executable(bin_dir / "uname", "#!/usr/bin/env bash\necho Linux\n")
            write_executable(bin_dir / "id", "#!/usr/bin/env bash\necho 0\n")
            test_binary = root / "so_bindtodevice_e2e-deadbeef"
            write_executable(test_binary, "#!/usr/bin/env bash\nexit 0\n")
            env = self.runtime_environment(
                root=root,
                bin_dir=bin_dir,
                test_binary=test_binary,
                evidence=evidence,
            )
            env["RIPDPI_TUN_DEVICE"] = str(missing_tun)

            completed = subprocess.run(
                ["bash", str(RUNTIME_WRAPPER)],
                capture_output=True,
                check=False,
                env=env,
                text=True,
            )

            self.assertEqual(completed.returncode, 75, completed.stderr)
            manifest = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(manifest["result"], "INFRA_GAP")
            self.assertEqual(manifest["reasonCode"], "TUN_UNAVAILABLE")
            self.assertFalse(manifest["cleanupVerified"])

    def test_runtime_wrapper_probes_cap_net_admin(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            evidence = root / "evidence" / "manifest.json"
            write_executable(bin_dir / "uname", "#!/usr/bin/env bash\necho Linux\n")
            write_executable(bin_dir / "id", "#!/usr/bin/env bash\necho 0\n")
            write_executable(bin_dir / "setpriv", "#!/usr/bin/env bash\nexit 0\n")
            write_executable(
                bin_dir / "ip",
                "#!/usr/bin/env bash\n"
                "if [ \"${1:-}\" = 'link' ] && [ \"${2:-}\" = 'add' ]; then exit 1; fi\n"
                "exit 0\n",
            )
            test_binary = root / "so_bindtodevice_e2e-deadbeef"
            write_executable(test_binary, "#!/usr/bin/env bash\nexit 0\n")
            env = self.runtime_environment(
                root=root,
                bin_dir=bin_dir,
                test_binary=test_binary,
                evidence=evidence,
            )

            completed = subprocess.run(
                ["bash", str(RUNTIME_WRAPPER)],
                capture_output=True,
                check=False,
                env=env,
                text=True,
            )

            self.assertEqual(completed.returncode, 75, completed.stderr)
            manifest = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(manifest["result"], "INFRA_GAP")
            self.assertEqual(manifest["reasonCode"], "PRIVILEGE_MISSING")

    def test_runtime_wrapper_classifies_capability_probe_cleanup_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            evidence = root / "evidence" / "manifest.json"
            ip_log = root / "ip.log"
            ip_state = root / "ip.state"
            ip_delete_count = root / "ip-delete-count"
            write_executable(bin_dir / "uname", "#!/usr/bin/env bash\necho Linux\n")
            write_executable(bin_dir / "id", "#!/usr/bin/env bash\necho 0\n")
            write_executable(bin_dir / "setpriv", "#!/usr/bin/env bash\nexit 0\n")
            write_executable(
                bin_dir / "ip",
                "#!/usr/bin/env bash\n"
                "printf '%s\\n' \"$*\" >> \"$FAKE_IP_LOG\"\n"
                "if [ \"${1:-}\" = 'link' ] && [ \"${2:-}\" = 'add' ]; then\n"
                "  printf '%s\\n' \"$3\" > \"$FAKE_IP_STATE\"\n"
                "fi\n"
                "if [ \"${1:-}\" = 'link' ] && [ \"${2:-}\" = 'del' ]; then\n"
                "  count=0; [ ! -f \"$FAKE_IP_DELETE_COUNT\" ] || count=$(cat \"$FAKE_IP_DELETE_COUNT\")\n"
                "  count=$((count + 1)); printf '%s\\n' \"$count\" > \"$FAKE_IP_DELETE_COUNT\"\n"
                "  if [ \"$count\" -eq 1 ]; then exit 1; fi\n"
                "  rm -f \"$FAKE_IP_STATE\"; exit 0\n"
                "fi\n"
                "if [ \"$*\" = '-o link show' ] && [ -f \"$FAKE_IP_STATE\" ]; then\n"
                "  echo \"99: $(cat \"$FAKE_IP_STATE\"): <BROADCAST>: mtu 1500\"\n"
                "fi\n"
                "exit 0\n",
            )
            test_binary = root / "so_bindtodevice_e2e-deadbeef"
            write_executable(test_binary, "#!/usr/bin/env bash\nexit 0\n")
            env = self.runtime_environment(
                root=root,
                bin_dir=bin_dir,
                test_binary=test_binary,
                evidence=evidence,
            )
            env.update(
                {
                    "FAKE_IP_LOG": str(ip_log),
                    "FAKE_IP_STATE": str(ip_state),
                    "FAKE_IP_DELETE_COUNT": str(ip_delete_count),
                }
            )

            completed = subprocess.run(
                ["bash", str(RUNTIME_WRAPPER)],
                capture_output=True,
                check=False,
                env=env,
                text=True,
            )

            self.assertEqual(completed.returncode, 1, completed.stderr)
            manifest = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(manifest["result"], "TEST_FAILURE")
            self.assertEqual(manifest["reasonCode"], "CLEANUP_FAILED")
            self.assertFalse(manifest["cleanupVerified"])
            self.assertFalse(ip_state.exists(), "EXIT trap must remove the owned probe")
            self.assertEqual(
                sum("link del rdcap" in line for line in ip_log.read_text(encoding="utf-8").splitlines()),
                2,
            )

    def test_runtime_wrapper_classifies_adversarial_test_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            evidence = root / "evidence" / "manifest.json"
            write_executable(bin_dir / "uname", "#!/usr/bin/env bash\necho Linux\n")
            write_executable(bin_dir / "id", "#!/usr/bin/env bash\necho 0\n")
            write_executable(bin_dir / "setpriv", "#!/usr/bin/env bash\nexit 0\n")
            write_executable(
                bin_dir / "ip",
                "#!/usr/bin/env bash\n"
                "if [ \"$*\" = '-o link show' ]; then\n"
                "  echo '1: lo: <LOOPBACK,UP>: mtu 65536'\n"
                "fi\n"
                "exit 0\n",
            )
            test_binary = root / "so_bindtodevice_e2e-deadbeef"
            write_executable(test_binary, "#!/usr/bin/env bash\nexit 23\n")
            env = self.runtime_environment(
                root=root,
                bin_dir=bin_dir,
                test_binary=test_binary,
                evidence=evidence,
            )

            completed = subprocess.run(
                ["bash", str(RUNTIME_WRAPPER)],
                capture_output=True,
                check=False,
                env=env,
                text=True,
            )

            self.assertEqual(completed.returncode, 23, completed.stderr)
            manifest = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(manifest["result"], "TEST_FAILURE")
            self.assertEqual(manifest["reasonCode"], "ADVERSARIAL_TEST_FAILED")
            self.assertTrue(manifest["cleanupVerified"])

    def test_runtime_wrapper_replaces_invalid_success_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            evidence = root / "evidence" / "manifest.json"
            write_executable(bin_dir / "uname", "#!/usr/bin/env bash\necho Linux\n")
            write_executable(bin_dir / "id", "#!/usr/bin/env bash\necho 0\n")
            write_executable(bin_dir / "setpriv", "#!/usr/bin/env bash\nexit 0\n")
            write_executable(
                bin_dir / "ip",
                "#!/usr/bin/env bash\n"
                "if [ \"$*\" = '-o link show' ]; then\n"
                "  echo '1: lo: <LOOPBACK,UP>: mtu 65536'\n"
                "fi\n"
                "exit 0\n",
            )
            test_binary = root / "so_bindtodevice_e2e-deadbeef"
            write_executable(
                test_binary,
                "#!/usr/bin/env bash\n"
                "mkdir -p \"$(dirname \"$RIPDPI_SO_BIND_EVIDENCE_PATH\")\"\n"
                "printf '%s\\n' '{\"result\":\"PASS\"}' > \"$RIPDPI_SO_BIND_EVIDENCE_PATH\"\n",
            )
            env = self.runtime_environment(
                root=root,
                bin_dir=bin_dir,
                test_binary=test_binary,
                evidence=evidence,
            )

            completed = subprocess.run(
                ["bash", str(RUNTIME_WRAPPER)],
                capture_output=True,
                check=False,
                env=env,
                text=True,
            )

            self.assertEqual(completed.returncode, 1, completed.stderr)
            manifest = json.loads(evidence.read_text(encoding="utf-8"))
            self.assertEqual(manifest["result"], "TEST_FAILURE")
            self.assertEqual(manifest["reasonCode"], "EVIDENCE_INVALID")
            self.assertTrue(manifest["cleanupVerified"])

    def test_rust_helper_drops_every_capability_and_supplementary_group(self) -> None:
        source = RUST_TEST.read_text(encoding="utf-8")
        self.assertNotIn("CommandExt", source)
        self.assertNotIn(".groups(", source)
        for argument in (
            '"--clear-groups"',
            '"--inh-caps=-all"',
            '"--ambient-caps=-all"',
            '"--bounding-set=-all"',
            '"--no-new-privs"',
        ):
            self.assertIn(argument, source)
        self.assertIn(
            '["CapInh:", "CapPrm:", "CapEff:", "CapBnd:", "CapAmb:"]',
            source,
        )
        self.assertIn("assert!(groups.is_empty()", source)
        self.assertIn('assert_eq!(no_new_privileges, "1"', source)

    def test_ipv6_topology_disables_dad_before_peer_helper_binds(self) -> None:
        source = RUST_TEST.read_text(encoding="utf-8")
        host_address = source.index(
            '"2001:db8::1/64", "dev", &topology.host_veth, "nodad"'
        )
        peer_address = source.index(
            '"2001:db8::2/64", "dev", &topology.peer_veth, "nodad"'
        )
        helper_spawn = source.index("let current_exe = std::env::current_exe()")

        self.assertLess(host_address, helper_spawn)
        self.assertLess(peer_address, helper_spawn)

    def test_workflow_builds_as_runner_then_restores_evidence_ownership(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        build_start = workflow.index(
            "- name: Build SO_BINDTODEVICE adversarial E2E as runner user"
        )
        runtime_start = workflow.index(
            "- name: Run unprivileged SO_BINDTODEVICE adversarial E2E",
            build_start,
        )
        finalizer_start = workflow.index(
            "- name: Finalize classified SO_BINDTODEVICE evidence",
            runtime_start,
        )
        validate_start = workflow.index(
            "- name: Validate redacted SO_BINDTODEVICE evidence as runner user",
            finalizer_start,
        )
        upload_start = workflow.index(
            "- name: Upload redacted SO_BINDTODEVICE evidence",
            validate_start,
        )
        build_step = workflow[build_start:runtime_start]
        runtime_step = workflow[runtime_start:finalizer_start]
        validate_step = workflow[validate_start:upload_start]
        self.assertIn("build-so-bindtodevice-e2e.sh", build_step)
        self.assertNotIn("sudo", build_step)
        self.assertIn("RIPDPI_SO_BIND_TEST_BINARY", runtime_step)
        self.assertNotIn("CARGO_HOME", runtime_step)
        self.assertNotIn("RUSTUP_HOME", runtime_step)
        self.assertIn('sudo chown -R "$(id -u):$(id -g)"', runtime_step)
        self.assertIn("check_so_bindtodevice_evidence.py", validate_step)
        self.assertIn("if: always()", validate_step)

    def test_workflow_finalizes_setup_and_build_failures_before_validation(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        finalizer_start = workflow.index("- name: Finalize classified SO_BINDTODEVICE evidence")
        validate_start = workflow.index(
            "- name: Validate redacted SO_BINDTODEVICE evidence as runner user",
            finalizer_start,
        )
        finalizer = workflow[finalizer_start:validate_start]

        self.assertIn("if: always()", finalizer)
        self.assertIn("steps.so_bind_prerequisites.outcome", finalizer)
        self.assertIn("steps.so_bind_build.outcome", finalizer)
        self.assertIn("steps.so_bind_runtime.outcome", finalizer)
        self.assertIn("--finalize-prerequisite-outcome", finalizer)


if __name__ == "__main__":
    unittest.main()
