"""Kernel peer input/readback checks; no privilege or network is used."""
import importlib.util
import base64
import hashlib
from pathlib import Path
import tempfile
import struct
import unittest

SPEC = importlib.util.spec_from_file_location("linux_awg_runner", Path(__file__).with_name("run-linux-awg-interop.py"))
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class KernelPeerContracts(unittest.TestCase):
    def test_raw_probe_has_only_the_intended_ipv4_length_defect(self):
        spec=importlib.util.spec_from_file_location("kernel_raw",Path(__file__).resolve().parents[1]/"fixtures/amneziawg-peer/kernel_raw.py")
        raw=importlib.util.module_from_spec(spec);spec.loader.exec_module(raw)
        packet=raw.malformed_packet(43210)
        self.assertEqual(len(packet),284)
        self.assertEqual(packet[:4],b"\x45\x00\x00\x00")
        self.assertEqual(packet[12:20],bytes([10,77,0,1,10,77,0,2]))
        self.assertEqual(struct.unpack("!HHHH",packet[20:28]),(41002,43210,264,0))
        self.assertEqual(packet[28:],b"DATA:malformed-ip:"+b"x"*(256-len(b"DATA:malformed-ip:")))
        checksum=sum(struct.unpack("!10H",packet[:20]))
        while checksum>>16:checksum=(checksum&65535)+(checksum>>16)
        self.assertEqual(checksum,65535)
        for port in (0,65536,True,"43210"):
            with self.assertRaises(ValueError):raw.malformed_packet(port)

    def test_capture_requires_complete_statistics_without_drops(self):
        good="64 packets captured\n64 packets received by filter\n0 packets dropped by kernel\n"
        self.assertEqual(RUNNER.capture_statistics(good),dict(packets=64,dropped_packets=0))
        for bad in ("",good.replace("64 packets captured","0 packets captured"),good.replace("0 packets dropped","1 packets dropped"),good+good):
            with self.assertRaises(ValueError):RUNNER.capture_statistics(bad)
        empty=good.replace("64 packets captured","0 packets captured")
        self.assertEqual(RUNNER.capture_statistics(empty,allow_empty=True)["packets"],0)
        with self.assertRaises(ValueError):RUNNER.capture_statistics(empty.replace("0 packets dropped","1 packets dropped"),allow_empty=True)

    def test_readback_preserves_ranges_and_hides_keys(self):
        wanted = "[Interface]\nHeaderProtectionKey = fixture\nContentPaddingAddition = 16\nRandomTrailers = on\n[Peer]\nAllowedIPs = 10.77.0.2/32, fd77::2/128\nPersistentKeepalive = 25-35\n"
        actual = wanted.replace("= 16", "= 16-16").replace("= on", "= true").replace("10.77.0.2/32, fd77::2/128", "fd77::2/128, 10.77.0.2/32")
        result = RUNNER.verify_readback(wanted, actual)
        self.assertEqual(result["Peer.persistentkeepalive"], "25-35")
        self.assertNotIn("fixture", str(result))
        self.assertNotIn("Interface.headerprotectionkey", result)

    def test_missing_or_changed_v3_readback_fails(self):
        for key, value in [("HeaderProtectionKey", "fixture"), ("ContentPaddingAddition", "16"),
                           ("RandomTrailers", "on"), ("DisableCookies", "on"),
                           ("RekeyAfterTime", "100-120"), ("RekeyTimeout", "3-7"),
                           ("RejectAfterTime", "150-180"), ("KeepaliveTimeout", "5-15"), ("MaxHandshakeAttempts", "15-20")]:
            expected = f"[Interface]\n{key} = {value}\n"
            for actual in ("[Interface]\n", f"[Interface]\n{key} = invalid\n"):
                with self.subTest(key=key, actual=actual), self.assertRaisesRegex(ValueError, "readback mismatch"):
                    RUNNER.verify_readback(expected, actual)

    def test_private_key_readback_accepts_only_x25519_clamping(self):
        original = bytes([9] * 32)
        clamped = bytes([8] + [9] * 30 + [73])
        def config(key): return "[Interface]\nPrivateKey = " + base64.b64encode(key).decode() + "\n"
        self.assertEqual(RUNNER.verify_readback(config(original), config(clamped)), {})
        with self.assertRaisesRegex(ValueError, "privatekey"):
            RUNNER.verify_readback(config(original), config(bytes([10] * 32)))

    def test_vm_receipt_cannot_be_reused_on_another_instance(self):
        owner = {"guest_hostname": "owned", "guest_machine_id_sha256": hashlib.sha256(b"instance-one").hexdigest()}
        RUNNER.verify_vm_binding(owner, "instance-one\n", "owned")
        for machine, host in (("instance-two", "owned"), ("instance-one", "foreign")):
            with self.assertRaisesRegex(ValueError, "different VM"):
                RUNNER.verify_vm_binding(owner, machine, host)

    def test_source_digest_is_checked_before_extraction(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = root / "source.tar.gz"
            archive.write_bytes(b"wrong source")
            with self.assertRaisesRegex(ValueError, "source digest mismatch"):
                RUNNER.unpack("kernel", archive, root)
            self.assertEqual(list(root.iterdir()), [archive])


if __name__ == "__main__":
    unittest.main()
