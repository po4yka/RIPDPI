from __future__ import annotations

import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ci import verify_design_md


class VerifyDesignMdTest(unittest.TestCase):
    def write_file(self, root: Path, relative_path: str, content: str) -> None:
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(content).strip() + "\n", encoding="utf-8")

    def test_parse_front_matter_keys_collects_named_groups(self) -> None:
        parsed = verify_design_md.parse_front_matter_keys(
            textwrap.dedent(
                """
                version: alpha
                name: Sample
                colors:
                  background: "#ffffff"
                  foreground: "#111111"
                typography:
                  body:
                    fontFamily: Geist Sans
                    fontSize: 14px
                rounded:
                  xl: 16px
                spacing:
                  md: 12px
                components:
                  buttonPrimary:
                    backgroundColor: "{colors.foreground}"
                """
            ).strip()
        )

        self.assertEqual({"background", "foreground"}, parsed["colors"])
        self.assertEqual({"body"}, parsed["typography"])
        self.assertEqual({"xl"}, parsed["rounded"])
        self.assertEqual({"md"}, parsed["spacing"])
        self.assertEqual({"buttonPrimary"}, parsed["components"])

    def test_design_screenshot_contract_requires_high_contrast(self) -> None:
        self.assertIn(
            "designSystemCatalogHighContrast",
            verify_design_md.REQUIRED_DESIGN_SCREENSHOT_FUNCTIONS,
        )

    def test_collect_violations_accepts_aligned_design_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            self.write_file(
                repo_root,
                "DESIGN.md",
                """
                ---
                version: alpha
                name: Sample
                colors:
                  background: "#ffffff"
                  foreground: "#111111"
                typography:
                  screenTitle:
                    fontFamily: Geist Sans
                    fontSize: 22px
                    fontWeight: 500
                    lineHeight: 28px
                rounded:
                  xs: 4px
                spacing:
                  xs: 4px
                components:
                  screenCanvas:
                    backgroundColor: "{colors.background}"
                    textColor: "{colors.foreground}"
                ---

                ## Overview
                Sample

                ## Colors
                Sample

                ## Typography
                Sample

                ## Layout
                Sample

                ## Elevation & Depth
                Sample

                ## Shapes
                Sample

                ## Components
                Sample

                ## Do's and Don'ts
                Sample

                ## Accessibility
                Sample

                ## Motion
                Sample

                ## Iconography
                Sample

                ## Theme Variants
                Sample

                ## Screen Recipes
                Sample

                ## Layout Recipes
                Sample

                ## Screen Contracts
                Sample
                """,
            )
            self.write_file(
                repo_root,
                "docs/design-system.md",
                """
                ## Source of Truth
                Sample

                ## Primitive Layers
                Sample

                ## Component Mapping
                Sample

                ## Theme Variants
                Sample

                ## Screen Recipes
                Sample

                ## Layout Recipes
                Sample

                ## Screen Contracts
                Sample

                ## Motion Baselines
                Sample

                ## Drift Checks
                Sample
                """,
            )
            self.write_file(
                repo_root,
                "app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Color.kt",
                """
                data class RipDpiExtendedColors(
                    val background: Color,
                    val foreground: Color,
                )
                """,
            )
            self.write_file(
                repo_root,
                "app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Type.kt",
                """
                data class RipDpiTextStyles(
                    val screenTitle: TextStyle,
                )
                """,
            )
            self.write_file(
                repo_root,
                "app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Spacing.kt",
                """
                data class RipDpiSpacing(
                    val xs: Dp,
                )
                """,
            )
            self.write_file(
                repo_root,
                "app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Shape.kt",
                """
                data class RipDpiShapeTokens(
                    val xs: Shape,
                )
                """,
            )
            self.write_file(
                repo_root,
                "app/src/test/kotlin/com/poyka/ripdpi/ui/screenshot/RipDpiDesignSystemScreenshotTest.kt",
                """
                fun designSystemCatalogLightCompact() {}
                val catalog = "RipDpiDesignSystemScreenshotCatalog"
                """,
            )
            self.write_file(
                repo_root,
                "app/src/test/kotlin/com/poyka/ripdpi/ui/screenshot/RipDpiScreenCatalogScreenshotTest.kt",
                """
                fun homeHighContrastScreen() {}
                """,
            )

            with patch.object(
                verify_design_md,
                "REQUIRED_DESIGN_SCREENSHOT_FUNCTIONS",
                {"designSystemCatalogLightCompact"},
            ), patch.object(
                verify_design_md,
                "REQUIRED_SCREENSHOT_FUNCTIONS",
                {"homeHighContrastScreen"},
            ):
                violations = verify_design_md.collect_violations(repo_root)

            self.assertEqual([], violations)

    def test_collect_violations_reports_missing_required_heading(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            self.write_file(
                repo_root,
                "DESIGN.md",
                """
                ---
                version: alpha
                name: Sample
                colors:
                  background: "#ffffff"
                typography:
                  screenTitle:
                    fontFamily: Geist Sans
                    fontSize: 22px
                    fontWeight: 500
                    lineHeight: 28px
                rounded:
                  xs: 4px
                spacing:
                  xs: 4px
                components:
                  screenCanvas:
                    backgroundColor: "{colors.background}"
                ---

                ## Overview
                Sample
                """,
            )
            self.write_file(repo_root, "docs/design-system.md", "## Source of Truth\n")
            self.write_file(
                repo_root,
                "app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Color.kt",
                "data class RipDpiExtendedColors(val background: Color)\n",
            )
            self.write_file(
                repo_root,
                "app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Type.kt",
                "data class RipDpiTextStyles(val screenTitle: TextStyle)\n",
            )
            self.write_file(
                repo_root,
                "app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Spacing.kt",
                "data class RipDpiSpacing(val xs: Dp)\n",
            )
            self.write_file(
                repo_root,
                "app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Shape.kt",
                "data class RipDpiShapeTokens(val xs: Shape)\n",
            )
            self.write_file(
                repo_root,
                "app/src/test/kotlin/com/poyka/ripdpi/ui/screenshot/RipDpiDesignSystemScreenshotTest.kt",
                "",
            )
            self.write_file(
                repo_root,
                "app/src/test/kotlin/com/poyka/ripdpi/ui/screenshot/RipDpiScreenCatalogScreenshotTest.kt",
                "",
            )

            with patch.object(
                verify_design_md,
                "REQUIRED_DESIGN_SCREENSHOT_FUNCTIONS",
                set(),
            ), patch.object(
                verify_design_md,
                "REQUIRED_SCREENSHOT_FUNCTIONS",
                set(),
            ):
                violations = verify_design_md.collect_violations(repo_root)

            self.assertTrue(
                any("missing standard DESIGN.md section 'Colors'" in violation.message for violation in violations)
            )


if __name__ == "__main__":
    unittest.main()
