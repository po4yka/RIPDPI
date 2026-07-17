from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUST_NATIVE_PLUGIN = ROOT / "build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts"


class RustNativeTaskLayoutTest(unittest.TestCase):
    def test_native_tasks_share_one_cargo_target_root(self) -> None:
        source = RUST_NATIVE_PLUGIN.read_text(encoding="utf-8")

        for task_name in (
            "buildRustNativeLibs",
            "buildRustRootHelper",
            "buildRustNaiveProxy",
            "buildRustCloudflareOrigin",
        ):
            task_start = source.index(f'("{task_name}")')
            task_end = source.find("\nval buildRust", task_start + 1)
            task_source = source[task_start : task_end if task_end != -1 else None]
            self.assertIn("cargoTargetDir.set(rustNativeLibsBuildDir)", task_source)


if __name__ == "__main__":
    unittest.main()
