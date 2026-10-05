#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence


LIMITS = {
    "rust": 1500,
    "compose": 1000,
    "kotlin": 700,
}
DEFAULT_BASELINE = "config/static/file-loc-baseline.json"
DEFAULT_REPORT_DIR = "build/reports/file-loc-limits"


@dataclass(frozen=True)
class SourceMeasurement:
    path: str
    kind: str
    measured_loc: int
    limit: int


@dataclass(frozen=True)
class BaselineEntry:
    path: str
    kind: str
    measured_loc: int
    limit: int


def git_ls_files(repo_root: Path) -> list[str]:
    completed = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
        cwd=repo_root,
        check=True,
        capture_output=True,
        text=True,
    )
    return [line for line in completed.stdout.splitlines() if line and (repo_root / line).is_file()]


def is_rust_source(path: Path) -> bool:
    return path.suffix == ".rs" and path.parts[:3] == ("native", "rust", "crates")


def is_kotlin_main_source(path: Path) -> bool:
    if path.suffix != ".kt":
        return False

    parts = path.parts
    for index in range(len(parts) - 1):
        if parts[index] == "src" and parts[index + 1] == "main":
            return True
    return False


def is_included_source(path: Path) -> bool:
    if "build" in path.parts or "generated" in path.parts:
        return False
    return is_rust_source(path) or is_kotlin_main_source(path)


def iter_source_paths(repo_root: Path, tracked_paths: Sequence[str] | None = None) -> list[Path]:
    relative_paths = tracked_paths if tracked_paths is not None else git_ls_files(repo_root)
    return sorted({Path(path) for path in relative_paths if is_included_source(Path(path))})


def is_identifier_char(char: str) -> bool:
    return char == "_" or char.isalnum()


def rust_string_start(text: str, index: int) -> tuple[str, int, int] | None:
    previous = text[index - 1] if index > 0 else ""
    if previous and is_identifier_char(previous):
        return None

    if text.startswith("br", index):
        cursor = index + 2
        while cursor < len(text) and text[cursor] == "#":
            cursor += 1
        if cursor < len(text) and text[cursor] == '"':
            return ("rust_raw_string", cursor + 1, cursor - (index + 2))

    if text[index] == "r":
        cursor = index + 1
        while cursor < len(text) and text[cursor] == "#":
            cursor += 1
        if cursor < len(text) and text[cursor] == '"':
            return ("rust_raw_string", cursor + 1, cursor - (index + 1))

    if text.startswith('b"', index):
        return ("string", index + 2, 0)

    if text.startswith("b'", index):
        return ("char", index + 2, 0)

    return None


def strip_comments(text: str, language: str) -> str:
    output: list[str] = []
    index = 0
    state = "code"
    block_depth = 0
    raw_hashes = 0
    quote = ""

    while index < len(text):
        char = text[index]

        if state == "code":
            if language == "rust":
                rust_start = rust_string_start(text, index)
                if rust_start is not None:
                    state, next_index, raw_hashes = rust_start
                    output.append(text[index:next_index])
                    quote = '"' if state == "string" else "'"
                    index = next_index
                    continue

            if language == "kotlin" and text.startswith('"""', index):
                state = "triple_string"
                output.append('"""')
                index += 3
                continue

            if text.startswith("//", index):
                state = "line_comment"
                output.append("  ")
                index += 2
                continue

            if text.startswith("/*", index):
                state = "block_comment"
                block_depth = 1
                output.append("  ")
                index += 2
                continue

            if char == '"':
                state = "string"
                quote = '"'
                output.append(char)
                index += 1
                continue

            if char == "'":
                state = "char"
                quote = "'"
                output.append(char)
                index += 1
                continue

            output.append(char)
            index += 1
            continue

        if state == "line_comment":
            if char == "\n":
                output.append("\n")
                state = "code"
            else:
                output.append(" ")
            index += 1
            continue

        if state == "block_comment":
            if text.startswith("/*", index):
                block_depth += 1
                output.append("  ")
                index += 2
                continue

            if text.startswith("*/", index):
                block_depth -= 1
                output.append("  ")
                index += 2
                if block_depth == 0:
                    state = "code"
                continue

            output.append("\n" if char == "\n" else " ")
            index += 1
            continue

        if state == "triple_string":
            if text.startswith('"""', index):
                output.append('"""')
                index += 3
                state = "code"
            else:
                output.append(char)
                index += 1
            continue

        if state == "rust_raw_string":
            if char == '"' and text[index + 1:index + 1 + raw_hashes] == "#" * raw_hashes:
                output.append(text[index:index + 1 + raw_hashes])
                index += 1 + raw_hashes
                state = "code"
            else:
                output.append(char)
                index += 1
            continue

        output.append(char)
        if char == "\\" and index + 1 < len(text):
            output.append(text[index + 1])
            index += 2
            continue

        index += 1
        if char == quote:
            state = "code"

    return "".join(output)


