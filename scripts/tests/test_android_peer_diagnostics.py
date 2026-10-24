"""Exercise bounded failure receipts against real loopback HTTP endpoints."""

from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

SOURCE = Path(__file__).resolve().parents[1] / "ci/run-android-local-acceptance.py"
SPEC = importlib.util.spec_from_file_location("diagnostic_runner", SOURCE)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
import android_peer_diagnostics as diagnostics


@contextmanager
def peer(responder):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            try:
                responder(self)
            except (BrokenPipeError, ConnectionResetError):
                pass

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server.server_port
    finally:
        server.shutdown()
        server.server_close()
        thread.join(2)
        assert not thread.is_alive()


def respond(handler, payload, status=200):
    body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
    handler.send_response(status)
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


class PeerDiagnosticTests(unittest.TestCase):
    def capture(self, port, directory, name="main"):
        collector = diagnostics.OwnedPeerDiagnostics()
        collector.register(name, port)
        outcomes = collector.capture(Path(directory))
        receipts = {
            endpoint: json.loads(
                (Path(directory) / f"diagnostic-{name}-{endpoint}.json").read_text()
            )
            for endpoint in diagnostics.ENDPOINTS
        }
        return outcomes, receipts

    def test_capture_keeps_only_owned_query_and_bounded_transport_fields(self):
        payloads = {
            "/dns-receipts": {
                "errors": 0,
                "rejected": 7,
                "password": "private-password",
                "receipts": [
                    {"query": "acceptance.test.", "type": 1},
                    {"query": "acceptance.test.", "type": 28},
                    {"query": "private-background.example", "type": 1},
                ],
            },
            "/transport-receipts": [
                {
                    "id": "private-request-id",
                    "operation": "download",
                    "bytes": 1048576,
                    "elapsedMs": 123,
                    "sha256": "a" * 64,
                    "authorization": "private-credential",
                }
            ],
        }
        with (
            tempfile.TemporaryDirectory() as directory,
            peer(lambda h: respond(h, payloads[h.path])) as port,
        ):
            outcomes, receipts = self.capture(port, directory)
            self.assertEqual({"captured"}, set(outcomes.values()))
            dns = receipts["dns-receipts"]["receipt"]
            self.assertEqual(2, dns["count"])
            self.assertEqual(7, dns["rejected"])
            self.assertEqual([1, 28], [item["type"] for item in dns["receipts"]])
            transfer = receipts["transport-receipts"]["receipt"]["receipts"][0]
            self.assertEqual(1048576, transfer["bytes"])
            self.assertEqual("a" * 64, transfer["sha256"])
            public = json.dumps(receipts)
            self.assertNotIn("private-", public)
            self.assertNotIn(str(port), public)

    def test_xray_dns_count_does_not_expose_nonowned_last_query(self):
        for query, expected in [("owned.test.", True), ("private.example", False)]:
            with (
                self.subTest(query=query),
                tempfile.TemporaryDirectory() as directory,
                peer(
                    lambda h: respond(
                        h,
                        {"count": 2, "lastQuery": query}
                        if h.path == "/dns-receipts"
                        else [],
                    )
                ) as port,
            ):
                _, receipts = self.capture(port, directory)
                self.assertEqual(
                    {"count": 2, "ownedQueryObserved": expected},
                    receipts["dns-receipts"]["receipt"],
                )
                self.assertNotIn(query, json.dumps(receipts))

    def test_empty_owned_endpoint_set_does_no_network_or_file_work(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            patch.object(diagnostics, "_fetch") as fetch,
        ):
            self.assertEqual(
                {}, diagnostics.OwnedPeerDiagnostics().capture(Path(directory))
            )
            fetch.assert_not_called()
            self.assertEqual([], list(Path(directory).iterdir()))

    def test_fixed_peer_names_ports_and_duplicate_registration(self):
        collector = diagnostics.OwnedPeerDiagnostics()
        for name, port in [
            ("../private", 1),
            ("main", 0),
            ("main", 65536),
            ("main", True),
        ]:
            with self.assertRaises(ValueError):
                collector.register(name, port)
        collector.register("main", 1)
        with self.assertRaises(ValueError):
            collector.register("main", 2)

    def test_null_or_malformed_receipts_are_unavailable_not_empty_success(self):
        for payload in [
            None,
            b"not-json-private",
            {"count": True},
            {"receipts": None, "errors": 0},
        ]:
            with (
                self.subTest(payload=payload),
                tempfile.TemporaryDirectory() as directory,
                peer(lambda h: respond(h, payload)) as port,
            ):
                outcomes, receipts = self.capture(port, directory)
                self.assertEqual({"unavailable"}, set(outcomes.values()))
                self.assertNotIn("not-json-private", json.dumps(receipts))

    def test_response_size_bound(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            peer(
                lambda h: respond(h, b"x" * (diagnostics.MAX_RESPONSE_BYTES + 1))
            ) as port,
        ):
            _, receipts = self.capture(port, directory)
            self.assertEqual(
                {"response-too-large"}, {r["reason"] for r in receipts.values()}
            )

    def test_trickled_response_has_total_deadline_and_releases_timer(self):
        finished = threading.Event()

        def trickle(handler):
            handler.send_response(200)
            handler.send_header("Content-Length", "1000")
            handler.end_headers()
            try:
                for _ in range(1000):
                    handler.wfile.write(b" ")
                    handler.wfile.flush()
                    time.sleep(0.01)
            finally:
                finished.set()

        with (
            tempfile.TemporaryDirectory() as directory,
            peer(trickle) as port,
            patch.object(diagnostics, "CAPTURE_TIMEOUT_SECONDS", 0.1),
        ):
            started = time.monotonic()
            _, receipts = self.capture(port, directory)
            self.assertLess(time.monotonic() - started, 1.0)
            self.assertEqual(
                {"capture-timeout"}, {r["reason"] for r in receipts.values()}
            )
            self.assertTrue(finished.wait(1))

    def test_redirect_and_ambient_proxy_are_not_followed(self):
        redirected = []

        def redirect(handler):
            handler.send_response(302)
            handler.send_header("Location", f"http://127.0.0.1:{other}/private")
            handler.send_header("Content-Length", "0")
            handler.end_headers()

        with (
            peer(lambda h: (redirected.append(h.path), respond(h, []))) as other,
            peer(redirect) as port,
        ):
            with (
                tempfile.TemporaryDirectory() as directory,
                patch.dict(
                    os.environ,
                    {
                        "HTTP_PROXY": f"http://127.0.0.1:{other}",
                        "http_proxy": f"http://127.0.0.1:{other}",
                        "NO_PROXY": "",
                    },
                ),
            ):
                _, receipts = self.capture(port, directory)
                self.assertEqual(
                    {"http-status"}, {r["reason"] for r in receipts.values()}
                )
                self.assertEqual([], redirected)

    def test_closed_endpoint_and_write_failure_do_not_raise(self):
        with socket.socket() as closed:
            closed.bind(("127.0.0.1", 0))
            port = closed.getsockname()[1]
        with tempfile.TemporaryDirectory() as directory:
            _, receipts = self.capture(port, directory)
            self.assertEqual(
                {"endpoint-unavailable"}, {r["reason"] for r in receipts.values()}
            )
            with patch.object(Path, "write_text", side_effect=OSError("private-path")):
                outcomes, _ = self.capture(port, directory)
            self.assertEqual({"write-failed"}, set(outcomes.values()))

    def test_runner_failure_captures_real_http_before_owned_peer_teardown(self):
        with (
            tempfile.TemporaryDirectory() as directory,
            peer(
                lambda h: respond(
                    h,
                    {"receipts": [], "errors": 0, "rejected": 3}
                    if h.path == "/dns-receipts"
                    else [],
                )
            ) as port,
        ):
            out = Path(directory) / "result"
            argv = [
                "runner",
                "--scenario",
                "android-awg",
                "--run-id",
                "owned-run",
                "--out-dir",
                str(out),
                "--serial",
                "emulator-5554",
                "--prepared-dir",
                directory,
            ]
            transcript = (
                "INSTRUMENTATION_STATUS: class=example.Test\nINSTRUMENTATION_STATUS: test=roundTrip\n"
                "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS: class=example.Test\n"
                "INSTRUMENTATION_STATUS: test=roundTrip\nINSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_CODE: -1\n"
            )
            outputs = {
                "source.log": "a" * 40,
                "devices.log": "emulator-5554\tdevice\n",
                "device-kind.log": "1",
                "device-abi.log": "arm64-v8a",
                "instrumentation.log": transcript,
                "instrumentation-components.log": "instrumentation:com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner (target=com.poyka.ripdpi)\n",
            }

            def run(_args, path, **_kwargs):
                value = outputs.get(path.name, "")
                path.write_text(value)
                return value

            child = Mock()
            child.close.side_effect = lambda: self.assertTrue(
                (out / "diagnostic-main-dns-receipts.json").exists()
            )
            metadata = {
                "apks": {
                    kind: {"path": "/owned/" + kind + ".apk", "sha256": "b" * 64}
                    for kind in ("app", "test")
                }
            }
            with (
                patch.object(runner.sys, "argv", argv),
                patch.dict(
                    runner.SCENARIOS, {"android-awg": ["example.Test#roundTrip"]}
                ),
                patch.object(runner, "source_fingerprint", return_value="c" * 64),
                patch.object(runner, "prepared", return_value=metadata),
                patch.object(runner, "run", side_effect=run),
                patch.object(runner, "OwnedProcess", return_value=child),
                patch.object(
                    runner,
                    "wait_json",
                    return_value={"mode": "http-tun", "controlPort": port},
                ),
                patch.object(runner, "digest", return_value="d" * 64),
                patch.object(runner.signal, "signal"),
                patch.object(
                    runner.subprocess,
                    "run",
                    return_value=subprocess.CompletedProcess([], 1, "", ""),
                ),
                patch("builtins.print"),
            ):
                self.assertEqual(1, runner.main())
            result = json.loads((out / "result.json").read_text())
            self.assertEqual("failed", result["status"])
            self.assertIn("instrumentation failed or skipped", result["error"])
            self.assertTrue(result["cleanup"]["passed"])
            self.assertEqual({"captured"}, set(result["peer_diagnostics"].values()))
            self.assertFalse((out / "test-1/dns-receipts.json").exists())
            child.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
