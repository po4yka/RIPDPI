#!/usr/bin/env python3
"""Ratchet gate that bans new source comments.

Every tracked source file may keep at most the comment lines recorded in
config/comments/comment-ratchet-baseline.json. A file not in the baseline may
have none. The baseline can only shrink: --update refuses to raise an entry.
"""

from __future__ import annotations

import argparse
import io
import json
import re
import subprocess
import sys
import tokenize
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = ROOT / "config/comments/comment-ratchet-baseline.json"
C_LIKE = {".rs", ".kt", ".kts", ".java"}
HASH = {".py", ".sh"}
EXCLUDED_PREFIXES = ("native/rust/vendor/", "native/zapret2/", ".agents/vendor/")

EXEMPT = re.compile(
    r"^[/*!#\s]*("
    r"safety:|"
    r"noqa\b|type:\s*ignore|pragma\b|pylint:|fmt:\s*(off|on|skip)|nosec\b|"
    r"shellcheck\s|ktlint-(disable|enable)|@formatter:|language=|noinspection\b|"
    r"spdx-license-identifier|-\*-"
    r")",
    re.IGNORECASE,
)
SAFETY_HEADING = re.compile(r"^#\s+(safety|cancel safety)\b", re.IGNORECASE)
DOC_HEADING = re.compile(r"^#\s")
RUST_CHAR = re.compile(r"'(\\(u\{[0-9a-fA-F]+\}|x[0-9a-fA-F]{2}|.)|[^'\\\n])'")
RUST_RAW = re.compile(r'b?r(#*)"')


def c_like_comments(text: str, rust: bool) -> dict[int, str]:
    """Map 1-based line number to the comment text found on that line."""
    out: dict[int, list[str]] = {}
    i, line, n = 0, 1, len(text)

    def add(begin: int, end: int, first_line: int) -> None:
        for offset, part in enumerate(text[begin:end].split("\n")):
            if part.strip():
                out.setdefault(first_line + offset, []).append(part)

    while i < n:
        ch = text[i]
        if ch == "\n":
            line += 1
            i += 1
        elif text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            add(i, end, line)
            i = end
        elif text.startswith("/*", i):
            start, first, depth = i, line, 1
            i += 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    line += text[i] == "\n"
                    i += 1
            add(start, i, first)
        elif rust and (m := RUST_RAW.match(text, i)) and (i == 0 or not text[i - 1].isalnum()):
            close = '"' + m.group(1)
            end = text.find(close, m.end())
            end = n if end < 0 else end + len(close)
            line += text.count("\n", i, end)
            i = end
        elif not rust and text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            line += text.count("\n", i, end)
            i = end
        elif ch == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                line += i < n and text[i] == "\n"
                i += 1
            i += 1
        elif ch == "'":
            m = RUST_CHAR.match(text, i)
            i = m.end() if m else i + 1
        else:
            i += 1
    return {k: " ".join(v) for k, v in out.items()}


def hash_comments(text: str, python: bool) -> dict[int, str]:
    out: dict[int, str] = {}
    if python:
        try:
            for tok in tokenize.generate_tokens(io.StringIO(text).readline):
                if tok.type == tokenize.COMMENT and not (tok.start[0] == 1 and tok.string.startswith("#!")):
                    out[tok.start[0]] = tok.string
            return out
        except (tokenize.TokenError, SyntaxError, IndentationError):
            out.clear()
    for number, raw in enumerate(text.split("\n"), 1):
        stripped = raw.strip()
        if stripped.startswith("#") and not (number == 1 and stripped.startswith("#!")):
            out[number] = stripped
    return out


