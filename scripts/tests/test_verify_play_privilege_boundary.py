from __future__ import annotations

import contextlib
import io
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.ci import verify_play_privilege_boundary as boundary


def elf(elf_class: int, machine: int) -> bytes:
    return (
        b"\x7fELF" + bytes((elf_class, 1, 1)) + bytes(9)
        + b"\x03\x00" + machine.to_bytes(2, "little") + bytes(44)
    )


def apk_members() -> dict[str, bytes]:
    return {
        "AndroidManifest.xml": b"compiled manifest",
        "classes.dex": b"dex\n035\x00",
        "resources.arsc": b"compiled resource table",
        "lib/arm64-v8a/libripdpi.so": elf(2, 183),
    }


def bundle_members() -> dict[str, bytes]:
    return {
        "BundleConfig.pb": b"bundle configuration",
        "base/manifest/AndroidManifest.xml": b"base module manifest",
        "base/dex/classes.dex": b"dex\n039\x00",
        "base/resources.pb": b"base module resources",
        "base/lib/arm64-v8a/libripdpi.so": elf(2, 183),
    }


class PlayPrivilegeBoundaryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def write_package(self, members: dict[str, bytes], name: str = "release.apk") -> Path:
        path = self.directory / name
        with zipfile.ZipFile(path, "w") as archive:
            for member, content in members.items():
                archive.writestr(member, content)
        return path

    def test_clean_play_keeps_unrelated_root_ca_shell_words_and_pcap(self) -> None:
        members = apk_members()
        members["classes.dex"] += (
            b"Lcom/example/tools/RootDetector;\x00Lcom/poyka/ripdpi/TlsRootDetector;\x00"
            b"root CA\x00certificate_root_path\x00su\x00"
        )
        members["assets/my-zapret2/data.txt"] = b"secure packet capture"
        members["lib/arm64-v8a/libripdpi.so"] += b"\x00pcap_capture\x00trust_anchor"
        package = self.write_package(members)

        self.assertEqual([], boundary.verify(package, "play"))

    def test_play_detects_paths_dex_elf_resources_and_text_across_modules(self) -> None:
        members = bundle_members()
        members["base/assets/bin/arm64-v8a/ripdpi-root-helper"] = elf(2, 183)
        members["optional/assets/zapret2/licenses/README"] = b"license"
        members["optional/dex/classes.dex"] = b"dex\n035\x00Lcom/poyka/ripdpi/services/RootDetector;"
        members["base/lib/arm64-v8a/libripdpi.so"] += b"\x00v4/send_raw_ip_packet\x00"
        members["base/resources.pb"] += "root-доступ".encode("utf-16-le")
        members["base/assets/lua/types.lua"] = b"return rawsend_dissect_ipfrag(packet)"
        members["base/assets/notices/rust.json"] = b'{"name": "ripdpi-root-helper-protocol"}'
        members["base/manifest/AndroidManifest.xml"] += b" android.permission.ACCESS_SUPERUSER"
        package = self.write_package(members, "release.aab")

        problems = "\n".join(boundary.verify(package, "play"))
        for member in (
            "ripdpi-root-helper", "optional/assets/zapret2/licenses", "optional/dex/classes.dex",
            "base/lib/arm64-v8a/libripdpi.so", "base/resources.pb", "base/assets/lua/types.lua",
            "base/assets/notices/rust.json", "base/manifest/AndroidManifest.xml",
        ):
            self.assertIn(member, problems)

    def test_scan_keeps_fingerprint_straddling_chunk_boundary(self) -> None:
        stream = io.BytesIO(b"\x00" * (1024 * 1024 - 3) + b"/data/adb/magisk")
        self.assertIn("root detection", boundary.content_findings(stream))

    def test_scan_detects_retired_settings_and_helper_state(self) -> None:
        for field in ("fakeMd5Sig", "fake_md5sig", "RootHelperAvailabilityChange", "NON_ROOT_PRODUCTION"):
            with self.subTest(field=field):
                self.assertIn("root settings", boundary.content_findings(io.BytesIO(field.encode())))

    def test_scan_detects_root_disclosure_in_native_diagnostics(self) -> None:
        reason = b"Native checks found hook, root, or hidden-interface artifacts."

        self.assertIn("root disclosure", boundary.content_findings(io.BytesIO(reason)))

    def test_scan_distinguishes_executable_name_from_socket_lua_compatibility_constant(self) -> None:
        for encoding in ("utf-8", "utf-16-le"):
            with self.subTest(encoding=encoding):
                self.assertEqual(set(), boundary.content_findings(io.BytesIO("NFQWS2_COMPAT_VER".encode(encoding))))
                self.assertIn("privileged executable", boundary.content_findings(io.BytesIO("/bin/nfqws2".encode(encoding))))

    def test_r8_mapping_skips_removed_classes_and_checks_inlining(self) -> None:
        package = self.write_package(apk_members())
        mapping = self.directory / "mapping.txt"
        mapping.write_text(
            "com.poyka.ripdpi.services.RootDetector -> R8$$REMOVED$$CLASS$$1:\n"
            "    int com.poyka.ripdpi.services.RootDetector.check() -> a\n"
            "com.thirdparty.ripdpi.RootDetector -> c:\n"
            "com.poyka.ripdpi.MainActivity -> b:\n",
            encoding="utf-8",
        )
        self.assertEqual([], boundary.verify(package, "play", mapping))

        with mapping.open("a", encoding="utf-8") as stream:
            stream.write("    10:20:void com.poyka.ripdpi.services.RootDetector.check():1:2 -> a\n")
        self.assertEqual(["R8 mapping contains app privilege code"], boundary.verify(package, "play", mapping))

    def test_play_checks_embedded_mapping_and_respects_namespace(self) -> None:
        members = bundle_members()
        members[boundary.MAPPING_ENTRY] = b"org.com.poyka.ripdpi.services.RootHelperManager -> a:\n"
        package = self.write_package(members, "release.aab")
        self.assertEqual([], boundary.verify(package, "play"))

        members[boundary.MAPPING_ENTRY] = b"com.poyka.ripdpi.services.RootHelperManager -> a:\n"
        package = self.write_package(members, "release.aab")
        self.assertEqual(["embedded R8 mapping contains app privilege code"], boundary.verify(package, "play"))

    def test_both_extended_editions_require_correct_elf_assets_for_each_abi(self) -> None:
        members = apk_members()
        members["lib/x86/libripdpi.so"] = elf(1, 3)
        for name in ("nfqws2", "ripdpi-root-helper"):
            members[f"assets/bin/arm64-v8a/{name}"] = elf(2, 183)
            members[f"assets/bin/x86/{name}"] = elf(1, 3)
        package = self.write_package(members)
        for edition in ("github", "fdroid"):
            with self.subTest(edition=edition):
                self.assertEqual([], boundary.verify(package, edition))
                self.assertTrue(boundary.verify(package, "play"))

        del members["assets/bin/x86/nfqws2"]
        members["assets/bin/x86/ripdpi-root-helper"] = elf(2, 183)
        package = self.write_package(members)
        self.assertEqual(
            ["invalid extended executable for x86: ripdpi-root-helper", "missing extended executable for x86: nfqws2"],
            boundary.verify(package, "github"),
        )

    def test_split_requires_explicit_option_and_scans_split_resources(self) -> None:
        package = self.write_package(
            {"AndroidManifest.xml": b"compiled manifest", "resources.arsc": "root モード".encode("utf-16-le")}
        )
        with self.assertRaises(boundary.InvalidArtifact):
            boundary.verify(package, "play")
        self.assertEqual([], boundary.verify(package, "play", split=True))

        package = self.write_package(
            {"AndroidManifest.xml": b"compiled manifest", "resources.arsc": "root-доступ".encode("utf-16-le")}
        )
        self.assertTrue(boundary.verify(package, "play", split=True))

    def test_cli_rejects_corrupt_entry_without_echoing_entry_contents(self) -> None:
        members = apk_members()
        members["assets/settings"] = b"sensitive-marker"
        package = self.write_package(members)
        package.write_bytes(package.read_bytes().replace(b"sensitive-marker", b"corrupted-marker"))
        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            code = boundary.main(["--edition", "play", str(package)])
        self.assertEqual(1, code)
        self.assertIn("Cannot read or verify package", errors.getvalue())
        self.assertNotIn("corrupted-marker", errors.getvalue())

    def test_rejects_missing_base_entries_bad_dex_and_unsafe_paths(self) -> None:
        for members, name in (
            ({"BundleConfig.pb": b"configuration"}, "bad.aab"),
            ({**apk_members(), "classes.dex": b"not dex"}, "bad.apk"),
            ({**apk_members(), "lib/arm64-v8a/libripdpi.so": elf(1, 3)}, "bad.apk"),
            ({**apk_members(), "assets/../anything": b"payload"}, "bad.apk"),
            ({**apk_members(), "assets/./anything": b"payload"}, "bad.apk"),
        ):
            with self.subTest(name=name, members=members):
                package = self.write_package(members, name)
                with self.assertRaises(boundary.InvalidArtifact):
                    boundary.verify(package, "play")

    def test_nested_apks_are_rejected_without_extraction(self) -> None:
        package = self.write_package({**apk_members(), "assets/plugin.apk": b"payload"})
        self.assertEqual(["nested Android package 'assets/plugin.apk'"], boundary.verify(package, "play"))


if __name__ == "__main__":
    unittest.main()