def count_code_lines(text: str, language: str) -> int:
    stripped = strip_comments(text, language)
    return sum(1 for line in stripped.splitlines() if line.strip())


def is_compose_source(text: str) -> bool:
    stripped = strip_comments(text, "kotlin")
    return (
        "@Composable" in stripped
        or "androidx.compose" in stripped
        or "setContent {" in stripped
        or "setContent(" in stripped
    )


def measure_source(repo_root: Path, relative_path: Path) -> SourceMeasurement:
    source_text = (repo_root / relative_path).read_text(encoding="utf-8")
    if relative_path.suffix == ".rs":
        kind = "rust"
        measured_loc = count_code_lines(source_text, "rust")
    else:
        kind = "compose" if is_compose_source(source_text) else "kotlin"
        measured_loc = count_code_lines(source_text, "kotlin")

    return SourceMeasurement(
        path=relative_path.as_posix(),
        kind=kind,
        measured_loc=measured_loc,
        limit=LIMITS[kind],
    )


def collect_measurements(
    repo_root: Path,
    tracked_paths: Sequence[str] | None = None,
) -> list[SourceMeasurement]:
    return [measure_source(repo_root, path) for path in iter_source_paths(repo_root, tracked_paths)]


def read_baseline(path: Path) -> dict[tuple[str, str, int], BaselineEntry]:
    raw_data = json.loads(path.read_text(encoding="utf-8"))
    raw_entries = raw_data.get("entries")
    if not isinstance(raw_entries, list):
        raise ValueError(f"Baseline file must contain an entries list: {path}")

    entries: dict[tuple[str, str, int], BaselineEntry] = {}
    for raw_entry in raw_entries:
        entry = BaselineEntry(
            path=raw_entry["path"],
            kind=raw_entry["kind"],
            measured_loc=int(raw_entry["measuredLoc"]),
            limit=int(raw_entry["limit"]),
        )
        key = (entry.path, entry.kind, entry.limit)
        if key in entries:
            raise ValueError(f"Duplicate baseline entry for {entry.path} ({entry.kind})")
        entries[key] = entry

    return entries


