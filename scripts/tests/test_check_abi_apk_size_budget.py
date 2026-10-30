from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.ci import check_abi_apk_size_budget


class CheckAbiApkSizeBudgetTest(unittest.TestCase):
    def write_apk(self, directory: Path) -> Path:
        path = directory / "app-universal-release.apk"
        with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as archive:
            archive.writestr("classes.dex", b"d" * 1000)
            archive.writestr("lib/arm64-v8a/libripdpi.so", b"a" * 4000)
            archive.writestr("lib/x86/libripdpi.so", b"x" * 2000)
            archive.writestr("assets/x86/keep.bin", b"k" * 500)
        return path

    def test_each_abi_drops_only_the_other_abis_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            apk = self.write_apk(Path(tmp))
            total = apk.stat().st_size

            sizes = check_abi_apk_size_budget.estimate_abi_sizes(apk)

        self.assertEqual({"arm64-v8a": total - 2000, "x86": total - 4000}, sizes)

    def test_budget_overrun_fails_and_fit_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            apk = self.write_apk(Path(tmp))
            arm64_mib = (apk.stat().st_size - 2000) / 1024 / 1024

            self.assertEqual(1, check_abi_apk_size_budget.main([str(apk), "--max-abi-apk-size-mib", str(arm64_mib * 0.99)]))
            self.assertEqual(0, check_abi_apk_size_budget.main([str(apk), "--max-abi-apk-size-mib", str(arm64_mib)]))


if __name__ == "__main__":
    unittest.main()
