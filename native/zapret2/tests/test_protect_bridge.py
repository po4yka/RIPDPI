"""Run on Linux: real SCM_RIGHTS transfers, no VPN or external traffic."""

import array
import ctypes
import errno
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest


class ProtectBridgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="nfqws2-protect-")
        cls.directory = Path(cls.temp.name)
        source = Path(__file__).resolve().parents[1] / "bridge/ripdpi_protect.c"
        library = cls.directory / "protect.so"
        subprocess.run(["cc", "-Wall", "-Wextra", "-Werror", "-shared", "-fPIC", str(source), "-o", str(library)], check=True)
        cls.library = ctypes.CDLL(str(library), use_errno=True)
        cls.library.ripdpi_protect_socket.argtypes = [ctypes.c_int]
        cls.library.ripdpi_protect_socket.restype = ctypes.c_bool

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def setUp(self):
        self.original = os.environ.pop("RIPDPI_PROTECT_PATH", None)
        self.outbound = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

    def tearDown(self):
        self.outbound.close()
        os.environ.pop("RIPDPI_PROTECT_PATH", None)
        if self.original is not None:
            os.environ["RIPDPI_PROTECT_PATH"] = self.original

    def protect(self):
        ctypes.set_errno(0)
        return self.library.ripdpi_protect_socket(self.outbound.fileno()), ctypes.get_errno()

    def handshake(self, reply):
        path = str(self.directory / self._testMethodName)
        server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        server.bind(path)
        server.listen(1)
        server.settimeout(5)
        failures = []

        def serve():
            try:
                with server.accept()[0] as channel:
                    channel.settimeout(5)
                    payload, messages, flags, _ = channel.recvmsg(1, socket.CMSG_SPACE(array.array("i").itemsize))
                    self.assertEqual(payload, b"\0")
                    self.assertEqual(flags & socket.MSG_CTRUNC, 0)
                    self.assertEqual(len(messages), 1)
                    level, kind, data = messages[0]
                    self.assertEqual((level, kind), (socket.SOL_SOCKET, socket.SCM_RIGHTS))
                    descriptors = array.array("i")
                    descriptors.frombytes(data)
                    self.assertEqual(len(descriptors), 1)
                    with socket.socket(fileno=descriptors[0]) as received:
                        self.assertEqual(received.getsockopt(socket.SOL_SOCKET, socket.SO_TYPE), socket.SOCK_DGRAM)
                        self.assertEqual(received.getsockname(), self.outbound.getsockname())
                    if reply == "stall":
                        time.sleep(2.2)
                    elif reply is not None:
                        channel.sendall(reply)
            except BaseException as error:
                failures.append(error)
            finally:
                server.close()

        thread = threading.Thread(target=serve)
        thread.start()
        os.environ["RIPDPI_PROTECT_PATH"] = path
        started = time.monotonic()
        result = self.protect()
        elapsed = time.monotonic() - started
        thread.join(5)
        self.assertFalse(thread.is_alive())
        if failures:
            raise failures[0]
        return result, elapsed

    def test_no_advertised_server_preserves_native_behavior(self):
        self.assertTrue(self.protect()[0])

    def test_success_receives_real_descriptor(self):
        self.assertTrue(self.handshake(b"\0")[0][0])

    def test_negative_ack_fails_closed(self):
        self.assertEqual(self.handshake(b"\1")[0], (False, errno.EACCES))

    def test_unknown_ack_fails_closed(self):
        self.assertEqual(self.handshake(b"\xff")[0], (False, errno.EACCES))

    def test_closed_server_fails_closed(self):
        self.assertEqual(self.handshake(None)[0], (False, errno.ECONNRESET))

    def test_stalled_server_has_bounded_wait(self):
        result, elapsed = self.handshake("stall")
        self.assertEqual(result, (False, errno.ETIMEDOUT))
        self.assertLess(elapsed, 3)

    def test_missing_server_fails_closed(self):
        os.environ["RIPDPI_PROTECT_PATH"] = str(self.directory / "missing")
        self.assertEqual(self.protect(), (False, errno.ENOENT))

    def test_empty_path_fails_closed(self):
        os.environ["RIPDPI_PROTECT_PATH"] = ""
        self.assertEqual(self.protect(), (False, errno.EINVAL))

    def test_long_path_fails_closed(self):
        os.environ["RIPDPI_PROTECT_PATH"] = "/" + "a" * 108
        self.assertEqual(self.protect(), (False, errno.EINVAL))


if __name__ == "__main__":
    unittest.main(verbosity=2)