def evaluate_measurements(
    measurements: Sequence[SourceMeasurement],
    baseline_entries: dict[tuple[str, str, int], BaselineEntry],
) -> dict[str, object]:
    measurement_index = {(item.path, item.kind, item.limit): item for item in measurements}

    new_violations: list[dict[str, object]] = []
    baseline_exemptions: list[dict[str, object]] = []
    baseline_growth_violations: list[dict[str, object]] = []
    stale_baseline_entries: list[dict[str, object]] = []
    missing_baseline_entries: list[dict[str, object]] = []

    for measurement in measurements:
        if measurement.measured_loc <= measurement.limit:
            continue

        key = (measurement.path, measurement.kind, measurement.limit)
        baseline_entry = baseline_entries.get(key)
        if baseline_entry is None:
            new_violations.append(asdict(measurement))
            continue

        baseline_exemptions.append(
            {
                **asdict(measurement),
                "baselineMeasuredLoc": baseline_entry.measured_loc,
            },
        )
        if measurement.measured_loc > baseline_entry.measured_loc:
            baseline_growth_violations.append(
                {
                    **asdict(measurement),
                    "baselineMeasuredLoc": baseline_entry.measured_loc,
                    "growth": measurement.measured_loc - baseline_entry.measured_loc,
                },
            )

    for key, baseline_entry in baseline_entries.items():
        current = measurement_index.get(key)
        if current is None:
            missing_baseline_entries.append(asdict(baseline_entry))
            continue

        if current.measured_loc <= current.limit:
            stale_baseline_entries.append(
                {
                    **asdict(baseline_entry),
                    "currentMeasuredLoc": current.measured_loc,
                },
            )

    counts = {
        "total": len(measurements),
        "rust": sum(1 for item in measurements if item.kind == "rust"),
        "compose": sum(1 for item in measurements if item.kind == "compose"),
        "kotlin": sum(1 for item in measurements if item.kind == "kotlin"),
    }

    return {
        "counts": counts,
        "newViolations": sorted(new_violations, key=lambda item: item["path"]),
        "baselineExemptions": sorted(baseline_exemptions, key=lambda item: item["path"]),
        "baselineGrowthViolations": sorted(baseline_growth_violations, key=lambda item: item["path"]),
        "staleBaselineEntries": sorted(stale_baseline_entries, key=lambda item: item["path"]),
        "missingBaselineEntries": sorted(missing_baseline_entries, key=lambda item: item["path"]),
    }


def format_summary(results: dict[str, object]) -> str:
    counts = results["counts"]
    assert isinstance(counts, dict)

    lines = [
        "File LoC limits",
        f"Scanned {counts['total']} files ({counts['rust']} rust, {counts['compose']} compose, {counts['kotlin']} kotlin)",
    ]

    new_violations = results["newViolations"]
    assert isinstance(new_violations, list)
    if new_violations:
        lines.append(f"New violations: {len(new_violations)}")
        for violation in new_violations:
            lines.append(
                f"  - {violation['path']} [{violation['kind']}] limit={violation['limit']} measured={violation['measured_loc']}",
            )
    else:
        lines.append("New violations: 0")

    baseline_exemptions = results["baselineExemptions"]
    assert isinstance(baseline_exemptions, list)
    lines.append(f"Baseline exemptions: {len(baseline_exemptions)}")
    for exemption in baseline_exemptions:
        lines.append(
            "  - "
            f"{exemption['path']} [{exemption['kind']}] limit={exemption['limit']} "
            f"baseline={exemption['baselineMeasuredLoc']} current={exemption['measured_loc']}",
        )

    baseline_growth = results["baselineGrowthViolations"]
    assert isinstance(baseline_growth, list)
    lines.append(f"Baseline growth violations: {len(baseline_growth)}")
    for violation in baseline_growth:
        lines.append(
            "  - "
            f"{violation['path']} [{violation['kind']}] limit={violation['limit']} "
            f"baseline={violation['baselineMeasuredLoc']} current={violation['measured_loc']} "
            f"growth={violation['growth']}",
        )

    stale_entries = results["staleBaselineEntries"]
    assert isinstance(stale_entries, list)
    lines.append(f"Stale baseline entries: {len(stale_entries)}")
    for entry in stale_entries:
        lines.append(
            f"  - {entry['path']} [{entry['kind']}] limit={entry['limit']} "
            f"baseline={entry['measured_loc']} current={entry['currentMeasuredLoc']}",
        )

    missing_entries = results["missingBaselineEntries"]
    assert isinstance(missing_entries, list)
    lines.append(f"Missing baseline entries: {len(missing_entries)}")
    for entry in missing_entries:
        lines.append(
            f"  - {entry['path']} [{entry['kind']}] limit={entry['limit']} baseline={entry['measured_loc']}",
        )

    return "\n".join(lines)


def write_reports(report_dir: Path, results: dict[str, object]) -> None:
    report_dir.mkdir(parents=True, exist_ok=True)
    summary = format_summary(results)
    (report_dir / "summary.txt").write_text(summary + "\n", encoding="utf-8")
    (report_dir / "results.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")


