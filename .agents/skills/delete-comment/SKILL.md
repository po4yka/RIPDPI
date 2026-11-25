---
name: delete-comment
description: Never invoke implicitly. Classifies and deletes source comments under one path, moves decisions to docs/adr/, files tasks for excuses, and lowers the comment ratchet. Use when asked to delete comments.
disable-model-invocation: true
---

# Delete comments

The comment ratchet `scripts/ci/check_comment_ratchet.py` bans new comments. Each file may keep only the comment lines in `config/comments/comment-ratchet-baseline.json`, and the baseline can only go down. This skill removes the existing comments one area at a time.

## 1. Pick one area

```bash
python3 scripts/ci/check_comment_ratchet.py --report
python3 scripts/ci/check_comment_ratchet.py --list <path-prefix>
```

Take one module or crate. Each parallel writer uses its own worktree and its own area.

## 2. Classify each comment

Read each comment block with the code next to it before you decide. Do not delete a whole file's comments in bulk: a lost decision record cannot be found again. Test comments that say why a test is built a certain way (a race, a timing, a fixture limit) are decision records. Doc comments (`///`, `//!`, `/** */`) are in scope.

After the edit, prove that only comments changed. This command fails if any source file differs from the revision in more than comments and whitespace:

```bash
python3 scripts/ci/check_comment_ratchet.py --comment-only HEAD
```

| Category | Test | Action |
| --- | --- | --- |
| Tautology | The name, type, or code already says it. | Delete. |
| Stale | It names a symbol, file, task, behavior, or number that is gone or different. Check with `git grep`. | Delete. |
| Excuse | It explains away a hack: workaround, `TODO`, `shortcut:`, "for now", "temporary", "ideally", "hack", a known bug. | Delete. Record the hack for step 4. |
| Decision record | A non-obvious reason the code cannot show: a protocol or spec rule, an ordering or lifetime rule, a security or privacy reason, a measured value, a rejected option. | Move to an ADR (step 3), then delete. |

If a comment is two categories, use the stronger one: decision record, then excuse.

## Keep these

- `// SAFETY:` comments and rustdoc or KDoc `# Safety` and `# Cancel safety` sections. The ratchet does not count them. When you delete the other lines of such a KDoc block, keep its `/**` and `*/`.
- Tool directives (`noqa`, `shellcheck`, `ktlint-disable`, `language=`, `@formatter:`), shebangs, and licence headers. The ratchet does not count them.
- Vendored trees: `native/rust/vendor/`, `native/zapret2/`, `.agents/vendor/`.
- A comment that a gate needs: docs in a crate with `#![deny(missing_docs)]`, or the only content of a body that detekt `EmptyCatchBlock` or `EmptyFunctionBlock` would flag. Leave it; it stays in the baseline. Report it.

## 3. Move decision records

Write one ADR for the area: `docs/adr/NNNN-<area>-code-decisions.md`, with the next free number. Copy the header of an existing ADR (`# ADR NNNN: ...`, `> Status: Accepted. Decision date: ...`, `## Context`). Then add one `### <path>#<Symbol>` section for each decision, in ASD-STE100 Simplified Technical English. Point to code by path and symbol, never by line number. Add a row to the table in `docs/adr/README.md`.

## 4. File excuses

For each hack (merge duplicates), create a task:

```bash
./taskctl new --kind chore --priority low --area <area> --owner <owner> --spec-mode not-required --spec-reason mechanical-refactor --title "<Remove the hack in Symbol>"
```

Write the location (`path#Symbol`), what the hack does, and the fix in the created `docs/tasks/issues/<slug>.md`. Then run `./taskctl validate` and `./taskctl generate-board`.

## 5. Clean up and check

- Delete blank lines left in a run, and an empty `/** */`.
- Run `--comment-only HEAD` before any lint fix, so that it proves the deletions alone.
- Run the formatter (`cargo fmt`, ktlint), the linter (`cargo clippy -p <crate> --all-targets --locked -- -D warnings`, the module's `detekt`), and the narrowest test gate (`cargo test -p <crate> --locked`, the module's unit tests). A deletion can expose a lint: clippy `collapsible_if` once a comment between two `if`s is gone, or an unused import that only a KDoc `[link]` used. Fix it in code and name each such edit in the commit message.
- Lower the baseline, then check it:

```bash
python3 scripts/ci/check_comment_ratchet.py --update
python3 scripts/ci/check_comment_ratchet.py
```

Commit the area as `refactor(<area>): delete comments`. The ADR, tasks, and baseline go in the same commit.
