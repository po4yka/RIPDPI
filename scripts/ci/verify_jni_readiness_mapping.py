#!/usr/bin/env python3
"""Verify source-named runtime boundaries in release R8 mappings."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


LISTENER_CLASS = "com.poyka.ripdpi.core.RuntimeReadinessListener"
CALLBACK_METHOD = "onRuntimeReady"
CLASS_MAPPING_PATTERN = re.compile(
    rf"^{re.escape(LISTENER_CLASS)} -> ([^:]+):$",
    re.MULTILINE,
)
DIRECT_METHOD_MAPPING_PATTERN = re.compile(
    rf"^\s+void {CALLBACK_METHOD}\(\) -> (\S+)$",
    re.MULTILINE,
)
PRESERVED_RELEASE_TEST_CLASSES = (
    "dagger.hilt.internal.TestSingletonComponent",
    "dagger.hilt.internal.TestSingletonComponentManager",
)
RETAINED_RELEASE_TEST_CLASSES = (
    "androidx.hilt.work.WorkerFactoryModule_ProvideFactoryFactory",
    "com.poyka.ripdpi.RipDpiApp_MembersInjector",
    "dagger.hilt.android.flags.FragmentGetContextFix$FragmentGetContextFixEntryPoint",
    "dagger.hilt.android.internal.lifecycle.DefaultViewModelFactories$InternalFactoryFactory",
    "dagger.hilt.android.internal.managers.FragmentComponentManager$FragmentComponentBuilderEntryPoint",
    "dagger.hilt.components.SingletonComponent",
)


def verify_preserved_class(source: str, class_name: str, mapping_path: Path) -> None:
    class_pattern = re.compile(
        rf"^{re.escape(class_name)} -> ([^:]+):$",
        re.MULTILINE,
    )
    class_match = class_pattern.search(source)
    if class_match is None:
        raise ValueError(f"missing {class_name} in R8 mapping: {mapping_path}")
    mapped_class = class_match.group(1)
    if mapped_class != class_name:
        raise ValueError(
            f"R8 renamed release instrumentation boundary {class_name} to "
            f"{mapped_class} in {mapping_path}"
        )


def verify_retained_class(source: str, class_name: str, mapping_path: Path) -> None:
    class_pattern = re.compile(
        rf"^{re.escape(class_name)} -> ([^:]+):$",
        re.MULTILINE,
    )
    class_match = class_pattern.search(source)
    if class_match is None:
        raise ValueError(f"missing retained {class_name} in R8 mapping: {mapping_path}")
    mapped_class = class_match.group(1)
    if mapped_class.startswith("R8$$REMOVED"):
        raise ValueError(
            f"R8 removed release instrumentation boundary {class_name} in "
            f"{mapping_path}"
        )


def verify_mapping(mapping_path: Path) -> None:
    source = mapping_path.read_text(encoding="utf-8")
    for class_name in PRESERVED_RELEASE_TEST_CLASSES:
        verify_preserved_class(source, class_name, mapping_path)
    for class_name in RETAINED_RELEASE_TEST_CLASSES:
        verify_retained_class(source, class_name, mapping_path)

    class_match = CLASS_MAPPING_PATTERN.search(source)
    if class_match is None:
        raise ValueError(f"missing {LISTENER_CLASS} in R8 mapping: {mapping_path}")
    mapped_class = class_match.group(1)
    if mapped_class != LISTENER_CLASS:
        raise ValueError(
            f"R8 renamed JNI listener {LISTENER_CLASS} to {mapped_class} "
            f"in {mapping_path}"
        )

    section_start = class_match.end()
    next_class = re.search(r"^\S.* -> [^:]+:$", source[section_start:], re.MULTILINE)
    section_end = section_start + next_class.start() if next_class else len(source)
    listener_section = source[section_start:section_end]
    direct_method_match = DIRECT_METHOD_MAPPING_PATTERN.search(listener_section)
    if (
        direct_method_match is not None
        and direct_method_match.group(1) != CALLBACK_METHOD
    ):
        mapped_name = direct_method_match.group(1)
        raise ValueError(
            f"R8 renamed JNI callback {LISTENER_CLASS}.{CALLBACK_METHOD}() "
            f"to {mapped_name}() in {mapping_path}"
        )

    callback_outputs = [
        line
        for line in source.splitlines()
        if line.startswith("    ")
        and "void " in line
        and line.endswith(f" -> {CALLBACK_METHOD}")
    ]
    if direct_method_match is None and not callback_outputs:
        raise ValueError(
            f"missing preserved {CALLBACK_METHOD}() output in R8 mapping: "
            f"{mapping_path}"
        )


def discover_mappings(repo_root: Path) -> list[Path]:
    return sorted(repo_root.glob("app/build/outputs/mapping/*Release/mapping.txt"))


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify release R8 mappings for JNI and instrumentation runtime boundaries."
        )
    )
    parser.add_argument(
        "--mapping",
        action="append",
        type=Path,
        help=(
            "R8 mapping.txt to verify. May be repeated. By default, verify every "
            "app/build/outputs/mapping/*Release/mapping.txt."
        ),
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    mappings = args.mapping or discover_mappings(repo_root)
    if not mappings:
        raise ValueError("no release R8 mapping files found")

    for mapping in mappings:
        mapping_path = mapping if mapping.is_absolute() else repo_root / mapping
        if not mapping_path.is_file():
            raise ValueError(f"R8 mapping file not found: {mapping_path}")
        verify_mapping(mapping_path)
        print(f"Verified release runtime boundary mapping in {mapping_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
