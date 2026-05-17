---
title: Tag protocol contract fixtures by upstream version
type: task
status: blocked
area: testing
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: [add-hysteria2-salamander-obfuscation-conformance-fixtures, add-vless-mux-conformance-tests-against-xray-core]
created: 2026-05-15
updated: 2026-05-16
---

## Blocker (2026-05-15)

The `contract-fixtures/` and `diagnostics-contract-fixtures/` trees currently hold only schema-level JSON contracts (proxy/diagnostics field shapes, TLS template acceptance corpora). There are **no per-protocol wire-format fixtures** to tag yet. The fixture-tagging work is well-defined but depends on those fixtures existing first:

- [[add-hysteria2-salamander-obfuscation-conformance-fixtures]] produces Salamander wire goldens.
- [[add-vless-mux-conformance-tests-against-xray-core]] produces VLESS-mux frame goldens.

Once either lands, this task unblocks and the directory layout + CI check can be added. The SPEC_VERSION.md pinning prerequisite (`add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols`) is already complete; tag values come from those files.

- [ ] #task Tag protocol contract fixtures by upstream version #repo/RIPDPI #area/testing #status/blocked 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `tag-protocol-contract-fixtures-by-upstream-version`
- **Verify:** `cargo test -p ripdpi-diagnostics-protocols -p ripdpi-vless -p ripdpi-tuic -p ripdpi-hysteria2`
- **Scope (only modify these + this file + the ledger):** `contract-fixtures/**`, `diagnostics-contract-fixtures/**`, `native/rust/crates/ripdpi-diagnostics-protocols/**`, `scripts/ci/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** `add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Reorganize `contract-fixtures/` and `diagnostics-contract-fixtures/` so each protocol's wire fixtures live under `<protocol>/<upstream-tag>/<scenario>.bin` and CI verifies the path matches each crate's pinned `SPEC_VERSION.md`.

## Context

Today's fixture trees are organized per-crate, not per-spec-version. A reader cannot tell from the directory layout which xray-core / Hysteria / TUIC revision a given golden was captured against. When the upstream-watch CI ([[add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols]]) lands, the natural follow-up is to express "we are conformant to upstream tag X" as a checkable fact in the fixture tree.

## Acceptance criteria

- [ ] Fixture files for each vendored protocol move under a `<protocol>/<upstream-tag>/` subdirectory; tag names match the crate's pinned `SPEC_VERSION.md`.
- [ ] Existing test loaders are updated to walk the new layout.
- [ ] A CI check fails when a fixture exists for a tag that no crate pins, or when a crate's pinned tag has no fixtures.
- [ ] At least one fixture per protocol covers the current pinned tag; historical fixtures may be retained under prior tags but are not required.

## Definition of done

- Every fixture is reachable from its protocol crate's tests under the new path.
- The fixture-vs-pin consistency check is wired into CI.

## Risks / open questions

- Renaming a fixture directory may invalidate cached test data; flag the change in the PR description.
- If `SPEC_VERSION.md` uses long upstream SHAs, directory names get noisy. Allow either short-SHA or release-tag as the directory name.

## Links

- [[add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols]]
- [[Epic - Control-plane hardening]]

## Work log

- 2026-05-16: Dropped orphaned blocker reference 'add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols' (file does not exist); two remaining blockers are valid.
