#!/usr/bin/env python3
"""Ratchet gate that bans new source comments.

Every tracked source file may keep at most the comment lines recorded in
config/comments/comment-ratchet-baseline.json. A file not in the baseline may
have none. The baseline can only shrink: --update and --against refuse to raise
an entry, except that the count of a removed file may move to a new file.
"""

from __future__ import annotations

import argparse
import bisect
import io
import json
import re
import subprocess
import sys
import tokenize
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE_PATH = "config/comments/comment-ratchet-baseline.json"
BASELINE = ROOT / BASELINE_PATH
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
SAFETY_COMMENT = re.compile(r"^//\s*safety:", re.IGNORECASE)
SAFETY_HEADING = re.compile(r"^#\s+(safety|cancel safety)\b", re.IGNORECASE)
DOC_HEADING = re.compile(r"^#\s")
DOC_LINE = re.compile(r"^(///|//!|/\*\*|\*)")
RUST_CHAR = re.compile(r"'(\\(u\{[0-9a-fA-F]+\}|x[0-9a-fA-F]{2}|.)|[^'\\\n])'")
RUST_RAW = re.compile(r'b?r(#*)"')

Span = tuple[int, int]


def scan_c_like(text: str, rust: bool) -> tuple[list[Span], list[Span]]:
    """Return the comment spans and the top-level string spans of the text."""
    comments: list[Span] = []
    strings: list[Span] = []
    n = len(text)

    def kotlin_string(i: int) -> int:
        if text.startswith('"""', i):
            end = i + 3
            while end < n and not text.startswith('"""', end):
                end = code(end + 2, in_template=True) if text.startswith("${", end) else end + 1
            end += 3
            while end < n and text[end] == '"':
                end += 1
            return min(end, n)
        i += 1
        while i < n and text[i] not in '"\n':
            if text[i] == "\\":
                i += 2
            elif text.startswith("${", i):
                i = code(i + 2, in_template=True)
            else:
                i += 1
        return min(i + 1, n)

    def code(i: int, in_template: bool = False) -> int:
        depth = 0
        while i < n:
            ch = text[i]
            start = i
            if in_template and ch == "}" and depth == 0:
                return i + 1
            if text.startswith("//", i):
                end = text.find("\n", i)
                i = n if end < 0 else end
                comments.append((start, i))
            elif text.startswith("/*", i):
                nesting = 1
                i += 2
                while i < n and nesting:
                    if text.startswith("/*", i):
                        nesting, i = nesting + 1, i + 2
                    elif text.startswith("*/", i):
                        nesting, i = nesting - 1, i + 2
                    else:
                        i += 1
                comments.append((start, i))
            elif rust and (m := RUST_RAW.match(text, i)) and (i == 0 or not text[i - 1].isalnum()):
                close = '"' + m.group(1)
                end = text.find(close, m.end())
                i = n if end < 0 else end + len(close)
                if not in_template:
                    strings.append((start, i))
            elif not rust and ch == '"':
                i = kotlin_string(i)
                if not in_template:
                    strings.append((start, i))
            elif ch == '"':
                i += 1
                while i < n and text[i] != '"':
                    i += 2 if text[i] == "\\" else 1
                i = min(i + 1, n)
                strings.append((start, i))
            elif ch == "'" and (m := RUST_CHAR.match(text, i)):
                i = m.end()
                strings.append((start, i))
            else:
                depth += (ch == "{") - (ch == "}")
                i += 1
        return i

    code(0)
    return comments, strings


