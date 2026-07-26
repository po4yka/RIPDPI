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
            temporary.write(
                "com.poyka.ripdpi.core.RuntimeReadinessListener "
                f"-> {class_mapping}:\n"
            )
            if method_mapping is not None:
                temporary.write(
                    f"    void onRuntimeReady() -> {method_mapping}\n"
                )
            if optimized_callback:
                temporary.write("example.SyntheticListener -> b:\n")
                temporary.write(
                    "    1:2:void example.Owner.lambda$0():42 "
                    "-> onRuntimeReady\n"
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


if __name__ == "__main__":
    unittest.main()
