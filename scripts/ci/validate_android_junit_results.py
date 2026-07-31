#!/usr/bin/env python3
"""Fail closed when connected Android JUnit evidence is missing or incomplete."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


AGGREGATE_FIELDS = ("tests", "failures", "errors", "skipped")
STATUS_AGGREGATES = {
    "failure": "failures",
    "error": "errors",
    "skipped": "skipped",
}


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("results_dir", type=Path)
    parser.add_argument(
        "--require-test",
        required=True,
        metavar="CLASS#METHOD",
        help="fully qualified testcase identity that must be present",
    )
    parser.add_argument(
        "--expected-count",
        type=int,
        help="require the selected testcase to execute exactly this many times",
    )
    parser.add_argument(
        "--expected-total-count",
        type=int,
        help="require the result set to contain exactly this many testcases",
    )
    parser.add_argument(
        "--minimum-total-count",
        type=int,
        help="require the result set to contain at least this many testcases",
    )
    parser.add_argument(
        "--forbid-skips",
        action="store_true",
        help="reject every skipped testcase in the result set",
    )
    return parser.parse_args()


def testcase_identity(testcase: ET.Element) -> str:
    class_name = testcase.get("classname")
    method_name = testcase.get("name")
    if not class_name or not method_name:
        raise ValueError("testcase is missing classname or name")
    return f"{class_name}#{method_name}"


def has_status(testcase: ET.Element, status: str) -> bool:
    return any(
        element is not testcase and local_name(element.tag) == status
        for element in testcase.iter()
    )


def parse_aggregates(element: ET.Element, context: str) -> dict[str, int]:
    aggregates: dict[str, int] = {}
    for field in AGGREGATE_FIELDS:
        raw_value = element.get(field)
        if raw_value is None:
            raise ValueError(f"{context} is missing {field} aggregate")
        if re.fullmatch(r"[0-9]+", raw_value) is None:
            raise ValueError(
                f"{context} {field} aggregate must be a non-negative integer"
            )
        aggregates[field] = int(raw_value)
    return aggregates


def ensure_aggregates_match(
    declared: dict[str, int],
    observed: dict[str, int],
    context: str,
) -> None:
    for field in AGGREGATE_FIELDS:
        if declared[field] != observed[field]:
            raise ValueError(
                f"{field} aggregate mismatch in {context}: "
                f"declared {declared[field]}, observed {observed[field]}"
            )


def parse_suite(
    suite: ET.Element,
    *,
    context: str,
) -> tuple[list[ET.Element], dict[str, int]]:
    declared = parse_aggregates(suite, context)
    testcases = [child for child in suite if local_name(child.tag) == "testcase"]
    nested_testcases = [
        element for element in suite.iter() if local_name(element.tag) == "testcase"
    ]
    if len(testcases) != len(nested_testcases):
        raise ValueError(f"{context} contains a testcase outside the suite root")

    observed = {field: 0 for field in AGGREGATE_FIELDS}
    observed["tests"] = len(testcases)
    for testcase in testcases:
        for status, aggregate in STATUS_AGGREGATES.items():
            if has_status(testcase, status):
                observed[aggregate] += 1
    ensure_aggregates_match(declared, observed, context)

    owned_status_elements = {
        id(element)
        for testcase in testcases
        for element in testcase.iter()
        if local_name(element.tag) in STATUS_AGGREGATES
    }
    if any(
        local_name(element.tag) in STATUS_AGGREGATES
        and id(element) not in owned_status_elements
        for element in suite.iter()
    ):
        raise ValueError(f"{context} contains a status element outside testcase")
    return testcases, observed


def parse_document(root: ET.Element, xml_file: Path) -> list[ET.Element]:
    root_name = local_name(root.tag)
    if root_name == "testsuite":
        testcases, _ = parse_suite(root, context=f"{xml_file} <testsuite>")
        return testcases
    if root_name != "testsuites":
        raise ValueError(f"unsupported JUnit XML root <{root_name}> in {xml_file}")

    root_context = f"{xml_file} <testsuites>"
    declared = parse_aggregates(root, root_context)
    suites = [child for child in root if local_name(child.tag) == "testsuite"]
    nested_suites = [
        element for element in root.iter() if local_name(element.tag) == "testsuite"
    ]
    if len(suites) != len(nested_suites):
        raise ValueError(f"{root_context} contains a nested testsuite")

    testcases: list[ET.Element] = []
    observed = {field: 0 for field in AGGREGATE_FIELDS}
    for index, suite in enumerate(suites, start=1):
        suite_context = f"{xml_file} <testsuite #{index}>"
        suite_testcases, suite_observed = parse_suite(suite, context=suite_context)
        testcases.extend(suite_testcases)
        for field in AGGREGATE_FIELDS:
            observed[field] += suite_observed[field]
    ensure_aggregates_match(declared, observed, root_context)
    return testcases


def validate(
    results_dir: Path,
    required_test: str,
    *,
    expected_count: int | None,
    expected_total_count: int | None,
    minimum_total_count: int | None,
    forbid_skips: bool,
) -> int:
    xml_files = sorted(results_dir.rglob("*.xml")) if results_dir.is_dir() else []
    if not xml_files:
        raise ValueError(f"no JUnit XML files found under {results_dir}")

    testcases: list[ET.Element] = []
    for xml_file in xml_files:
        try:
            root = ET.parse(xml_file).getroot()
        except ET.ParseError as error:
            raise ValueError(
                f"could not parse JUnit XML {xml_file}: {error}"
            ) from error
        testcases.extend(parse_document(root, xml_file))
    if not testcases:
        raise ValueError("JUnit XML contains zero testcases")

    if expected_count is not None and expected_count < 1:
        raise ValueError("--expected-count must be at least 1")
    if expected_total_count is not None and expected_total_count < 1:
        raise ValueError("--expected-total-count must be at least 1")
    if minimum_total_count is not None and minimum_total_count < 1:
        raise ValueError("--minimum-total-count must be at least 1")
    if expected_total_count is not None and len(testcases) != expected_total_count:
        raise ValueError(
            f"result set contains {len(testcases)} testcases, expected {expected_total_count}"
        )
    if minimum_total_count is not None and len(testcases) < minimum_total_count:
        raise ValueError(
            f"result set contains {len(testcases)} testcases, minimum is {minimum_total_count}"
        )
    required_class, separator, required_method = required_test.rpartition("#")
    if not separator or not required_class or not required_method:
        raise ValueError("--require-test must use CLASS#METHOD format")

    identities: set[str] = set()
    for testcase in testcases:
        identity = testcase_identity(testcase)
        if identity in identities:
            raise ValueError(f"duplicate testcase execution: {identity}")
        identities.add(identity)
        if has_status(testcase, "failure"):
            raise ValueError(f"failed testcase: {identity}")
        if has_status(testcase, "error"):
            raise ValueError(f"errored testcase: {identity}")
        if forbid_skips and has_status(testcase, "skipped"):
            raise ValueError(f"skipped testcase is forbidden: {identity}")

    matches = [
        testcase
        for testcase in testcases
        if testcase.get("classname") == required_class
        and testcase.get("name") == required_method
    ]
    if not matches:
        raise ValueError(f"required testcase did not execute: {required_test}")
    if any(has_status(testcase, "skipped") for testcase in matches):
        raise ValueError(f"required testcase was skipped: {required_test}")
    if expected_count is not None and len(matches) != expected_count:
        raise ValueError(
            f"required testcase executed {len(matches)} times, expected {expected_count}: "
            f"{required_test}"
        )

    print(
        f"validated {len(testcases)} testcases from {len(xml_files)} JUnit XML file(s); "
        f"required testcase executions={len(matches)}"
    )
    return 0


def main() -> int:
    args = parse_args()
    try:
        return validate(
            args.results_dir,
            args.require_test,
            expected_count=args.expected_count,
            expected_total_count=args.expected_total_count,
            minimum_total_count=args.minimum_total_count,
            forbid_skips=args.forbid_skips,
        )
    except (OSError, ValueError) as error:
        print(f"Android JUnit validation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