def c_like_comments(text: str, rust: bool) -> dict[int, str]:
    """Map 1-based line number to the comment text found on that line."""
    out: dict[int, list[str]] = {}
    newlines = [i for i, ch in enumerate(text) if ch == "\n"]
    for begin, end in scan_c_like(text, rust)[0]:
        first_line = bisect.bisect_left(newlines, begin) + 1
        for offset, part in enumerate(text[begin:end].split("\n")):
            if part.strip():
                out.setdefault(first_line + offset, []).append(part)
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
    lines = text.split("\n")
    counted: dict[int, str] = {}
    run: list[int] = []
    run_has_safety, in_safety_section, last = False, False, -1
    for number in sorted(comments):
        comment = comments[number].strip()
        body = re.sub(r"^\s*(//[/!]?|/\*+|\*+/?|#)\s?", "", comment).strip(" */")
        full_line = comment == lines[number - 1].strip()
        if number != last + 1 or not full_line:
            in_safety_section = run_has_safety = False
            run = []
        last = number
        if full_line and SAFETY_COMMENT.match(comment):
            in_safety_section = True
        elif full_line and DOC_LINE.match(comment) and SAFETY_HEADING.match(body):
            in_safety_section = run_has_safety = True
            for earlier in run:
                if not counted.get(earlier, "x").strip("/* "):
                    del counted[earlier]
        elif DOC_HEADING.match(body):
            in_safety_section = False
        delimiter_of_safety_block = run_has_safety and not body
        if not (in_safety_section or delimiter_of_safety_block or EXEMPT.match(comment)):
            counted[number] = comment
            run.append(number)
    return counted


def count_comments(path: str, text: str) -> int:
    return len(counted_lines(path, text))


def code_only(path: str, text: str) -> str:
    """Return the code with comments removed and whitespace outside strings collapsed."""
    suffix = Path(path).suffix
    if suffix == ".py":
        try:
            tokens = tokenize.generate_tokens(io.StringIO(text).readline)
            return repr([(t.type, t.string) for t in tokens if t.type not in (tokenize.COMMENT, tokenize.NL)])
        except (tokenize.TokenError, SyntaxError, IndentationError):
            pass
    if suffix not in C_LIKE:
        kept = [re.sub(r"\s+", " ", line).strip() for line in text.split("\n") if not line.strip().startswith("#")]
        return "\n".join(line for line in kept if line)
    comments, strings = scan_c_like(text, rust=suffix == ".rs")
    newline_ends_statement = suffix in {".kt", ".kts"}

    def squeeze(gap: list[str]) -> str:
        joined = " ".join(gap)
        if newline_ends_statement:
            joined = re.sub(r"\s*\n\s*", "\n", joined)
            return re.sub(r"[^\S\n]+", " ", joined)
        return re.sub(r"\s+", " ", joined)

    pieces: list[str] = []
    gap: list[str] = []
    cursor = 0
    for begin, end, is_string in sorted([(b, e, False) for b, e in comments] + [(b, e, True) for b, e in strings]):
        if begin < cursor:
            continue
        gap.append(text[cursor:begin])
        if is_string:
            pieces.append(squeeze(gap))
            pieces.append(text[begin:end])
            gap = []
        else:
            gap.append(" ")
        cursor = end
    gap.append(text[cursor:])
    pieces.append(squeeze(gap))
    return "".join(pieces).strip()


def git(root: Path, *args: str, check: bool = True) -> str:
    return subprocess.run(["git", *args], cwd=root, check=check, capture_output=True, text=True).stdout


def changed_code(root: Path, rev: str) -> list[str]:
    patterns = [f"*{s}" for s in sorted(C_LIKE | HASH)]
    changed = git(root, "diff", "--name-only", rev, "--", *patterns).split()
    untracked = git(root, "ls-files", "--others", "--exclude-standard", "--", *patterns).split()
    bad = [f"{path} (new file)" for path in untracked]
    for path in changed:
        if not (root / path).is_file():
            bad.append(f"{path} (deleted)")
            continue
        old = git(root, "show", f"{rev}:{path}", check=False)
        new = (root / path).read_text(encoding="utf-8", errors="replace")
        if code_only(path, old) != code_only(path, new):
            bad.append(path)
    return bad


def list_comments(root: Path, prefix: str) -> None:
    for path in tracked_sources(root):
        if path.startswith(prefix) and (root / path).is_file():
            text = (root / path).read_text(encoding="utf-8", errors="replace")
            for number, body in counted_lines(path, text).items():
                print(f"{path}:{number}: {body}")


def tracked_sources(root: Path) -> list[str]:
    patterns = [f"*{suffix}" for suffix in sorted(C_LIKE | HASH)]
    listed = git(root, "ls-files", "-z", "--", *patterns).split("\0")
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


