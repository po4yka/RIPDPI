---
name: RTK token filter intercepts rg output
description: rg commands filtered by RTK produce "N matches in 0 files" with truncated output; use rtk proxy rg to bypass
type: feedback
---

When running `rg` directly, RTK's token filter intercepts results and produces output like "10 matches in 0 files / [+10 more]" with no actual file paths. This makes file-listing impossible.

**Why:** RTK is configured to suppress high-volume rg output to save tokens, but for architecture auditing we need full file lists.

**How to apply:** Always use `rtk proxy rg` for `rg -l` (list-files) queries in this repo. Use plain `rg -n` with `| head` for content searches — RTK allows those through because the line count is bounded.
