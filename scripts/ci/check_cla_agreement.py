#!/usr/bin/env python3
"""Fail a pull request whose description lacks the CLA agreement statement from CLA.md."""

import os
import re
import sys

OWNER = "po4yka"
STATEMENT = "I have read the RIPDPI Contributor License Agreement (CLA.md) and I agree to its terms for this contribution"
# A whole line: plain, quoted, or a checked task-list item. An unchecked "- [ ]" item does not match.
AGREEMENT_LINE = re.compile(r"^\s*(?:>\s*|[-*+]\s+\[[xX]\]\s+)?" + re.escape(STATEMENT) + r"\.?\s*$", re.MULTILINE)


def is_exempt(author: str) -> bool:
    # GitHub reserves the "[bot]" suffix for app accounts such as dependabot[bot] and renovate[bot].
    return author == OWNER or author.endswith("[bot]")


def has_agreement(body: str) -> bool:
    return AGREEMENT_LINE.search(body.replace("\r\n", "\n")) is not None


def main() -> int:
    author = os.environ.get("PR_AUTHOR", "")
    if is_exempt(author):
        print(f"CLA check skipped: {author} is exempt.")
        return 0
    if has_agreement(os.environ.get("PR_BODY") or ""):
        print("CLA agreement statement found.")
        return 0
    print(
        "::error title=CLA agreement missing::Add this line to the pull request description, "
        f"or tick the CLA checkbox in the template: {STATEMENT}. See CLA.md."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
