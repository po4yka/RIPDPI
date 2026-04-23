#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
DESIGN_MD_PATH = Path("DESIGN.md")
DESIGN_SYSTEM_DOC_PATH = Path("docs/design-system.md")
COLOR_FILE_PATH = Path("app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Color.kt")
TYPE_FILE_PATH = Path("app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Type.kt")
SPACING_FILE_PATH = Path("app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Spacing.kt")
SHAPE_FILE_PATH = Path("app/src/main/kotlin/com/poyka/ripdpi/ui/theme/Shape.kt")
DESIGN_SCREENSHOT_TEST_PATH = Path(
    "app/src/test/kotlin/com/poyka/ripdpi/ui/screenshot/RipDpiDesignSystemScreenshotTest.kt"
)
SCREEN_CATALOG_TEST_PATH = Path(
    "app/src/test/kotlin/com/poyka/ripdpi/ui/screenshot/RipDpiScreenCatalogScreenshotTest.kt"
)

STANDARD_SECTIONS = [
    "Overview",
    "Colors",
    "Typography",
    "Layout",
    "Elevation & Depth",
    "Shapes",
    "Components",
    "Do's and Don'ts",
]
REQUIRED_EXTENDED_SECTIONS = {
    "Accessibility",
    "Motion",
    "Iconography",
    "Theme Variants",
    "Screen Recipes",
    "Screen Contracts",
}
REQUIRED_DOC_HEADINGS = {
    "Source of Truth",
    "Primitive Layers",
    "Component Mapping",
    "Theme Variants",
    "Screen Recipes",
    "Screen Contracts",
    "Drift Checks",
}
REQUIRED_DESIGN_SCREENSHOT_FUNCTIONS = {
    "designSystemCatalogLightCompact",
    "designSystemCatalogDarkMedium",
    "designSystemCatalogLargeFont",
}
REQUIRED_SCREENSHOT_FUNCTIONS = {
    "homeDarkScreen",
    "homeHighContrastScreen",
    "introLargeFontScreen",
    "settingsLargeFontScreen",
    "diagnosticsScanScreen",
}
FRONT_MATTER_GROUPS = ("colors", "typography", "rounded", "spacing", "components")
ALLOWED_DOCUMENTED_COLOR_ALIASES = {"primary"}


@dataclass(frozen=True)
class Violation:
    path: str
    message: str


def read_text(repo_root: Path, relative_path: Path) -> str:
    return (repo_root / relative_path).read_text(encoding="utf-8")


def extract_front_matter(document: str) -> str:
    match = re.match(r"\A---\n(.*?)\n---\n", document, re.DOTALL)
    if match is None:
        raise ValueError("DESIGN.md must start with YAML front matter delimited by --- fences")
    return match.group(1)


def parse_front_matter_keys(front_matter: str) -> dict[str, set[str]]:
    keys = {group: set() for group in FRONT_MATTER_GROUPS}
    current_group: str | None = None

    for raw_line in front_matter.splitlines():
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue

        indent = len(raw_line) - len(raw_line.lstrip(" "))
        if indent == 0:
            current_group = stripped[:-1] if stripped.endswith(":") else None
            continue

        if current_group is None or current_group not in keys:
            continue

        if current_group in {"colors", "rounded", "spacing"} and indent == 2 and ":" in stripped:
            keys[current_group].add(stripped.split(":", 1)[0].strip())
        elif current_group in {"typography", "components"} and indent == 2 and stripped.endswith(":"):
            keys[current_group].add(stripped[:-1].strip())

    return keys


def extract_h2_headings(document: str) -> list[str]:
    return re.findall(r"^## (.+)$", document, re.MULTILINE)


def extract_data_class_field_names(source: str, class_name: str) -> set[str]:
    marker = f"data class {class_name}("
    start = source.find(marker)
    if start == -1:
        raise ValueError(f"Unable to find data class {class_name}")
    body_start = start + len(marker)
    depth = 1
    index = body_start
    while index < len(source) and depth > 0:
        character = source[index]
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
        index += 1

    if depth != 0:
        raise ValueError(f"Unable to parse data class body for {class_name}")

    body = source[body_start : index - 1]
    return set(re.findall(r"\bval\s+([A-Za-z0-9_]+)\s*:", body))


def extract_function_names(source: str) -> set[str]:
    return set(re.findall(r"\bfun\s+([A-Za-z0-9_]+)\s*\(", source))


def compare_named_tokens(
    path: Path,
    group_name: str,
    documented: set[str],
    implementation: set[str],
) -> list[Violation]:
    violations: list[Violation] = []
    missing = sorted(implementation - documented)
    extra = sorted(documented - implementation)

    if missing:
        violations.append(
            Violation(
                path=path.as_posix(),
                message=f"{group_name} missing implementation tokens: {', '.join(missing)}",
            )
        )
    if group_name == "colors":
        extra = sorted(set(extra) - ALLOWED_DOCUMENTED_COLOR_ALIASES)

    if extra:
        violations.append(
            Violation(
                path=path.as_posix(),
                message=f"{group_name} defines undocumented extras not present in implementation: {', '.join(extra)}",
            )
        )
    return violations


