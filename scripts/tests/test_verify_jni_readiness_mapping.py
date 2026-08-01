from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from scripts.ci import verify_jni_readiness_mapping


class VerifyJniReadinessMappingTest(unittest.TestCase):
    def write_mapping(
        self,
        method_mapping: str | None,
        *,
        class_mapping: str = "com.poyka.ripdpi.core.RuntimeReadinessListener",
        optimized_callback: bool = False,
        omitted_test_boundary: str | None = None,
        renamed_test_boundary: str | None = None,
        removed_test_boundary: str | None = None,
    ) -> Path:
        temporary = tempfile.NamedTemporaryFile(
            mode="w",
            suffix="-mapping.txt",
            encoding="utf-8",
            delete=False,
        )
        with temporary:
            temporary.write("example.Unrelated -> a:\n")
            temporary.write("    void other() -> a\n")
            for boundary in verify_jni_readiness_mapping.PRESERVED_RELEASE_TEST_CLASSES:
                if boundary == omitted_test_boundary:
                    continue
                mapped = "d" if boundary == renamed_test_boundary else boundary
                temporary.write(f"{boundary} -> {mapped}:\n")
            for boundary in verify_jni_readiness_mapping.RETAINED_RELEASE_TEST_CLASSES:
                mapped = (
                    "R8$$REMOVED$$CLASS$$1"
                    if boundary == removed_test_boundary
                    else "r"
                )
                temporary.write(f"{boundary} -> {mapped}:\n")
            temporary.write(
                f"com.poyka.ripdpi.core.RuntimeReadinessListener -> {class_mapping}:\n"
            )
            if method_mapping is not None:
                temporary.write(f"    void onRuntimeReady() -> {method_mapping}\n")
            if optimized_callback:
                temporary.write("example.SyntheticListener -> b:\n")
                temporary.write(
                    "    1:2:void example.Owner.lambda$0():42 -> onRuntimeReady\n"
                )
            temporary.write("example.After -> c:\n")
        self.addCleanup(Path(temporary.name).unlink)
        return Path(temporary.name)

    def test_accepts_preserved_callback_name(self) -> None:
        verify_jni_readiness_mapping.verify_mapping(
            self.write_mapping("onRuntimeReady")
        )

    def test_accepts_preserved_optimized_callback_output(self) -> None:
        verify_jni_readiness_mapping.verify_mapping(
            self.write_mapping(None, optimized_callback=True)
        )

    def test_rejects_obfuscated_callback_name(self) -> None:
        with self.assertRaisesRegex(ValueError, "renamed JNI callback"):
            verify_jni_readiness_mapping.verify_mapping(self.write_mapping("a"))

    def test_rejects_obfuscated_listener_class(self) -> None:
        with self.assertRaisesRegex(ValueError, "renamed JNI listener"):
            verify_jni_readiness_mapping.verify_mapping(
                self.write_mapping("onRuntimeReady", class_mapping="b")
            )

    def test_rejects_missing_callback_method(self) -> None:
        with self.assertRaisesRegex(ValueError, "missing preserved onRuntimeReady"):
            verify_jni_readiness_mapping.verify_mapping(self.write_mapping(None))

    def test_rejects_missing_release_test_boundary(self) -> None:
        boundary = "dagger.hilt.internal.TestSingletonComponentManager"
        with self.assertRaisesRegex(ValueError, f"missing {boundary}"):
            verify_jni_readiness_mapping.verify_mapping(
                self.write_mapping(
                    "onRuntimeReady",
                    omitted_test_boundary=boundary,
                )
            )

    def test_rejects_renamed_release_test_boundary(self) -> None:
        boundary = "dagger.hilt.internal.TestSingletonComponent"
        with self.assertRaisesRegex(
            ValueError,
            "renamed release instrumentation boundary",
        ):
            verify_jni_readiness_mapping.verify_mapping(
                self.write_mapping(
                    "onRuntimeReady",
                    renamed_test_boundary=boundary,
                )
            )

    def test_rejects_removed_release_test_boundary(self) -> None:
        boundary = "dagger.hilt.android.flags.FragmentGetContextFix$FragmentGetContextFixEntryPoint"
        with self.assertRaisesRegex(
            ValueError,
            "R8 removed release instrumentation boundary",
        ):
            verify_jni_readiness_mapping.verify_mapping(
                self.write_mapping(
                    "onRuntimeReady",
                    removed_test_boundary=boundary,
                )
            )


if __name__ == "__main__":
    unittest.main()
