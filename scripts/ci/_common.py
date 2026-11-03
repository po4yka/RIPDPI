"""Helpers shared by the CI scripts in this directory.

Scripts import this module after putting their own directory on ``sys.path``,
so it resolves for direct runs from any working directory and for imports.
"""

from __future__ import annotations

import re
from pathlib import Path

REPO_ROOT: Path = Path(__file__).resolve().parents[2]

_BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
_LINE_COMMENT_RE = re.compile(r"//[^\n]*")


def strip_comments(text: str) -> str:
    """Remove Rust-style block and line comments (not string-literal aware)."""
    text = _BLOCK_COMMENT_RE.sub("", text)
    return _LINE_COMMENT_RE.sub("", text)
