#!/usr/bin/env python3
"""Adversarial contracts for the source-owned ordinary physical producer."""

from __future__ import annotations

import copy
import hashlib
import io
import json
import socket
import struct
import threading
import unittest
from pathlib import Path
from unittest import mock

from scripts.ci import android_ordinary_dns_fixture as dns_fixture
from scripts.ci import android_ordinary_marker_relay as marker_relay
from scripts.ci import android_ordinary_physical_attestation as attestation
from scripts.ci import android_ordinary_raw_evidence as raw_evidence
from scripts.ci import android_ordinary_semantic_oracles as oracles
from scripts.ci import produce_android_ordinary_gate_results as gate_producer
from scripts.ci import produce_android_ordinary_physical_evidence as physical_producer
from scripts.tests import android_ordinary_semantic_fixtures as fixtures


def dns_query(name: str, query_type: int = 28) -> bytes:
    labels = b"".join(
        bytes((len(label),)) + label.encode() for label in name.split(".")
    )
    return (
        struct.pack("!HHHHHH", 7, 0x0100, 1, 0, 0, 0)
        + labels
        + b"\0"
        + struct.pack("!HH", query_type, 1)
    )


def der(tag: int, payload: bytes) -> bytes:
    if len(payload) < 0x80:
        length = bytes((len(payload),))
    else:
        encoded = len(payload).to_bytes((len(payload).bit_length() + 7) // 8, "big")
        length = bytes((0x80 | len(encoded),)) + encoded
    return bytes((tag,)) + length + payload


class AndroidOrdinaryPhysicalProducerTest(unittest.TestCase):
    source_sha = "a" * 40
    app_sha = hashlib.sha256(b"app").hexdigest()
    test_sha = hashlib.sha256(b"test").hexdigest()
    run_id = hashlib.sha256(b"physical-run").hexdigest()

    def test_release_instrumentation_keeps_test_only_runtime_boundary(self) -> None:
        runner = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/HiltTestRunner.kt"
        ).read_text()
        rules = Path("app/proguard-rules.pro").read_text()
        generated_rules = Path("app/release-instrumentation-rules.pro").read_text()

        self.assertIn(
            '"com.poyka.ripdpi.RipDpiCustomTestApplication_Application"',
            runner,
        )
        self.assertNotIn(
            "RipDpiCustomTestApplication_Application::class.java.name",
            runner,
        )
        for required_rule in (
            "-keep class androidx.work.Configuration { *; }",
            "-keep interface androidx.work.Configuration$Provider",
            "-keep interface dagger.hilt.internal.GeneratedComponentManager { *; }",
            "-keep interface dagger.hilt.internal.TestSingletonComponent { *; }",
            "-keep interface dagger.hilt.internal.TestSingletonComponentManager { *; }",
            "-keep class dagger.hilt.internal.Preconditions { *; }",
            "-keep public class kotlin.** {",
            "-keep class kotlinx.coroutines.BuildersKt { public *; }",
            "-keep class com.poyka.ripdpi.core.Tun2SocksBridgeFactoryModule { *; }",
        ):
            with self.subTest(required_rule=required_rule):
                self.assertIn(required_rule, rules)
        self.assertIn(
            "-keep,allowoptimization,allowobfuscation class "
            "dagger.hilt.android.flags.FragmentGetContextFix$*",
            generated_rules,
        )
        self.assertIn(
            "-keepclassmembers class "
            "dagger.hilt.android.flags.FragmentGetContextFix$* { *; }",
            generated_rules,
        )
        self.assertIn(
            "-keepclassmembers class "
            "com.poyka.ripdpi.RipDpiApp_MembersInjector { *; }",
            generated_rules,
        )
        self.assertIn(
            "-keepclassmembers class "
            "com.poyka.ripdpi.data.ServiceTelemetrySnapshot { *; }",
            generated_rules,
        )
        self.assertIn(
            "-keepclassmembers class "
            "com.poyka.ripdpi.core.Tun2SocksConfig { *; }",
            generated_rules,
        )

    def test_marker_relay_forwards_exact_marker_and_acknowledges_capture(self) -> None:
        marker = b"RIPDPI-ORDINARY-V1:dual-stack:" + b"a" * 64 + b":outcome"
        client, server = socket.socketpair()
        with (
            socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as observer,
            client,
            server,
        ):
            observer.bind(("127.0.0.1", 0))
            observer.settimeout(2)
            worker = threading.Thread(
                target=marker_relay.handle_connection,
                kwargs={
                    "connection": server,
                    "destination": observer.getsockname(),
                },
            )
            worker.start()
            client.sendall(marker + b"\n")
            self.assertEqual(observer.recv(512), marker)
            self.assertEqual(client.recv(16), b"OK\n")
            worker.join(timeout=2)
            self.assertFalse(worker.is_alive())

    def test_marker_relay_rejects_unbounded_or_injected_payloads(self) -> None:
        valid = b"RIPDPI-ORDINARY-V1:ipv4-only:" + b"b" * 64 + b":action"
        self.assertEqual(marker_relay.validated_marker(valid + b"\n"), valid)
        for payload in (
            b"",
            valid + b";id",
            valid.replace(b":action", b":unknown"),
            b"RIPDPI-ORDINARY-V1:ipv4-only:" + b"b" * 63 + b":action",
            b"x" * (marker_relay.MAX_MARKER_BYTES + 1),
        ):
            with self.subTest(payload=payload[:32]):
                with self.assertRaises(ValueError):
                    marker_relay.validated_marker(payload)

    def test_marker_relay_waits_for_successful_forward_command(self) -> None:
        marker = b"RIPDPI-ORDINARY-V1:dual-stack:" + b"c" * 64 + b":action"
        client, server = socket.socketpair()
        completed = marker_relay.subprocess.CompletedProcess(("ssh",), 0)
        with (
            client,
            server,
            mock.patch.object(
                marker_relay.subprocess, "run", return_value=completed
            ) as run,
        ):
            worker = threading.Thread(
                target=marker_relay.handle_connection,
                kwargs={
                    "connection": server,
                    "forward_command": ("ssh", "fixture", "sender"),
                },
            )
            worker.start()
            client.sendall(marker + b"\n")
            self.assertEqual(client.recv(16), b"OK\n")
            worker.join(timeout=2)
            self.assertFalse(worker.is_alive())
            self.assertEqual(run.call_args.args[0], ("ssh", "fixture", "sender"))
            self.assertEqual(run.call_args.kwargs["input"], marker)
            self.assertFalse(run.call_args.kwargs["check"])

    def test_marker_relay_rejects_failed_forward_command(self) -> None:
        marker = b"RIPDPI-ORDINARY-V1:dual-stack:" + b"d" * 64 + b":outcome"
        client, server = socket.socketpair()
        completed = marker_relay.subprocess.CompletedProcess(("ssh",), 1)
        with (
            client,
            server,
            mock.patch.object(marker_relay.subprocess, "run", return_value=completed),
        ):
            worker = threading.Thread(
                target=marker_relay.handle_connection,
                kwargs={
                    "connection": server,
                    "forward_command": ("ssh", "fixture", "sender"),
                },
            )
            worker.start()
            client.sendall(marker + b"\n")
            self.assertEqual(client.recv(16), b"ERROR\n")
            worker.join(timeout=2)
            self.assertFalse(worker.is_alive())

    def test_runner_does_not_require_coordinator_network_for_fixture_preflight(
        self,
    ) -> None:
        runner = Path(
            "scripts/ci/run-android-ordinary-physical-evidence.sh"
        ).read_text()
        self.assertNotRegex(runner, r"(?m)^nc -[46] ")
        self.assertRegex(
            runner,
            r'ssh_remote\[@\].*socket\.create_connection\(\(\\"::1\\"',
        )

    def test_runner_suspends_and_restores_the_existing_always_on_vpn(self) -> None:
        runner = Path(
            "scripts/ci/run-android-ordinary-physical-evidence.sh"
        ).read_text()
        saved = runner.index('always_on_before=""')
        suspended = runner.rindex("settings delete secure always_on_vpn_app")
        instrumented = runner.index("shell am instrument")
        restored = runner.index(
            'settings put secure always_on_vpn_app "$always_on_before"'
        )
        self.assertLess(saved, suspended)
        self.assertLess(suspended, instrumented)
        self.assertLess(restored, suspended)
        self.assertIn('am force-stop "$always_on_before"', runner)

    def test_runner_opens_only_runtime_nftables_rules_and_removes_by_marker(
        self,
    ) -> None:
        runner = Path(
            "scripts/ci/run-android-ordinary-physical-evidence.sh"
        ).read_text()
        self.assertNotIn("sudo ufw", runner)
        self.assertIn("nft insert rule inet filter input tcp dport", runner)
        self.assertIn("nft insert rule inet filter input udp dport", runner)
        self.assertIn("$marker_port, $dns_port, $socks_port", runner)
        self.assertIn("nft delete rule inet filter input handle", runner)
        self.assertIn("awk -v marker='$nft_comment'", runner)

    def test_physical_producer_forces_the_test_process_into_the_vpn(self) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        configure = producer[producer.index("private fun configureAndStart") :]
        configure = configure[: configure.index("private fun requestVpnStop")]
        self.assertIn("fullTunnelMode = true", configure)
        self.assertIn("setSplitTunnelMode(SplitTunnelMode.Off)", configure)
        self.assertIn("clearSplitTunnelPackages()", configure)

    def test_lockdown_convergence_uses_socket_outcomes_not_network_visibility(
        self,
    ) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        lockdown = producer[producer.index("private fun awaitTestProcessLockdown") :]
        lockdown = lockdown[: lockdown.index("private fun probe")]
        self.assertIn("awaitStable(", lockdown)
        self.assertIn("stablePollCount = 3", lockdown)
        self.assertEqual(lockdown.count("testProcessTcpConnect("), 2)
        self.assertIn("!ipv4.ok && !ipv6.ok", lockdown)
        self.assertNotIn("awaitNoDefaultNetwork", lockdown)

    def test_action_boundary_reconfigures_inside_the_protected_tunnel(self) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        configure = producer[producer.index("private fun configureAndStart") :]
        configure = configure[: configure.index("private fun requestVpnStop")]
        self.assertIn("OrdinaryProtectedRestartAction", configure)
        self.assertIn("transport_failover_restart", producer)
        self.assertIn(
            "wasIpv6Enabled == ipv6 -> OrdinaryProtectedRestartAction", configure
        )
        self.assertIn("else -> null", configure)
        self.assertIn("requestedAction?.let", configure)
        self.assertIn("hasIpv6 == ipv6", configure)
        self.assertIn(
            "val completedStartCount = tunnelFactory.completedStartCount()", configure
        )
        self.assertIn(
            "tunnelFactory.completedStartAfter(completedStartCount, ipv6)",
            configure,
        )
        self.assertNotIn("ACTIVATE_VPN ignore", configure)

    def test_action_boundary_waits_for_the_matching_native_bridge_generation(
        self,
    ) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        bridge = producer[
            producer.index("private class OrdinaryTun2SocksBridgeFactory") :
        ]
        bridge = bridge[: bridge.index("class OrdinaryFaultingProxyFactory")]
        self.assertIn("completedStarts.get() > previousCount", bridge)
        self.assertIn("lastCompletedIpv6 == ipv6", bridge)
        start = bridge[bridge.index("override suspend fun start") :]
        completed_increment = start.index("completedStarts.incrementAndGet()")
        self.assertLess(start.index("delegate.start("), completed_increment)
        self.assertLess(
            start.index("lastCompletedIpv6 = config.tunnelIpv6 != null"),
            completed_increment,
        )

    def test_always_on_transitions_require_continuous_tunnel_protection(self) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        network = producer[producer.index("private fun runNetworkSwitchAction") :]
        network = network[: network.index("private fun runSleepWakeAction")]
        sleep = producer[producer.index("private fun runSleepWakeAction") :]
        sleep = sleep[: sleep.index("private fun runAlwaysOnProtectedAction")]
        always_on = producer[producer.index("private fun runAlwaysOnProtectedAction") :]
        always_on = always_on[: always_on.index("private fun configureAndStart")]
        revoke = producer[
            producer.index("private fun runPermissionRevokeProtectedAction") :
        ]
        revoke = revoke[: revoke.index("private fun runNetworkSwitchAction")]
        for action in (network, sleep, always_on, revoke):
            self.assertIn('capturePhase("protected", expectVpn = true)', action)
            self.assertNotIn("blockedTransitionProbes", action)
            self.assertNotIn("awaitTestProcessLockdown", action)
        self.assertIn("ACTIVATE_VPN ignore", revoke)
        self.assertIn('appOpState.contains("ignore")', revoke)
        self.assertIn(
            'probe("post-revoke-ipv4", "ipv4", controlIpv4, connected = true)', revoke
        )
        self.assertIn(
            'probe("post-revoke-ipv6", "ipv6", controlIpv6, connected = true)', revoke
        )

    def test_network_switch_waits_for_the_new_native_bridge_generation(self) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        network = producer[producer.index("private fun runNetworkSwitchAction") :]
        network = network[: network.index("private fun runSleepWakeAction")]

        generation = network.index(
            "val completedStartCount = tunnelFactory.completedStartCount()"
        )
        switch = network.index('shell("svc wifi disable")')
        completed = network.index(
            "tunnelFactory.completedStartAfter(completedStartCount, ipv6 = false)"
        )
        probe = network.index('probe("post-switch-ipv4"')

        self.assertLess(generation, switch)
        self.assertLess(switch, completed)
        self.assertLess(completed, probe)
        self.assertIn("awaitStable(", network)
        self.assertIn("pollMs = 250", network)
        self.assertIn("stablePollCount = 12", network)
        self.assertIn(
            "networkHandoverState == NetworkHandoverStates.Revalidated", network
        )

    def test_sleep_wake_requires_a_stable_runtime_on_both_boundaries(self) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        sleep = producer[producer.index("private fun runSleepWakeAction") :]
        sleep = sleep[: sleep.index("private fun runAlwaysOnProtectedAction")]
        helper = producer[producer.index("private fun awaitStablePhysicalRuntime") :]
        helper = helper[: helper.index("private fun configureAndStart")]

        before_sleep = sleep.index("configureAndStart(ipv6 = false)")
        started = sleep.index("val started = now()")
        wake = sleep.index('shell("input keyevent KEYCODE_WAKEUP")')
        after_wake = sleep.index(
            'awaitStablePhysicalRuntime("after wake", requireInteractive = true)'
        )
        probe = sleep.index('probe("post-wake-ipv4"')

        self.assertLess(before_sleep, started)
        self.assertLess(wake, after_wake)
        self.assertLess(after_wake, probe)
        self.assertIn("awaitStable(", helper)
        self.assertIn("pollMs = 250", helper)
        self.assertIn("stablePollCount = 12", helper)
        self.assertIn("powerManager.isInteractive", helper)
        self.assertIn("generationUnchanged", helper)

    def test_configure_waits_for_a_stable_physical_runtime_generation(self) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        configure = producer[producer.index("private fun configureAndStart") :]
        configure = configure[: configure.index("private fun requestVpnStop")]

        completed = configure.index(
            "tunnelFactory.completedStartAfter(completedStartCount, ipv6)"
        )
        settled = configure.index(
            'awaitStablePhysicalRuntime("after configure", requireInteractive = false)'
        )
        interface = configure.index("lastVpnInterface =")

        self.assertLess(completed, settled)
        self.assertLess(settled, interface)

    def test_physical_producer_compares_aaaa_answers_by_address_bytes(self) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        self.assertIn("observed?.contentEquals(expected) == true", producer)
        self.assertIn('.put("answers", JSONArray(recordedAnswers))', producer)

    def test_physical_connected_probes_require_an_exact_tcp_round_trip(self) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        probe = producer[producer.index("private fun probe(") :]
        probe = probe[: probe.index("private fun normalizedFailure")]
        self.assertIn("testProcessTcpRoundTrip(", probe)
        self.assertIn("result.ok && result.response == payload", probe)
        self.assertNotIn("testProcessTcpConnect(", probe)

    def test_release_target_owns_the_test_probe_signature_permission(self) -> None:
        main_manifest = Path("app/src/main/AndroidManifest.xml").read_text()
        debug_manifest = Path("app/src/debug/AndroidManifest.xml").read_text()
        test_manifest = Path("app/src/androidTest/AndroidManifest.xml").read_text()
        permission = "com.poyka.ripdpi.permission.TEST_NETWORK_PROBE"

        self.assertIn(
            f'<permission\n        android:name="{permission}"\n'
            '        android:protectionLevel="signature" />',
            main_manifest,
        )
        self.assertIn(
            f'<uses-permission android:name="{permission}" />', main_manifest
        )
        self.assertIn(f'android:permission="{permission}"', test_manifest)
        self.assertNotIn(permission, debug_manifest)

    def test_tcp_round_trip_reads_echo_before_half_closing_the_socket(self) -> None:
        receiver = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "TestNetworkProbeReceiver.kt"
        ).read_text()
        tcp_probe = receiver[receiver.index("private fun runTcpProbe(") :]
        tcp_probe = tcp_probe[: tcp_probe.index("private fun runUdpProbe(")]

        self.assertIn("output.write(payloadBytes)", tcp_probe)
        self.assertIn("readTcpProbeResponse(input, payloadBytes.size)", tcp_probe)
        self.assertNotIn("shutdownOutput()", tcp_probe)

    def test_aaaa_probe_runs_outside_the_vpn_owner_and_captures_direct_leaks(
        self,
    ) -> None:
        producer = Path(
            "app/src/androidTest/kotlin/com/poyka/ripdpi/e2e/"
            "AndroidOrdinaryPhysicalEvidenceTest.kt"
        ).read_text()
        runner = Path(
            "scripts/ci/run-android-ordinary-physical-evidence.sh"
        ).read_text()
        self.assertIn("bindTestProcessDnsProbeService(", producer)
        self.assertIn("probeService.dnsProbe(", producer)
        self.assertIn("queryType = DnsQueryTypeAaaa", producer)
        self.assertIn("transport = DnsTransportTcp", producer)
        self.assertNotIn("private fun ordinaryAaaaQuery", producer)
        self.assertIn("(udp port $dns_port)", runner)
        self.assertIn("(tcp port $dns_port)", runner)
        self.assertIn("/usr/bin/tcpdump -i any", runner)
        self.assertIn("android_ordinary_marker_relay.py", runner)
        self.assertIn('--forward-command "${marker_forward_command[@]}"', runner)
        self.assertIn('ssh -S "$marker_ssh_control"', runner)
        self.assertNotIn('-L "127.0.0.1:$marker_relay_port', runner)
        self.assertIn('reverse "tcp:$marker_relay_port"', runner)
        self.assertIn('reverse --remove "tcp:$marker_relay_port"', runner)
        self.assertIn('requiredPort("ripdpi.ordinaryMarkerRelayPort")', producer)
        self.assertIn("Socket().use { socket ->", producer)
        self.assertNotIn("toybox nc -u", producer)
        self.assertIn("--fixture-tailscale-proxy", runner)
        self.assertIn('"ProxyCommand=$tailscale_path nc %h %p"', runner)
        self.assertIn('"HostKeyAlias=$fixture_host_key_alias"', runner)
        self.assertIn("StrictHostKeyChecking=yes", runner)
        self.assertIn("collect_failure_diagnostics", runner)
        self.assertIn("failed-fixture-observer.pcap", runner)
        self.assertIn("failed-fixture-journal.txt", runner)
        self.assertIn("settings put secure always_on_vpn_app com.poyka.ripdpi", runner)
        self.assertIn("settings put secure always_on_vpn_lockdown 1", runner)
        self.assertGreaterEqual(runner.count('"${adb[@]}" reboot'), 2)
        self.assertIn("wait_for_device_boot", runner)
        self.assertIn("State: RUNNING_UNLOCKED", runner)
        self.assertIn("local timeout_seconds=86400", runner)
        target_reboot = runner.index(
            '"${adb[@]}" reboot', runner.index("vpn_manager_rebooted=1")
        )
        target_unlock = runner.index("wait_for_user_unlock", target_reboot)
        instrumentation = runner.index("shell am instrument", target_reboot)
        self.assertLess(target_reboot, target_unlock)
        self.assertLess(target_unlock, instrumentation)
        self.assertNotIn(
            "LocalFixtureClient.fromInstrumentationArgs().manifest()", producer
        )
        self.assertIn('requiredPort("ripdpi.ordinaryTcpEchoPort")', producer)
        self.assertIn('requiredPort("ripdpi.ordinarySocksPort")', producer)
        self.assertIn('-e ripdpi.ordinaryTcpEchoPort "$tcp_echo_port"', runner)
        self.assertIn('-e ripdpi.ordinarySocksPort "$socks_port"', runner)
        fixture_start = runner.index("remote_started=1")
        self.assertLess(target_unlock, fixture_start)
        self.assertLess(fixture_start, instrumentation)

    def test_dns_fixture_returns_empty_ipv4_only_and_exact_dual_stack_aaaa(
        self,
    ) -> None:
        ipv6 = "2001:db8::44"
        empty = dns_fixture.build_response(
            dns_query(dns_fixture.IPV4_ONLY_NAME), dual_stack_ipv6=ipv6
        )
        dual = dns_fixture.build_response(
            dns_query(dns_fixture.DUAL_STACK_NAME), dual_stack_ipv6=ipv6
        )
        self.assertEqual(struct.unpack("!H", empty[6:8])[0], 0)
        self.assertEqual(struct.unpack("!H", dual[6:8])[0], 1)
        self.assertEqual(dual[-16:], __import__("ipaddress").IPv6Address(ipv6).packed)

    def test_dns_fixture_frames_exact_tcp_aaaa_response(self) -> None:
        ipv6 = "2001:db8::44"
        query = dns_query(dns_fixture.DUAL_STACK_NAME)
        framed = struct.pack("!H", len(query)) + query
        response = dns_fixture.build_tcp_response(framed, dual_stack_ipv6=ipv6)
        self.assertEqual(struct.unpack("!H", response[:2])[0], len(response) - 2)
        self.assertEqual(
            response[-16:], __import__("ipaddress").IPv6Address(ipv6).packed
        )
        with self.assertRaisesRegex(ValueError, "length is invalid"):
            dns_fixture.build_tcp_response(b"\0\x01bad", dual_stack_ipv6=ipv6)

    def test_stalled_tcp_dns_client_does_not_starve_valid_query(self) -> None:
        ipv6 = "2001:db8::44"
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen(4)
            threading.Thread(
                target=dns_fixture.serve_tcp_connections,
                args=(listener, ipv6),
                daemon=True,
            ).start()
            with mock.patch("sys.stdout", new=io.StringIO()):
                with socket.create_connection(listener.getsockname(), timeout=1):
                    with socket.create_connection(
                        listener.getsockname(), timeout=1
                    ) as valid:
                        query = dns_query(dns_fixture.DUAL_STACK_NAME)
                        valid.sendall(struct.pack("!H", len(query)) + query)
                        valid.settimeout(1)
                        response_size = struct.unpack(
                            "!H", dns_fixture.receive_exact(valid, 2)
                        )[0]
                        response = dns_fixture.receive_exact(valid, response_size)

        self.assertEqual(len(response), response_size)
        self.assertEqual(response[6:8], b"\0\x01")

    def test_dns_fixture_refuses_unknown_and_malformed_questions(self) -> None:
        nxdomain = dns_fixture.build_response(
            dns_query("caller.example"), dual_stack_ipv6="2001:db8::44"
        )
        self.assertEqual(struct.unpack("!H", nxdomain[2:4])[0] & 0xF, 3)
        with self.assertRaisesRegex(ValueError, "truncated"):
            dns_fixture.build_response(b"short", dual_stack_ipv6="2001:db8::44")

    def test_key_description_requires_hardware_levels_and_exact_challenge(self) -> None:
        challenge = b"c" * 32
        description = der(
            0x30,
            der(0x02, b"\x04")
            + der(0x0A, b"\x02")
            + der(0x02, b"\x64")
            + der(0x0A, b"\x01")
            + der(0x04, challenge)
            + der(0x04, b""),
        )
        self.assertEqual(attestation._key_description(description), (2, 1, challenge))
        with self.assertRaisesRegex(ValueError, "schema differs"):
            attestation._key_description(
                description.replace(b"\x0a\x01\x02", b"\x02\x01\x02", 1)
            )

    def observations(self) -> dict:
        actions = []
        for index, spec in enumerate(raw_evidence.ACTION_SPECS):
            started = 1_800_000_000_000 + index * 2_000
            finished = started + 1_000
            correlation = hashlib.sha256(
                f"{self.run_id}:{spec.action_id}".encode()
            ).hexdigest()
            receipt = json.loads(
                fixtures.action_receipt(
                    spec.action_id,
                    correlation_id=correlation,
                    source_sha=self.source_sha,
                    app_sha256=self.app_sha,
                    test_sha256=self.test_sha,
                    started_at=started,
                    finished_at=finished,
                )
            )
            route = json.loads(
                fixtures.route_snapshot(
                    spec.action_id,
                    correlation_id=correlation,
                    source_sha=self.source_sha,
                    started_at=started,
                    finished_at=finished,
                )
            )
            actions.append(
                {
                    "actionId": spec.action_id,
                    "correlationId": correlation,
                    "dnsObservation": receipt["dnsObservation"],
                    "event": receipt["event"],
                    "fixture": receipt["fixture"],
                    "probes": receipt["probes"],
                    "routePhases": route["phases"],
                    "windowFinishedAtEpochMs": finished,
                    "windowStartedAtEpochMs": started,
                }
            )
        hardware = {
            "certificateChainDerBase64": ["leaf", "root"],
            "challengeSha256": attestation.expected_challenge(
                self.source_sha, self.app_sha, self.test_sha, self.run_id
            ).hex(),
            "requestedStrongBox": True,
            "version": attestation.HARDWARE_VERSION,
        }
        return {
            "actions": actions,
            "apiLevel": 37,
            "appApkSha256": self.app_sha,
            "deviceCodename": "husky",
            "deviceManufacturer": "Google",
            "hardwareAttestation": hardware,
            "kernelRelease": "6.1.0",
            "runId": self.run_id,
            "sourceSha": self.source_sha,
            "testApkSha256": self.test_sha,
            "version": physical_producer.OBSERVATION_VERSION,
        }

    def pass_inputs(self) -> tuple[dict, dict, dict]:
        observations = self.observations()
        documents = physical_producer.expected_raw_documents(observations)
        capture_sha = hashlib.sha256(b"physical-capture").hexdigest()
        action_proofs = {}
        gate_results = {}
        for spec in raw_evidence.ACTION_SPECS:
            receipt, route = documents[spec.action_id]
            action_proofs[spec.action_id] = {
                "artifacts": {
                    "action-receipt": hashlib.sha256(
                        oracles.canonical_json_bytes(receipt)
                    ).hexdigest(),
                    "packet-capture": capture_sha,
                    "route-snapshot": hashlib.sha256(
                        oracles.canonical_json_bytes(route)
                    ).hexdigest(),
                },
                "factsSha256": "f" * 64,
                "gateIds": list(spec.gate_ids),
            }
            gate_results.update(
                {gate_id: {"state": "PASS"} for gate_id in spec.gate_ids}
            )
        provenance = {
            "actionCount": 7,
            "actionProofs": action_proofs,
            "appApkSha256": self.app_sha,
            "artifactCount": 21,
            "manifestSha256": "b" * 64,
            "semanticVerifier": oracles.VERIFIER_VERSION,
            "testApkSha256": self.test_sha,
        }
        document = {
            "appApkSha256": self.app_sha,
            "captureSha256": capture_sha,
            "device": {
                "apiLevel": 37,
                "codename": "husky",
                "kernelRelease": "6.1.0",
                "manufacturer": "Google",
                "serialSha256": "d" * 64,
            },
            "hardwareAttestation": observations["hardwareAttestation"],
            "instrumentationTranscriptSha256": "e" * 64,
            "manifestSha256": "b" * 64,
            "observations": observations,
            "producerSha256": hashlib.sha256(
                Path(physical_producer.__file__).read_bytes()
            ).hexdigest(),
            "runId": self.run_id,
            "sourceSha": self.source_sha,
            "testApkSha256": self.test_sha,
            "version": attestation.ATTESTATION_VERSION,
        }
        return (
            provenance,
            {"actionProofs": action_proofs, "gateResults": gate_results},
            document,
        )

    def test_attested_semantics_are_the_only_pass_path(self) -> None:
        provenance, evaluation, document = self.pass_inputs()
        with mock.patch.object(
            gate_producer.android_ordinary_physical_attestation,
            "validate_physical_attestation",
            return_value={
                "producerSha256": document["producerSha256"],
                "runId": self.run_id,
            },
        ):
            results = gate_producer.semantic_verification_results(
                self.source_sha, provenance, evaluation, document
            )
        self.assertTrue(gate_producer.SOURCE_OWNED_PHYSICAL_PRODUCER_AVAILABLE)
        self.assertTrue(results["rawBundleProvenance"]["productionReady"])
        self.assertTrue(
            all(value == {"state": "PASS"} for value in results["gateResults"].values())
        )

    def test_copied_or_mutated_attestation_cannot_author_pass(self) -> None:
        provenance, evaluation, document = self.pass_inputs()
        results = gate_producer.semantic_verification_results(
            self.source_sha, provenance, evaluation
        )
        results["gateResults"] = {
            gate_id: {"state": "PASS"} for gate_id in gate_producer.ORDINARY_GATE_IDS
        }
        results["rawBundleProvenance"]["productionReady"] = True
        results["producerAttestation"] = document
        mutations = (
            lambda value: value["rawBundleProvenance"]["actionProofs"]["dual-stack"][
                "artifacts"
            ].update({"packet-capture": "0" * 64}),
            lambda value: value["producerAttestation"].update(
                {"producerSha256": "0" * 64}
            ),
            lambda value: value["producerAttestation"]["observations"]["actions"][
                0
            ].update({"correlationId": "0" * 64}),
        )
        for mutation in mutations:
            forged = copy.deepcopy(results)
            mutation(forged)
            with (
                self.subTest(mutation=mutation),
                mock.patch.object(
                    gate_producer.android_ordinary_physical_attestation,
                    "validate_physical_attestation",
                    side_effect=lambda attested, **_: {
                        "producerSha256": attested["producerSha256"],
                        "runId": attested["runId"],
                    },
                ),
                self.assertRaises(gate_producer.EvidenceError),
            ):
                gate_producer.validate_pass_results(forged)


if __name__ == "__main__":
    unittest.main()
