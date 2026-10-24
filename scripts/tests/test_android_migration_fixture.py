import hashlib
import http.client
import importlib.util
import json
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "ci/android_migration_fixture.py"
SPEC = importlib.util.spec_from_file_location("android_migration_fixture", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class AndroidMigrationFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.storage = tempfile.TemporaryDirectory()
        cls.root = Path(cls.storage.name)
        cls.tls = cls.root / "tls"
        cls.tls.mkdir(mode=0o700)
        subprocess.run([
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-days", "1", "-subj", "/CN=fixture.test",
            "-addext", "subjectAltName=DNS:fixture.test",
            "-keyout", str(cls.tls / "private-key.pem"),
            "-out", str(cls.tls / "certificate.pem"),
        ], check=True, capture_output=True, timeout=20)
        for path in cls.tls.iterdir():
            path.chmod(0o600)
        cls.context = ssl.create_default_context(cafile=str(cls.tls / "certificate.pem"))

    @classmethod
    def tearDownClass(cls):
        cls.storage.cleanup()

    def setUp(self):
        self.payloads = ()

        def factory(port, token):
            self.payloads = tuple(json.dumps({
                "outbounds": [{"type": "direct", "tag": f"revision-{revision}"}],
                "ripdpi": {"schema_version": 2, "subscription_mirrors": [{
                    "id": "owned", "url": f"https://fixture.test:{port}/mirror",
                    "token": token, "transport": "direct",
                }]},
            }).encode() for revision in (0, 1))
            return self.payloads

        self.fixture = MODULE.MigrationFixture(self.root / self._testMethodName, self.tls, factory)
        self.fixture.start()
        self.addCleanup(self.fixture.close)

    def request(self, path, *, method="GET", authorization=None, control=False):
        port = self.fixture.control_port if control else self.fixture.port
        connection = http.client.HTTPConnection("fixture.test", port, timeout=4)
        stream = socket.create_connection(("127.0.0.1", port), timeout=4)
        connection.sock = stream if control else self.context.wrap_socket(stream, server_hostname="fixture.test")
        try:
            headers = {} if authorization is None else {"Authorization": authorization}
            connection.request(method, path, headers=headers)
            response = connection.getresponse()
            return response.status, response.read()
        finally:
            connection.close()

    def test_exact_https_revisions_and_authenticated_mirror_survive_primary_stop(self):
        self.assertEqual((200, self.payloads[0]), self.request("/subscription"))
        self.request("/revision/next", method="POST", control=True)
        self.request("/subscription/stop", method="POST", control=True)
        self.assertEqual(503, self.request("/subscription")[0])
        self.assertEqual((200, self.payloads[1]), self.request(
            "/mirror", authorization="Bearer " + self.fixture.mirror_token))
        status, body = self.request("/receipts", control=True)
        self.assertEqual(200, status)
        receipts = json.loads(body)
        self.assertEqual([0, 1, 1], [receipt["revision"] for receipt in receipts])
        self.assertEqual([200, 503, 200], [receipt["status"] for receipt in receipts])
        self.assertEqual(hashlib.sha256(self.payloads[1]).hexdigest(), receipts[-1]["sha256"])
        self.assertNotIn(self.fixture.mirror_token, body.decode())

    def test_mirror_authentication_and_exact_paths(self):
        for value in (None, "Bearer wrong", "bearer " + self.fixture.mirror_token):
            self.assertEqual(401, self.request("/mirror", authorization=value)[0])
        for path in ("/mirror?token=secret", "/subscription/extra", "/revision/next"):
            self.assertEqual(404, self.request(path)[0])
        self.assertTrue(all(receipt["sha256"] is None for receipt in self.fixture.receipts()))
        self.assertNotIn("secret", json.dumps(self.fixture.receipts()))

    def test_control_is_separate_and_cannot_serve_payloads(self):
        self.assertEqual(404, self.request("/subscription", control=True)[0])
        self.assertEqual(405, self.request("/revision/next", method="POST")[0])
        self.assertEqual((200, self.payloads[0]), self.request("/subscription"))

    def test_untrusted_certificate_is_rejected(self):
        with socket.create_connection(("127.0.0.1", self.fixture.port), timeout=4) as stream:
            with self.assertRaises(ssl.SSLCertVerificationError):
                ssl.create_default_context().wrap_socket(stream, server_hostname="fixture.test")
        self.assertEqual([], self.fixture.receipts())

    def test_wrong_hostname_is_rejected(self):
        with socket.create_connection(("127.0.0.1", self.fixture.port), timeout=4) as stream:
            with self.assertRaises(ssl.SSLCertVerificationError):
                self.context.wrap_socket(stream, server_hostname="other.test")
        self.assertEqual([], self.fixture.receipts())

    def test_receipt_limit_fails_without_unbounded_storage(self):
        for _ in range(MODULE.MAX_RECEIPTS):
            self.assertEqual(401, self.request("/mirror")[0])
        self.assertEqual(503, self.request("/subscription")[0])
        self.assertEqual(MODULE.MAX_RECEIPTS, len(self.fixture.receipts()))

    def test_cleanup_closes_both_owned_listeners_and_is_idempotent(self):
        ports = self.fixture.port, self.fixture.control_port
        self.request("/mirror", authorization="Bearer " + self.fixture.mirror_token)
        self.request("/mirror?token=secret")
        expected = self.fixture.receipts()
        self.fixture.close()
        self.fixture.close()
        receipt_path = self.fixture.directory / "http-receipts.json"
        self.assertEqual(expected, json.loads(receipt_path.read_text()))
        self.assertEqual(0, receipt_path.stat().st_mode & 0o077)
        self.assertNotIn(self.fixture.mirror_token, receipt_path.read_text())
        self.assertNotIn("secret", receipt_path.read_text())
        for port in ports:
            with self.assertRaises(OSError):
                socket.create_connection(("127.0.0.1", port), timeout=1)

    def test_failed_factory_closes_reserved_listener(self):
        ports = []

        def invalid(port, token):
            ports.append(port)
            return b'{"ripdpi":{"schema_version":1}}', b"{}"

        fixture = MODULE.MigrationFixture(self.root / "invalid", self.tls, invalid)
        with self.assertRaises(ValueError):
            fixture.start()
        with self.assertRaises(OSError):
            socket.create_connection(("127.0.0.1", ports[0]), timeout=1)

    def test_cleanup_does_not_write_to_an_existing_unowned_directory(self):
        directory = self.root / "unowned"
        directory.mkdir()
        fixture = MODULE.MigrationFixture(directory, self.tls, lambda *_: ())
        with self.assertRaises(FileExistsError):
            fixture.start()
        fixture.close()
        self.assertEqual([], list(directory.iterdir()))

    def test_schema_and_size_admission(self):
        for value in (b"{}", b"[]", b'{"ripdpi":{"schema_version":true}}',
                      b'{"ripdpi":{"schema_version":2.0}}', b" " * (MODULE.MAX_PAYLOAD + 1)):
            with self.subTest(value=value[:60]):
                with self.assertRaises(ValueError):
                    MODULE.admitted_payload(value)


if __name__ == "__main__":
    unittest.main()
