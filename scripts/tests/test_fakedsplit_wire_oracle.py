"""Independent TCP byte fixtures for raw fakedsplit acceptance."""
import copy
import importlib.util
import json
from pathlib import Path
import struct
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("fakedsplit_wire_oracle", ROOT / "scripts/ci/fakedsplit_wire_oracle.py")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)
PORT = 24443
# Packet order is a test contract, not output copied from the engine.
ORDER = ("FA A FB B", "FA FB A B", "A FA B FB", "A B FA FB")


def checksum(data):
    total = sum(int.from_bytes(data[i:i + 2].ljust(2, b"\0"), "big") for i in range(0, len(data), 2))
    total = (total & 65535) + (total >> 16)
    total = (total & 65535) + (total >> 16)
    return (~total) & 65535


def frame(payload, sequence, source_port, ttl, md5=False, ipv6=False, syn=False, flags=None):
    source = bytes.fromhex("20010db8000000000000000000000001") if ipv6 else bytes((192, 0, 2, 1))
    target = bytes.fromhex("20010db8000000000000000000000002") if ipv6 else bytes((192, 0, 2, 2))
    options = b"\x13\x12" + bytes(range(16)) + b"\0\0" if md5 else b""
    size = 20 + len(options)
    tcp = bytearray(struct.pack("!HHIIBBHHH", source_port, PORT, sequence, 1, (size // 4) << 4,
                                flags if flags is not None else (2 if syn else 24), 4096, 0, 0) + options + payload)
    if ipv6:
        pseudo = source + target + struct.pack("!I", len(tcp)) + b"\0\0\0\6"
        ip = struct.pack("!IHBB", 6 << 28, len(tcp), 6, ttl) + source + target
    else:
        pseudo = source + target + b"\0\6" + struct.pack("!H", len(tcp))
        ip = bytearray(struct.pack("!BBHHHBBH", 0x45, 0, 20 + len(tcp), 1, 0, ttl, 6, 0) + source + target)
        ip[10:12] = struct.pack("!H", checksum(ip))
    tcp[16:18] = struct.pack("!H", checksum(pseudo + tcp))
    return bytes(12) + (b"\x86\xdd" if ipv6 else b"\x08\0") + ip + tcp


def capture(contract, ipv6=False):
    frames = []
    for index, case in enumerate(contract["cases"]):
        base = (0xfffffff8 + index * 100) & 0xffffffff
        source_port = 30000 + index
        frames.append(frame(b"", (base - 1) & 0xffffffff, source_port, 64, ipv6=ipv6, syn=True))
        original = bytes.fromhex(case["originalHex"])
        split = contract["splitOffset"]
        payloads = {"A": (original[:split], 0), "B": (original[split:], split),
                    "FA": (bytes.fromhex(contract["fakeRegionsHex"][0]), 0),
                    "FB": (bytes.fromhex(contract["fakeRegionsHex"][1]), split)}
        for role in ORDER[case["order"]].split():
            data, offset = payloads[role]
            fake = role.startswith("F")
            frames.append(frame(data, (base + offset) & 0xffffffff, source_port,
                                contract["fakeTtl"] if fake else contract["realTtl"],
                                md5=fake, ipv6=ipv6))
    return frames


def packets(frames):
    return oracle.packets_from_tshark([
        {"_source": {"layers": {"frame_raw": [data.hex(), 0, len(data)],
                                ("ipv6_raw" if data[12:14] == b"\x86\xdd" else "ip_raw"): ["", 14, 20]}}}
        for data in frames
    ])


class FakedsplitWireTests(unittest.TestCase):
    def setUp(self):
        self.contract = json.loads(oracle.FIXTURE.read_text())
        self.packets = packets(capture(self.contract))

    def validate(self, records=None, contract=None):
        return oracle.validate_packets(self.packets if records is None else records,
                                       self.contract if contract is None else contract, PORT)

    def test_all_eight_modes_ipv4_and_ipv6_including_sequence_wrap(self):
        for ipv6 in (False, True):
            with self.subTest(ipv6=ipv6):
                result = self.validate(packets(capture(self.contract, ipv6)))
                self.assertEqual(8, result["cases"])
                self.assertEqual("deterministic-raw-wire", result["evidence"])

    def test_wrong_source_bytes_fail(self):
        changed = copy.deepcopy(self.contract)
        changed["cases"][0]["originalHex"] = "ff" + changed["cases"][0]["originalHex"][2:]
        with self.assertRaisesRegex(ValueError, "missing or ambiguous original"):
            self.validate(contract=changed)

    def test_missing_second_region_fails(self):
        with self.assertRaisesRegex(ValueError, "incomplete or wrong"):
            self.validate(self.packets[:4] + self.packets[5:])

    def test_fake_only_cannot_establish_real_source(self):
        records = [p for p in self.packets if not p["payload"] or p["md5"]]
        with self.assertRaisesRegex(ValueError, "missing or ambiguous original"):
            self.validate(records)

    def test_unsplit_original_is_rejected(self):
        records = copy.deepcopy(self.packets)
        records[2]["payload"] = bytes.fromhex(self.contract["cases"][0]["originalHex"])
        with self.assertRaises(ValueError):
            self.validate(records)

    def test_wrong_order_fails(self):
        records = self.packets[:]
        records[1], records[2] = records[2], records[1]
        with self.assertRaisesRegex(ValueError, "incomplete or wrong"):
            self.validate(records)

    def test_wrong_bytes_sequence_ttl_and_genuine_md5_fail(self):
        for field, value in (("payload", b"corrupt"), ("sequence", 5), ("ttl", 1), ("md5", True)):
            with self.subTest(field=field):
                records = copy.deepcopy(self.packets)
                records[2][field] = value
                with self.assertRaises(ValueError):
                    self.validate(records)

    def test_extra_fake_after_real_completion_fails(self):
        with self.assertRaises(ValueError):
            self.validate(self.packets[:5] + [self.packets[1]] + self.packets[5:])

    def test_contiguous_capture_duplicates_are_allowed(self):
        doubled = [record for packet in self.packets for record in (packet, packet)]
        self.assertEqual(8, self.validate(doubled)["cases"])

    def test_ambiguous_or_missing_syn_fails(self):
        with self.assertRaisesRegex(ValueError, "SYN"):
            self.validate(self.packets[1:])

    def test_corrupt_checksum_truncation_and_options_fail(self):
        frames = capture(self.contract)
        for offset in (14 + 10, 14 + 20 + 16, len(frames[1]) - 1):
            with self.subTest(offset=offset):
                damaged = bytearray(frames[1])
                damaged[offset] ^= 1
                with self.assertRaisesRegex(ValueError, "checksum"):
                    packets([bytes(damaged)])
        with self.assertRaises(ValueError):
            packets([frames[1][:-1]])
        malformed = bytearray(frames[1])
        malformed[14 + 20 + 21] = 1
        with self.assertRaisesRegex(ValueError, "option"):
            packets([bytes(malformed)])

    def test_incomplete_or_duplicate_mode_contract_fails(self):
        invalid_cases = ([], self.contract["cases"][:-1], [self.contract["cases"][0]] * 8)
        for cases in invalid_cases:
            with self.subTest(cases=len(cases)):
                changed = copy.deepcopy(self.contract)
                changed["cases"] = cases
                with self.assertRaisesRegex(ValueError, "eight modes"):
                    self.validate(contract=changed)

    def test_duplicate_or_ambiguous_source_contract_fails(self):
        for field in ("id", "originalHex"):
            with self.subTest(field=field):
                changed = copy.deepcopy(self.contract)
                changed["cases"][1][field] = changed["cases"][0][field]
                with self.assertRaises(ValueError):
                    self.validate(contract=changed)
        changed = copy.deepcopy(self.contract)
        # Different full sources with the same first region are ambiguous too.
        source = changed["cases"][0]["originalHex"]
        changed["cases"][1]["originalHex"] = source[:-2] + "ff"
        with self.assertRaisesRegex(ValueError, "distinct first regions"):
            self.validate(contract=changed)

    def test_checksum_correct_control_flags_on_data_are_rejected(self):
        for ipv6 in (False, True):
            for flags in (0x04, 0x02, 0x11, 0x30, 0x08):
                with self.subTest(ipv6=ipv6, flags=flags):
                    frames = capture(self.contract, ipv6)
                    original = bytes.fromhex(self.contract["cases"][0]["originalHex"])
                    split = self.contract["splitOffset"]
                    frames[2] = frame(original[:split], 0xfffffff8, 30000, 64, ipv6=ipv6, flags=flags)
                    frames[4] = frame(original[split:], (0xfffffff8 + split) & 0xffffffff,
                                      30000, 64, ipv6=ipv6, flags=flags)
                    # Parsing verifies the repaired TCP checksums before the role check.
                    records = packets(frames)
                    with self.assertRaises(ValueError):
                        self.validate(records)

    def test_missing_raw_bytes_fail(self):
        with self.assertRaisesRegex(ValueError, "raw capture"):
            oracle.packets_from_tshark([{"_source": {"layers": {"ip_raw": ["", 14, 20]}}}])


if __name__ == "__main__":
    unittest.main()