def build_baseline(measurements: Iterable[SourceMeasurement]) -> dict[str, object]:
    entries = [
        {
            "path": item.path,
            "kind": item.kind,
            "measuredLoc": item.measured_loc,
            "limit": item.limit,
        }
        for item in sorted(measurements, key=lambda measurement: measurement.path)
        if item.measured_loc > item.limit
    ]
    return {"entries": entries}


_KT_FUN_RE = re.compile(r"^\s*((?:(?:private|internal|public|protected|inline|suspend|override|open|abstract|operator|infix|tailrec|external|actual|expect)\s+)*)fun\s+(\w+)", re.MULTILINE)
_RS_FN_RE = re.compile(r"^\s*((?:(?:pub(?:\([^)]*\))?|async|unsafe|extern(?:\s+\"[^\"]*\")?|const|default)\s+)*)fn\s+(\w+)", re.MULTILINE)

_KT_SUPPRESS_RE = re.compile(r"@(?:file:)?Suppress\s*\(")
_RS_ALLOW_RE = re.compile(r"#\[allow\(")


def _measure_function_lines(text: str, match_start: int, body_start: int | None = None) -> int:
    """Count lines from the function signature to the end of its body (balanced braces)."""
    start_line = text.count("\n", 0, match_start)
    brace_pos = text.find("{", match_start) if body_start is None else body_start
    if brace_pos == -1:
        # Single-expression or abstract function with no body
        end_of_line = text.find("\n", match_start)
        end_line = text.count("\n", 0, end_of_line) if end_of_line != -1 else text.count("\n")
        return max(1, end_line - start_line + 1)

    depth = 0
    index = brace_pos
    while index < len(text):
        ch = text[index]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                end_line = text.count("\n", 0, index)
                return max(1, end_line - start_line + 1)
        index += 1

    # Unbalanced — count to end of file
    return max(1, text.count("\n", match_start) + 1)


def _kotlin_type_arguments_end(text: str, opening: int) -> int | None:
    type_context = re.search(r"(?:\b(?:is|as)\??)\s+[\w.`]+$", text[:opening]) is not None
    depth = 1
    for index in range(opening + 1, len(text)):
        character = text[index]
        if character == "<":
            depth += 1
        elif character == ">" and text[index - 1] != "-":
            depth -= 1
            if depth == 0:
                suffix = text[index + 1:].lstrip()
                return index if type_context or suffix.startswith(("(", "{", "::", ".")) else None
        elif character in "{};=+/%|":
            return None
    return None


def _kotlin_expression_needs_body(expression: str) -> bool:
    """Track operands, selectors and infix names outside completed nested groups."""
    tokens: list[str] = []
    groups: list[tuple[str, str]] = []
    generic_ends: set[int] = set()
    pattern = r"@(?:\w+:)?[\w.]+|\w+@|!in\b|!is\b|as\?|`[^`]*`|\w+|\+\+|--|!!|\?\.|::|&&|\|\||\?:|->|\S"
    for match in re.finditer(pattern, expression):
        token = match.group()
        if token == "<":
            end = _kotlin_type_arguments_end(expression, match.start())
            if end is not None:
                generic_ends.add(end)
        opening = token in "([{"
        generic_opening = token == "<" and end is not None
        if opening or generic_opening:
            kind = "<condition>" if token == "(" and not groups and tokens and tokens[-1] in ("if", "when", "catch") else "<operand>"
            if not groups and tokens and tokens[-1].startswith("@"):
                kind = "<prefix>"
            groups.append((token, kind))
        elif token in ")]}" or token == ">" and match.start() in generic_ends:
            if groups:
                _, kind = groups.pop()
                if not groups:
                    tokens.append(kind)
        elif not groups:
            tokens.append(token)
    needed = True
    selector = False
    for token in tokens:
        if token in ("<condition>", "<prefix>", "!in", "!is", "as?") or token.startswith("@") or token.endswith("@"):
            needed = True
        elif token in ("if", "when", "catch", "else", "try", "finally", "throw", "return", "object"):
            needed = True
        elif token == "<operand>" or token[0].isdigit():
            needed = False
            selector = False
        elif token in (".", "?.", "::"):
            needed = True
            selector = True
        elif token in ("++", "--", "!!"):
            # Completed postfix operators do not require a new operand.
            continue
        elif token.startswith("`") or token[0].isalpha() or token[0] == "_":
            needed = False if selector else not needed
            selector = False
        elif token != "?":
            needed = True
    return needed


