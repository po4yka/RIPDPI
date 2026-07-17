from __future__ import annotations

import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from scripts.ci import package_native_symbols


class PackageNativeSymbolsTest(unittest.TestCase):
    def make_matrix(self, root: Path) -> tuple[Path, Path, dict[Path, package_native_symbols.ElfMetadata]]:
        symbols = root / "symbols"
        stripped = root / "stripped"
        metadata: dict[Path, package_native_symbols.ElfMetadata] = {}
        sequence = 0
        for abi in package_native_symbols.EXPECTED_ABIS:
            for library in package_native_symbols.EXPECTED_NATIVE_LIBRARIES:
                sequence += 1
                debug_file = symbols / abi / f"{library}.dbg"
                stripped_file = stripped / abi / library
                debug_file.parent.mkdir(parents=True, exist_ok=True)
                stripped_file.parent.mkdir(parents=True, exist_ok=True)
                debug_file.write_bytes(f"debug:{abi}:{library}".encode())
                stripped_file.write_bytes(f"stripped:{abi}:{library}".encode())
                build_id = f"{sequence:040x}"
                metadata[debug_file] = package_native_symbols.ElfMetadata(
                    build_ids=(build_id,),
                    sections=frozenset({".debug_line", ".debug_info"}),
                )
                metadata[stripped_file] = package_native_symbols.ElfMetadata(
                    build_ids=(build_id,),
                    sections=frozenset({".text", ".gnu_debuglink"}),
                )
        return symbols, stripped, metadata

    def package(self, root: Path, metadata: dict[Path, package_native_symbols.ElfMetadata]) -> dict[str, object]:
        symbols = root / "symbols"
        stripped = root / "stripped"
        output = root / "output"
        normalized_metadata = {path.resolve(): value for path, value in metadata.items()}
        with patch(
            "scripts.ci.package_native_symbols.inspect_elf",
            side_effect=lambda path, _tool: normalized_metadata[path.resolve()],
        ):
            return package_native_symbols.package_symbols(symbols, stripped, output, "llvm-readelf")

    def test_packages_exact_four_by_five_matrix_deterministically(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            _, _, metadata = self.make_matrix(root)

            first = self.package(root, metadata)
            manifest_bytes = (root / "output/manifest.json").read_bytes()
            bundle_bytes = (root / "output/release-native-symbols.zip").read_bytes()
            second = self.package(root, metadata)

            self.assertEqual(20, len(first["artifacts"]))
            self.assertEqual(first, second)
            self.assertEqual(manifest_bytes, (root / "output/manifest.json").read_bytes())
            self.assertEqual(bundle_bytes, (root / "output/release-native-symbols.zip").read_bytes())
            self.assertEqual(first, json.loads(manifest_bytes))
            with zipfile.ZipFile(root / "output/release-native-symbols.zip") as archive:
                self.assertTrue(all(entry.compress_type == zipfile.ZIP_STORED for entry in archive.infolist()))

    def test_rejects_missing_abi(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            symbols, _, metadata = self.make_matrix(root)
            for path in (symbols / "x86").iterdir():
                metadata.pop(path)
                path.unlink()
            (symbols / "x86").rmdir()
            with self.assertRaisesRegex(ValueError, "ABI set mismatch"):
                self.package(root, metadata)

    def test_rejects_missing_library(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            symbols, _, metadata = self.make_matrix(root)
            missing = symbols / "arm64-v8a/libripdpi-warp.so.dbg"
            metadata.pop(missing)
            missing.unlink()
            with self.assertRaisesRegex(ValueError, "missing expected files"):
                self.package(root, metadata)

    def test_rejects_extra_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            symbols, _, metadata = self.make_matrix(root)
            (symbols / "arm64-v8a/unexpected.dbg").write_bytes(b"extra")
            with self.assertRaisesRegex(ValueError, "unexpected files"):
                self.package(root, metadata)

    def test_rejects_mismatched_build_id(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            _, stripped, metadata = self.make_matrix(root)
            path = stripped / "arm64-v8a/libripdpi.so"
            metadata[path] = package_native_symbols.ElfMetadata(
                build_ids=("f" * 40,),
                sections=metadata[path].sections,
            )
            with self.assertRaisesRegex(ValueError, "build-ID mismatch"):
                self.package(root, metadata)

    def test_rejects_missing_or_duplicate_build_id_notes(self) -> None:
        for build_ids in ((), ("1" * 40, "2" * 40)):
            with self.subTest(build_ids=build_ids), tempfile.TemporaryDirectory() as temp_dir:
                root = Path(temp_dir)
                symbols, _, metadata = self.make_matrix(root)
                path = symbols / "arm64-v8a/libripdpi.so.dbg"
                metadata[path] = package_native_symbols.ElfMetadata(
                    build_ids=build_ids,
                    sections=metadata[path].sections,
                )
                with self.assertRaisesRegex(ValueError, "exactly one GNU build ID"):
                    self.package(root, metadata)

    def test_rejects_build_id_reused_by_two_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            symbols, stripped, metadata = self.make_matrix(root)
            first_debug = symbols / "arm64-v8a/libripdpi.so.dbg"
            duplicate_id = metadata[first_debug].build_ids
            second_debug = symbols / "arm64-v8a/libripdpi-relay.so.dbg"
            second_stripped = stripped / "arm64-v8a/libripdpi-relay.so"
            metadata[second_debug] = package_native_symbols.ElfMetadata(duplicate_id, metadata[second_debug].sections)
            metadata[second_stripped] = package_native_symbols.ElfMetadata(duplicate_id, metadata[second_stripped].sections)
            with self.assertRaisesRegex(ValueError, "duplicate GNU build ID"):
                self.package(root, metadata)

    def test_rejects_missing_debug_sections(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            symbols, _, metadata = self.make_matrix(root)
            path = symbols / "arm64-v8a/libripdpi.so.dbg"
            metadata[path] = package_native_symbols.ElfMetadata(metadata[path].build_ids, frozenset({".text"}))
            with self.assertRaisesRegex(ValueError, "missing .debug_line"):
                self.package(root, metadata)

    def test_rejects_unstripped_packaging_copy(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            _, stripped, metadata = self.make_matrix(root)
            path = stripped / "arm64-v8a/libripdpi.so"
            metadata[path] = package_native_symbols.ElfMetadata(
                metadata[path].build_ids,
                frozenset({".text", ".debug_line", ".gnu_debuglink"}),
            )
            with self.assertRaisesRegex(ValueError, "still contains debug sections"):
                self.package(root, metadata)

    def test_rejects_missing_debuglink(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            _, stripped, metadata = self.make_matrix(root)
            path = stripped / "arm64-v8a/libripdpi.so"
            metadata[path] = package_native_symbols.ElfMetadata(metadata[path].build_ids, frozenset({".text"}))
            with self.assertRaisesRegex(ValueError, "missing .gnu_debuglink"):
                self.package(root, metadata)


if __name__ == "__main__":
    unittest.main()
