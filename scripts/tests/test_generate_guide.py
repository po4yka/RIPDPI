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

    def test_required_selector_failure_marks_page_unreachable(self) -> None:
        guide = load_generate_guide_module()
        page = guide.PageSpec(
            id="config",
            title="Config",
            route="config",
            expected_root="config-screen",
            required_elements=["config-section-navigation"],
        )
        xml = """<hierarchy><node resource-id="config-screen" /></hierarchy>"""

        with tempfile.TemporaryDirectory() as temp_dir:
            result = guide.analyze_ui_tree(page, xml, Path(temp_dir) / "config.xml")

        self.assertFalse(result.reachable)
        self.assertEqual(["config-section-navigation"], result.missing_elements)

    def test_theme_results_fail_closed_when_one_theme_is_unreachable(self) -> None:
        guide = load_generate_guide_module()
        page = guide.PageSpec(id="config", title="Config", route="config")
        dark = guide.PageCaptureResult(
            page_id="config",
            route="config",
            expected_root="config-screen",
            reachable=True,
            missing_elements=[],
            node_count=10,
            clickable_count=3,
            enabled_count=10,
            scrollable_count=1,
            text_samples=["Config"],
        )
        light = guide.PageCaptureResult(
            page_id="config",
            route="config",
            expected_root="config-screen",
            reachable=False,
            missing_elements=["config-section-navigation"],
            node_count=8,
            clickable_count=2,
            enabled_count=8,
            scrollable_count=1,
            text_samples=["Config"],
        )

        result = guide.aggregate_theme_results(page, [("dark", dark), ("light", light)])

        self.assertFalse(result.reachable)
        self.assertEqual(["light: config-section-navigation"], result.missing_elements)

    def test_theme_results_fail_closed_when_one_theme_is_absent(self) -> None:
        guide = load_generate_guide_module()
        page = guide.PageSpec(id="config", title="Config", route="config")
        dark = guide.PageCaptureResult(
            page_id="config",
            route="config",
            expected_root="config-screen",
            reachable=True,
            missing_elements=[],
            node_count=10,
            clickable_count=3,
            enabled_count=10,
            scrollable_count=1,
            text_samples=["Config"],
        )

        result = guide.aggregate_theme_results(page, [("dark", dark)])

        self.assertFalse(result.reachable)
        self.assertEqual(["light: capture result"], result.missing_elements)

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
            ],
            flow_sections=[
                guide.FlowSection("Start", "Start states", ["onboarding", "home_idle"]),
                guide.FlowSection("Tools", "Tool states", ["diagnostics", "settings"]),
            ],
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            sections = guide.write_flow_svgs(spec, root, root)

        self.assertEqual(2, len(sections))
        self.assertEqual(["Start", "Tools"], [section["title"] for section in sections])
        self.assertTrue(all(section["path"].endswith(".svg") for section in sections))

    def test_ui_audit_spec_has_typed_exclusions_and_exact_flow_partition(self) -> None:
        guide = load_generate_guide_module()
        spec_path = MODULE_PATH.parent / "specs" / "ui-ux-audit.yaml"

        spec = guide.load_spec(spec_path)

        self.assertEqual(7, len(spec.route_exclusions))
        self.assertIn("shared_diagnostic_result", [item.route for item in spec.route_exclusions])
        self.assertTrue(all(item.prerequisite and item.reason for item in spec.route_exclusions))
        section_ids = [page_id for section in spec.flow_sections for page_id in section.page_ids]
        self.assertCountEqual([page.id for page in spec.pages], section_ids)
        self.assertEqual(len(section_ids), len(set(section_ids)))
        self.assertNotIn("strategy_import", section_ids)
        self.assertNotIn("profile_variants", section_ids)

    def test_spec_validation_rejects_duplicate_ids_unknown_edges_and_section_drift(self) -> None:
        guide = load_generate_guide_module()
        cases = [
            guide.GuideSpec(
                title="duplicate",
                pages=[
                    guide.PageSpec(id="same", title="One", route="one"),
                    guide.PageSpec(id="same", title="Two", route="two"),
                ],
            ),
            guide.GuideSpec(
                title="edge",
                pages=[
                    guide.PageSpec(
                        id="one",
                        title="One",
                        route="one",
                        flow_from="missing",
                        flow_from_explicit=True,
                    ),
                ],
            ),
            guide.GuideSpec(
                title="section",
                pages=[guide.PageSpec(id="one", title="One", route="one")],
                flow_sections=[guide.FlowSection("Section", "", ["missing"])],
            ),
        ]

        for spec in cases:
            with self.subTest(spec=spec.title), self.assertRaises(ValueError):
                guide.validate_spec(spec)

    def test_write_guide_data_includes_dark_and_light_screenshots(self) -> None:
        guide = load_generate_guide_module()
        spec = guide.GuideSpec(
            title="Audit",
            pages=[
                guide.PageSpec(id="home", title="Home", route="home"),
            ],
            route_exclusions=[
                guide.RouteExclusion(
                    route="profile/share",
                    prerequisite="A profile exists.",
                    reason="The route needs a profile ID.",
                ),
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
        self.assertEqual(1, data["audit"]["excluded_count"])
        self.assertEqual("profile/share", data["audit"]["exclusions"][0]["route"])
        self.assertEqual(2, data["audit"]["coverage_total"])

    def test_write_guide_data_reports_each_missing_theme_screenshot(self) -> None:
        guide = load_generate_guide_module()
        spec = guide.GuideSpec(
            title="Audit",
            pages=[guide.PageSpec(id="home", title="Home", route="home")],
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            dark_dir = root / "screenshots" / "dark"
            light_dir = root / "screenshots" / "light"
            dark_dir.mkdir(parents=True)
            light_dir.mkdir(parents=True)
            Image.new("RGB", (100, 200), "black").save(dark_dir / "home.png")
            mermaid = root / "user-flow.mmd"
            mermaid.write_text("flowchart TD\n", encoding="utf-8")
            flow = root / "user-flow.svg"
            flow.write_text("<svg></svg>", encoding="utf-8")

            missing = guide.write_guide_data(
                spec,
                {"dark": dark_dir, "light": light_dir},
                root / "guide-data.json",
                root,
                [],
                mermaid,
                "flowchart TD\n",
                flow,
                [],
            )

        self.assertEqual(["home (light)"], missing)

    def test_cached_audit_results_reject_changed_selector_contract(self) -> None:
        guide = load_generate_guide_module()
        page = guide.PageSpec(
            id="config",
            title="Config",
            route="config",
            required_elements=["config-section-navigation"],
        )
        spec = guide.GuideSpec(title="Audit", pages=[page])
        per_theme = {
            theme: guide.PageCaptureResult(
                page_id="config",
                route="config",
                expected_root="config-screen",
                reachable=True,
                missing_elements=[],
                node_count=10,
                clickable_count=3,
                enabled_count=10,
                scrollable_count=1,
                text_samples=["Config"],
            )
            for theme in guide.CAPTURE_THEMES
        }
        result = guide.aggregate_theme_results(page, list(per_theme.items()))

        with tempfile.TemporaryDirectory() as temp_dir:
            cache = Path(temp_dir) / "ui-audit.json"
            guide.write_audit_results([result], [], cache, spec)
            self.assertEqual(1, len(guide.load_cached_audit_results(cache, spec)))

            changed_spec = guide.GuideSpec(
                title="Audit",
                pages=[
                    guide.PageSpec(
                        id="config",
                        title="Config",
                        route="config",
                        required_elements=[
                            "config-section-navigation",
                            "config-traffic-endpoint-selection",
                        ],
                    ),
                ],
            )
            self.assertEqual([], guide.load_cached_audit_results(cache, changed_spec))

    def test_strict_audit_completion_reports_missing_results_failures_and_screenshots(self) -> None:
        guide = load_generate_guide_module()
        pages = [
            guide.PageSpec(id="home", title="Home", route="home"),
            guide.PageSpec(id="config", title="Config", route="config"),
        ]
        result = guide.PageCaptureResult(
            page_id="home",
            route="home",
            expected_root="home-screen",
            reachable=False,
            missing_elements=["home-mode-card-local-dpi-bypass"],
            node_count=1,
            clickable_count=0,
            enabled_count=1,
            scrollable_count=0,
            text_samples=[],
        )

        errors = guide.audit_completion_errors(pages, [result], ["config"])

        self.assertIn("config: no audit result", errors)
        self.assertIn("home: home-mode-card-local-dpi-bypass", errors)
        self.assertIn("config: screenshot missing", errors)


if __name__ == "__main__":
    unittest.main()