def _kotlin_expression_lines(text: str, match_start: int, equals: int) -> int:
    """Measure balanced expression arguments and every continuation, not the next class."""
    start_line = text.count("\n", 0, match_start)
    depth = 0
    generic_ends: set[int] = set()
    for index in range(equals + 1, len(text)):
        character = text[index]
        if character in "([{":
            depth += 1
        elif character == "<":
            end = _kotlin_type_arguments_end(text, index)
            if end is not None:
                generic_ends.add(end)
                depth += 1
        elif character == ">" and index in generic_ends:
            depth -= 1
        elif character in ")]}":
            if depth == 0:
                return max(1, text.count("\n", 0, index) - start_line + 1)
            depth -= 1
        elif character == ";" and depth == 0:
            return max(1, text.count("\n", 0, index) - start_line + 1)
        elif character == "\n" and depth == 0:
            expression = text[equals + 1:index].rstrip()
            if not expression or _kotlin_expression_needs_body(expression):
                continue
            next_token = index + 1
            while next_token < len(text) and text[next_token].isspace():
                next_token += 1
            remainder = text[next_token:]
            if remainder.startswith((".", "?.", "?:", "{", "+", "-", "*", "/", "%", "&&", "||")):
                continue
            if re.match(r"(?:else|catch|finally)\b", remainder):
                continue
            return max(1, text.count("\n", 0, index) - start_line + 1)
    return max(1, text.count("\n", match_start) + 1)


def _kotlin_function_boundary(text: str, match_start: int) -> tuple[int | None, int | None]:
    """Return bodyless signature length or the actual block-body opening position.

    Interface methods are implicitly abstract. A brace in the next class or default
    method does not belong to them, while a body following a newline still does.
    The input masks comments and literals without changing character positions.
    """
    start_line = text.count("\n", 0, match_start)
    parenthesis_depth = 0
    saw_parameters = False
    parameters_complete = False
    parameter_end = match_start
    for index in range(match_start, len(text)):
        character = text[index]
        if character == "(":
            parenthesis_depth += 1
            saw_parameters = True
        elif character == ")":
            parenthesis_depth = max(0, parenthesis_depth - 1)
            if parenthesis_depth == 0 and not parameters_complete:
                parameter_end = index + 1
                parameters_complete = True
        elif saw_parameters and parenthesis_depth == 0 and character == "{":
            return None, index
        elif saw_parameters and parenthesis_depth == 0 and character == "=":
            return _kotlin_expression_lines(text, match_start, index), None
        elif character == "\n" and saw_parameters and parenthesis_depth == 0:
            return_type = text[parameter_end:index].strip()
            angle_type = return_type.replace("->", "")
            if "@" in return_type:
                # Retain the existing body measurement for ambiguous annotated types.
                return None, None
            if return_type.endswith((":", ".", ",", "<", "->", "&")) or angle_type.count("<") > angle_type.count(">"):
                continue
            next_token = index + 1
            while next_token < len(text) and text[next_token].isspace():
                next_token += 1
            if re.search(r"\bsuspend$", return_type):
                continue
            if text.startswith((":", "->", "<", ".", "?", "&"), next_token):
                continue
            if re.match(r"where\b", text[next_token:]):
                continue
            if next_token < len(text) and text[next_token] == "{":
                return None, next_token
            if next_token < len(text) and text[next_token] == "=":
                return _kotlin_expression_lines(text, match_start, next_token), None
            end_line = text.count("\n", 0, index)
            return max(1, end_line - start_line + 1), None
    return max(1, text.count("\n", match_start) + 1), None


