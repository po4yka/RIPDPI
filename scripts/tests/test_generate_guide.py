import importlib.util
import sys
import tempfile
import unittest
import json
from pathlib import Path

from PIL import Image, ImageDraw


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

    def test_screenshot_has_app_content_rejects_blank_app_surface(self) -> None:
        guide = load_generate_guide_module()
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            blank = temp_path / "blank.png"
            content = temp_path / "content.png"

            blank_image = Image.new("RGB", (1080, 2400), "black")
            blank_draw = ImageDraw.Draw(blank_image)
            blank_draw.rectangle((40, 40, 180, 90), fill="white")
            blank_draw.rectangle((360, 2320, 720, 2330), fill="white")
            blank_image.save(blank)

            content_image = Image.new("RGB", (1080, 2400), "black")
            content_draw = ImageDraw.Draw(content_image)
            content_draw.rectangle((200, 1500, 880, 1700), fill="white")
            content_draw.rectangle((260, 1000, 820, 1120), fill=(150, 150, 150))
            content_image.save(content)

            self.assertFalse(guide.screenshot_has_app_content(blank))
            self.assertTrue(guide.screenshot_has_app_content(content))

    def test_write_flow_svg_renders_a_diagram_asset(self) -> None:
        guide = load_generate_guide_module()
        spec = guide.GuideSpec(
            title="Audit",
            pages=[
                guide.PageSpec(id="onboarding", title="Onboarding", route="onboarding", state="first-run"),
                guide.PageSpec(id="home", title="Home", route="home", flow_from="onboarding", flow_label="finish setup"),
            ],
            flow_title="Current app user flow",
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "flow.svg"
            guide.write_flow_svg(spec, output)
            svg = output.read_text(encoding="utf-8")

        self.assertIn("<svg", svg)
        self.assertIn("Current app user flow", svg)
        self.assertIn("Onboarding", svg)
        self.assertIn("finish setup", svg)

    def test_write_flow_svgs_splits_diagram_sections(self) -> None:
        guide = load_generate_guide_module()
        spec = guide.GuideSpec(
            title="Audit",
            pages=[
                guide.PageSpec(id="onboarding", title="Onboarding", route="onboarding"),
                guide.PageSpec(id="home_idle", title="Home", route="home", flow_from="onboarding"),
                guide.PageSpec(id="diagnostics", title="Diagnostics", route="diagnostics"),
                guide.PageSpec(id="settings", title="Settings", route="settings"),
                guide.PageSpec(id="profile_variants", title="Profile Variants", route="profile_variants"),
            ],
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            sections = guide.write_flow_svgs(spec, root, root)

        self.assertGreaterEqual(len(sections), 4)
        self.assertTrue(all(section["path"].endswith(".svg") for section in sections))

    def test_write_guide_data_includes_dark_and_light_screenshots(self) -> None:
        guide = load_generate_guide_module()
        spec = guide.GuideSpec(
            title="Audit",
            pages=[
                guide.PageSpec(id="home", title="Home", route="home"),
            ],
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            dark_dir = root / "screenshots" / "dark"
            light_dir = root / "screenshots" / "light"
            dark_dir.mkdir(parents=True)
            light_dir.mkdir(parents=True)
            Image.new("RGB", (100, 200), "black").save(dark_dir / "home.png")
            Image.new("RGB", (100, 200), "white").save(light_dir / "home.png")
            mermaid = root / "user-flow.mmd"
            mermaid.write_text("flowchart TD\n", encoding="utf-8")
            flow = root / "user-flow.svg"
            flow.write_text("<svg></svg>", encoding="utf-8")

            output = root / "guide-data.json"
            missing = guide.write_guide_data(
                spec,
                {"dark": dark_dir, "light": light_dir},
                output,
                root,
                [],
                mermaid,
                "flowchart TD\n",
                flow,
                [],
            )
            data = json.loads(output.read_text(encoding="utf-8"))

        self.assertEqual([], missing)
        page = data["pages"][0]
        self.assertEqual("/screenshots/dark/home.png", page["screenshot"])
        self.assertEqual(["Dark", "Light"], [shot["label"] for shot in page["screenshots"]])
        self.assertEqual("/screenshots/light/home.png", page["screenshots"][1]["path"])


if __name__ == "__main__":
    unittest.main()