def design_front_matter_violations(repo_root: Path) -> list[Violation]:
    design_source = read_text(repo_root, DESIGN_MD_PATH)
    front_matter = parse_front_matter_keys(extract_front_matter(design_source))

    color_source = read_text(repo_root, COLOR_FILE_PATH)
    type_source = read_text(repo_root, TYPE_FILE_PATH)
    spacing_source = read_text(repo_root, SPACING_FILE_PATH)
    shape_source = read_text(repo_root, SHAPE_FILE_PATH)

    implementation_groups = {
        "colors": extract_data_class_field_names(color_source, "RipDpiExtendedColors"),
        "typography": extract_data_class_field_names(type_source, "RipDpiTextStyles"),
        "rounded": extract_data_class_field_names(shape_source, "RipDpiShapeTokens"),
        "spacing": extract_data_class_field_names(spacing_source, "RipDpiSpacing"),
    }

    violations: list[Violation] = []
    for group_name, implementation_keys in implementation_groups.items():
        violations.extend(
            compare_named_tokens(
                path=DESIGN_MD_PATH,
                group_name=group_name,
                documented=front_matter[group_name],
                implementation=implementation_keys,
            )
        )
    return violations


def design_section_violations(repo_root: Path) -> list[Violation]:
    design_source = read_text(repo_root, DESIGN_MD_PATH)
    headings = extract_h2_headings(design_source)
    violations: list[Violation] = []

    missing_standard = [heading for heading in STANDARD_SECTIONS if heading not in headings]
    missing_extended = sorted(REQUIRED_EXTENDED_SECTIONS - set(headings))

    for heading in missing_standard:
        violations.append(
            Violation(
                path=DESIGN_MD_PATH.as_posix(),
                message=f"missing standard DESIGN.md section '{heading}'",
            )
        )

    for heading in missing_extended:
        violations.append(
            Violation(
                path=DESIGN_MD_PATH.as_posix(),
                message=f"missing extended DESIGN.md section '{heading}'",
            )
        )

    if not missing_standard:
        indices = [headings.index(heading) for heading in STANDARD_SECTIONS]
        if indices != sorted(indices):
            violations.append(
                Violation(
                    path=DESIGN_MD_PATH.as_posix(),
                    message="standard DESIGN.md sections must remain in specification order",
                )
            )

    return violations


def design_system_doc_violations(repo_root: Path) -> list[Violation]:
    headings = set(extract_h2_headings(read_text(repo_root, DESIGN_SYSTEM_DOC_PATH)))
    missing = sorted(REQUIRED_DOC_HEADINGS - headings)
    return [
        Violation(
            path=DESIGN_SYSTEM_DOC_PATH.as_posix(),
            message=f"missing design-system guidance heading '{heading}'",
        )
        for heading in missing
    ]


def screenshot_coverage_violations(repo_root: Path) -> list[Violation]:
    design_functions = extract_function_names(read_text(repo_root, DESIGN_SCREENSHOT_TEST_PATH))
    screen_functions = extract_function_names(read_text(repo_root, SCREEN_CATALOG_TEST_PATH))
    violations: list[Violation] = []

    missing_design_functions = sorted(REQUIRED_DESIGN_SCREENSHOT_FUNCTIONS - design_functions)
    missing_screen_functions = sorted(REQUIRED_SCREENSHOT_FUNCTIONS - screen_functions)

    if "RipDpiDesignSystemScreenshotCatalog" not in read_text(repo_root, DESIGN_SCREENSHOT_TEST_PATH):
        violations.append(
            Violation(
                path=DESIGN_SCREENSHOT_TEST_PATH.as_posix(),
                message="design-system screenshot test must exercise RipDpiDesignSystemScreenshotCatalog",
            )
        )

    for function_name in missing_design_functions:
        violations.append(
            Violation(
                path=DESIGN_SCREENSHOT_TEST_PATH.as_posix(),
                message=f"missing required design-system screenshot test '{function_name}'",
            )
        )

    for function_name in missing_screen_functions:
        violations.append(
            Violation(
                path=SCREEN_CATALOG_TEST_PATH.as_posix(),
                message=f"missing required screen screenshot test '{function_name}'",
            )
        )

    return violations


def collect_violations(repo_root: Path) -> list[Violation]:
    return [
        *design_front_matter_violations(repo_root),
        *design_section_violations(repo_root),
        *design_system_doc_violations(repo_root),
        *screenshot_coverage_violations(repo_root),
    ]


def format_summary(violations: list[Violation]) -> str:
    lines = ["DESIGN.md verification", f"Violations: {len(violations)}"]
    for violation in violations:
        lines.append(f"  - {violation.path}: {violation.message}")
    return "\n".join(lines)


def main() -> int:
    violations = collect_violations(REPO_ROOT)
    print(format_summary(violations))
    return 1 if violations else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"DESIGN.md verification failed: {exc}", file=sys.stderr)
        raise
