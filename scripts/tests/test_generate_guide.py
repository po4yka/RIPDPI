import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).resolve().parents[1] / "guide" / "generate_guide.py"


def load_generate_guide_module():
    spec = importlib.util.spec_from_file_location("generate_guide", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    sys.modules["generate_guide"] = module
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


class GenerateGuideTest(unittest.TestCase):
    def test_extract_xml_trims_uiautomator_stdout_trailer(self) -> None:
        guide = load_generate_guide_module()
        raw = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node resource-id="onboarding-screen"><node resource-id="onboarding-continue" /></node></hierarchy>UI hierarchy dumped to: /dev/tty"""

        xml = guide._extract_xml(raw)

        self.assertTrue(xml.endswith("</hierarchy>"))
        self.assertNotIn("dumped to", xml)

        page = guide.PageSpec(
            id="onboarding",
            title="Onboarding",
            route="onboarding",
            expected_root="onboarding-screen",
            required_elements=["onboarding-continue"],
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            result = guide.analyze_ui_tree(page, xml, Path(temp_dir) / "onboarding.xml")

        self.assertTrue(result.reachable)
        self.assertEqual([], result.missing_elements)


if __name__ == "__main__":
    unittest.main()