def counted_lines(path: str, text: str) -> dict[int, str]:
    suffix = Path(path).suffix
    if suffix in C_LIKE:
        comments = c_like_comments(text, rust=suffix == ".rs")
    elif suffix in HASH:
        comments = hash_comments(text, python=suffix == ".py")
    else:
        return {}
    counted: dict[int, str] = {}
    in_safety_section, last = False, -1
    for number in sorted(comments):
        body = re.sub(r"^\s*(//[/!]?|/\*+|\*+/?|#)\s?", "", comments[number].strip())
        if number != last + 1:
            in_safety_section = False
        last = number
        if SAFETY_HEADING.match(body):
            in_safety_section = True
        elif DOC_HEADING.match(body):
            in_safety_section = False
        if not (in_safety_section or EXEMPT.match(comments[number].strip())):
            counted[number] = comments[number].strip()
    return counted


def count_comments(path: str, text: str) -> int:
    return len(counted_lines(path, text))


def list_comments(root: Path, prefix: str) -> None:
    for path in tracked_sources(root):
        if path.startswith(prefix) and (root / path).is_file():
            text = (root / path).read_text(encoding="utf-8", errors="replace")
            for number, body in counted_lines(path, text).items():
                print(f"{path}:{number}: {body}")


def tracked_sources(root: Path) -> list[str]:
    patterns = [f"*{suffix}" for suffix in sorted(C_LIKE | HASH)]
    listed = subprocess.run(
        ["git", "ls-files", "-z", "--", *patterns], cwd=root, check=True, capture_output=True, text=True
    ).stdout.split("\0")
    return [p for p in listed if p and not p.startswith(EXCLUDED_PREFIXES)]


def measure(root: Path) -> dict[str, int]:
    counts: dict[str, int] = {}
    for path in tracked_sources(root):
        file = root / path
        if not file.is_file():
            continue
        count = count_comments(path, file.read_text(encoding="utf-8", errors="replace"))
        if count:
            counts[path] = count
    return counts


def load_baseline(path: Path) -> dict[str, int]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))["files"]


def write_baseline(path: Path, counts: dict[str, int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"total": sum(counts.values()), "files": dict(sorted(counts.items()))}
    path.write_text(json.dumps(payload, indent=1) + "\n", encoding="utf-8")


def compare(current: dict[str, int], baseline: dict[str, int]) -> tuple[list[str], list[str]]:
    grown = [f"{p}: {c} comment lines, baseline {baseline.get(p, 0)}" for p, c in sorted(current.items()) if c > baseline.get(p, 0)]
    shrunk = [p for p, c in sorted(baseline.items()) if current.get(p, 0) < c]
    return grown, shrunk


def report(counts: dict[str, int]) -> None:
    areas: Counter[str] = Counter()
    for path, count in counts.items():
        areas["/".join(path.split("/")[:2])] += count
    for area, count in areas.most_common():
        print(f"{count:7d}  {area}")
    print(f"{sum(counts.values()):7d}  total")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--update", action="store_true", help="lower the baseline to the current counts")
    mode.add_argument("--report", action="store_true", help="print comment lines per area")
    mode.add_argument("--list", metavar="PATH_PREFIX", help="print each counted comment line under a path")
    parser.add_argument("--init", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.list is not None:
        list_comments(ROOT, args.list)
        return 0
    current = measure(ROOT)
    if args.report:
        report(current)
        return 0
    if args.init:
        write_baseline(BASELINE, current)
        return 0
    baseline = load_baseline(BASELINE)
    grown, shrunk = compare(current, baseline)
    if grown:
        print("New comments are banned. Delete them, or move decisions to docs/adr/ (see the delete-comment skill):")
        print("\n".join(f"  {line}" for line in grown))
        return 1
    if args.update:
        write_baseline(BASELINE, current)
        print(f"Baseline lowered to {sum(current.values())} comment lines.")
        return 0
    if shrunk:
        print("Comments were removed; lock the gain with: python3 scripts/ci/check_comment_ratchet.py --update")
        print("\n".join(f"  {path}" for path in shrunk[:50]))
        return 1
    print(f"Comment ratchet OK: {sum(current.values())} comment lines.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
