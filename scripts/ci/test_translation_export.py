"""Contract tests for the translation source-key exporter."""
import pathlib
import shutil
import subprocess
import tempfile
import unittest


class TranslationExportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.script = self.root / "scripts/ci/export-strings-for-translation.sh"
        self.script.parent.mkdir(parents=True)
        shutil.copy2(pathlib.Path(__file__).with_name(self.script.name), self.script)
        self.write("app", "strings.xml", '<string name="base">Base</string>')
        self.write("core/service", "strings.xml", '<string name="status">Status</string>')

    def write(self, module, filename, body):
        path = self.root / module / "src/main/res/values" / filename
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("<resources>" + body + "</resources>", encoding="utf-8")

    def run_export(self):
        return subprocess.run(["bash", str(self.script)], capture_output=True, text=True, check=False)

    def test_exports_all_default_resource_files_without_comments_or_nontranslatable_strings(self):
        self.write("app", "strings_xray_import.xml", '<string name="xray">Xray</string>')
        self.write("core/service", "connection.xml", '<string name="connected">Connected</string>')
        self.write("app", "colors.xml", '<color name="accent">#ffffff</color>')
        self.write("app", "attributes.xml", """
            <!-- <string name="comment">Not a resource</string> -->
            <string translatable='false' name='internal'>Internal</string>
            <string
                formatted="false"
                name="multiline">Visible</string>
            <plurals name="count"><item quantity="other">Many</item></plurals>
        """)
        result = self.run_export()
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = self.root / "config/i18n/translatable-keys.txt"
        self.assertEqual(
            ["app:base", "app:multiline", "app:xray", "service:connected", "service:status"],
            manifest.read_text().splitlines(),
        )
        first = manifest.read_bytes()
        self.assertEqual(0, self.run_export().returncode)
        self.assertEqual(first, manifest.read_bytes())

    def test_rejects_duplicate_resource_keys(self):
        self.write("app", "extra.xml", '<string name="base">Conflicting</string>')
        self.assertNotEqual(0, self.run_export().returncode)

    def test_rejects_missing_source_catalog(self):
        shutil.rmtree(self.root / "core/service/src/main/res/values")
        self.assertNotEqual(0, self.run_export().returncode)


if __name__ == "__main__":
    unittest.main()