def top_functions(path: Path, language: str, top_n: int = 5) -> list[tuple[str, int]]:
    """Return the top_n longest functions as (name, line_count) pairs, descending."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []

    pattern = _KT_FUN_RE if language == "kotlin" else _RS_FN_RE
    kotlin_structure = (
        re.sub(
            r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',
            # A balanced placeholder retains multiline literal bounds as well as positions.
            lambda literal: "(" + "".join("\n" if char == "\n" else " " for char in literal.group()[1:-1]) + ")",
            strip_comments(text, "kotlin"),
        )
        if language == "kotlin"
        else ""
    )
    results: list[tuple[str, int]] = []
    for match in pattern.finditer(text):
        name = match.group(2)
        line_count, body_start = (
            _kotlin_function_boundary(kotlin_structure, match.start())
            if language == "kotlin"
            else (None, None)
        )
        if line_count is None:
            line_count = _measure_function_lines(
                kotlin_structure if body_start is not None else text,
                match.start(),
                body_start,
            )
        results.append((name, line_count))

    results.sort(key=lambda item: item[1], reverse=True)
    return results[:top_n]


def count_suppressions(path: Path, language: str) -> int:
    """Count suppression annotations in a source file."""
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return 0

    if language == "kotlin":
        return len(_KT_SUPPRESS_RE.findall(text))
    return len(_RS_ALLOW_RE.findall(text))


def report_hotspots(repo_root: Path, baseline_path: Path) -> None:
    """Emit a markdown hotspot report to stdout."""
    raw_data = json.loads(baseline_path.read_text(encoding="utf-8"))
    entries = raw_data.get("entries", [])

    suppression_rows: list[tuple[str, int]] = []

    for entry in entries:
        rel_path = entry["path"]
        abs_path = repo_root / rel_path
        kind = entry["kind"]
        language = "kotlin" if kind in ("kotlin", "compose") else "rust"

        if not abs_path.is_file():
            print(f"WARNING: hotspot file not found, skipping: {rel_path}", file=sys.stderr)
            continue

        filename = abs_path.name
        funcs = top_functions(abs_path, language)
        suppressions = count_suppressions(abs_path, language)
        suppression_rows.append((rel_path, suppressions))

        print(f"### {filename}")
        print(f"`{rel_path}`")
        print()
        print("| Function | Lines |")
        print("| --- | --- |")
        if funcs:
            for name, lines in funcs:
                print(f"| `{name}` | {lines} |")
        else:
            print("| _(no functions detected)_ | — |")
        print()

    print("### Suppression summary")
    print()
    print("| File | Suppressions |")
    print("| --- | --- |")
    for rel_path, count in suppression_rows:
        print(f"| `{rel_path}` | {count} |")
    print()


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify code-only LoC limits for repo-owned Rust and Kotlin sources.")
    parser.add_argument("--baseline", default=DEFAULT_BASELINE, help="Checked-in JSON baseline file.")
    parser.add_argument("--report-dir", default=DEFAULT_REPORT_DIR, help="Directory for generated reports.")
    parser.add_argument(
        "--dump-baseline",
        action="store_true",
        help="Print the current baseline payload to stdout instead of validating it.",
    )
    parser.add_argument(
        "--report-hotspots",
        action="store_true",
        help="Emit a markdown hotspot report (top-5 functions + suppression counts) for each baseline entry.",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]

    if args.report_hotspots:
        baseline_path = (repo_root / args.baseline).resolve()
        if not baseline_path.is_file():
            raise ValueError(f"LoC baseline not found: {baseline_path}")
        report_hotspots(repo_root, baseline_path)
        return 0

    measurements = collect_measurements(repo_root)

    if args.dump_baseline:
        print(json.dumps(build_baseline(measurements), indent=2))
        return 0

    baseline_path = (repo_root / args.baseline).resolve()
    if not baseline_path.is_file():
        raise ValueError(f"LoC baseline not found: {baseline_path}")

    results = evaluate_measurements(measurements, read_baseline(baseline_path))
    report_dir = (repo_root / args.report_dir).resolve()
    write_reports(report_dir, results)
    summary = format_summary(results)
    print(summary)

    new_violations = results["newViolations"]
    baseline_growth = results["baselineGrowthViolations"]
    assert isinstance(new_violations, list)
    assert isinstance(baseline_growth, list)
    return 1 if new_violations or baseline_growth else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"File LoC verification failed: {exc}", file=sys.stderr)
        raise