def parse_baseline(raw: str) -> dict[str, int]:
    return json.loads(raw)["files"] if raw.strip() else {}


def write_baseline(path: Path, counts: dict[str, int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {"total": sum(counts.values()), "files": dict(sorted(counts.items()))}
    path.write_text(json.dumps(payload, indent=1) + "\n", encoding="utf-8")


def compare(current: dict[str, int], baseline: dict[str, int]) -> tuple[list[str], list[str]]:
    grown = [
        f"{p}: {c} comment lines, baseline {baseline.get(p, 0)}"
        for p, c in sorted(current.items())
        if c > baseline.get(p, 0)
    ]
    shrunk = [p for p, c in sorted(baseline.items()) if current.get(p, 0) < c]
    return grown, shrunk


def rename_sources(root: Path, rev: str) -> dict[str, str]:
    """Map each file that git sees as renamed or copied since REV to its source."""
    pairs: dict[str, str] = {}
    for line in git(root, "diff", "-M", "-C", "--name-status", rev, check=False).splitlines():
        fields = line.split("\t")
        if len(fields) == 3 and fields[0][:1] in "RC":
            pairs[fields[2]] = fields[1]
    return pairs


def raised_entries(old: dict[str, int], new: dict[str, int], sources: dict[str, str]) -> list[str]:
    """Entries of `new` above `old`. A renamed, copied, or split file may take
    the count that its own source gave up, and no more."""
    raised = [f"{p}: {new[p]} > {old[p]}" for p in sorted(new) if p in old and new[p] > old[p]]
    budget = {src: old.get(src, 0) - new.get(src, 0) for src in set(sources.values())}
    for path, count in sorted(new.items()):
        if path in old:
            continue
        source = sources.get(path)
        if source is not None and budget.get(source, 0) >= count:
            budget[source] -= count
        else:
            raised.append(f"{path}: {count} new comment lines")
    return raised


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
    mode.add_argument("--comment-only", metavar="REV", help="fail if a source file differs from REV in more than comments")
    mode.add_argument("--against", metavar="REV", help="fail if the baseline raises an entry of the baseline at REV")
    parser.add_argument("--init", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    if args.comment_only:
        bad = changed_code(ROOT, args.comment_only)
        print("\n".join(f"code changed: {path}" for path in bad) or "Only comments and whitespace changed.")
        return 1 if bad else 0
    if args.list is not None:
        list_comments(ROOT, args.list)
        return 0
    baseline = parse_baseline(BASELINE.read_text(encoding="utf-8")) if BASELINE.exists() else {}
    if args.against:
        old = parse_baseline(git(ROOT, "show", f"{args.against}:{BASELINE_PATH}", check=False))
        raised = raised_entries(old, baseline, rename_sources(ROOT, args.against)) if old else []
        print("\n".join(f"baseline raised: {line}" for line in raised) or "Baseline only shrank.")
        return 1 if raised else 0
    current = measure(ROOT)
    if args.report:
        report(current)
        return 0
    if args.init:
        write_baseline(BASELINE, current)
        return 0
    if args.update:
        committed = parse_baseline(git(ROOT, "show", f"HEAD:{BASELINE_PATH}", check=False)) or baseline
        raised = raised_entries(committed, current, rename_sources(ROOT, "HEAD"))
        if raised:
            print("New comments are banned. Delete them, or move decisions to docs/adr/ (see the delete-comment skill):")
            print("\n".join(f"  {line}" for line in raised))
            return 1
        write_baseline(BASELINE, current)
        print(f"Baseline lowered to {sum(current.values())} comment lines.")
        return 0
    grown, shrunk = compare(current, baseline)
    if grown:
        print("New comments are banned. Delete them, or move decisions to docs/adr/ (see the delete-comment skill).")
        print("For a renamed or split file, run --update to move the count of the removed path:")
        print("\n".join(f"  {line}" for line in grown))
        return 1
    if shrunk:
        print("Comments were removed; lock the gain with: python3 scripts/ci/check_comment_ratchet.py --update")
        print("\n".join(f"  {path}" for path in shrunk[:50]))
        return 1
    print(f"Comment ratchet OK: {sum(current.values())} comment lines.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
