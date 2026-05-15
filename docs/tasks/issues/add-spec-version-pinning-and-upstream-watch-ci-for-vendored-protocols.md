---
title: Add SPEC_VERSION pinning and upstream-watch CI for vendored protocols
type: task
status: done
area: ci
priority: high
owner: unassigned
parent: epic-control-plane-hardening
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [x] #task Add SPEC_VERSION pinning and upstream-watch CI for vendored protocols #repo/RIPDPI #area/ci #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols`
- **Verify:** `just verify-spec-versions`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-vless/**`, `native/rust/crates/ripdpi-xhttp/**`, `native/rust/crates/ripdpi-hysteria2/**`, `native/rust/crates/ripdpi-tuic/**`, `native/rust/crates/ripdpi-shadowtls/**`, `native/rust/crates/ripdpi-naiveproxy/**`, `native/rust/crates/ripdpi-ws-tunnel/**`, `scripts/ci/**`, `.github/workflows/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Pin every vendored wire-format reimplementation to a known upstream
commit/tag and add a weekly CI job that diffs upstream release notes
against those pins, opening an issue when drift is detected.

## Context

RIPDPI vendors wire formats from xray-core (VLESS, REALITY, XTLS-Vision,
XHTTP, FinalMask), apernet/hysteria (Hysteria 2, Salamander, port-hopping),
EAimTY/tuic (TUIC v5), ihciah/shadow-tls (ShadowTLS v3), klzgrad/naiveproxy
(SOCKS5↔HTTPS helper), and Telegram MTProto obfuscated2. The "spec" is
whatever the upstream reference implementation does at a snapshot commit;
there is no standards body, no in-band version negotiation, and no CI signal
when upstream ships a wire-affecting change.

The existing
[[recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes]] task
documents the need but is `status: backlog, owner: unassigned`. This task
operationalizes it across all vendored protocols, not only xray-core.

## Acceptance criteria

- [ ] `SPEC_VERSION.md` lives at the root of each protocol crate
    (`ripdpi-vless`, `ripdpi-xhttp`, `ripdpi-hysteria2`, `ripdpi-tuic`,
    `ripdpi-shadowtls`, `ripdpi-naiveproxy`, `ripdpi-ws-tunnel`) and names
    the exact upstream repo, commit SHA, and tag being tracked.
- [ ] A `scripts/ci/verify_spec_versions.py` (or shell equivalent) reads
    every `SPEC_VERSION.md` and fails CI when the format is malformed or
    the referenced commit is unreachable on the upstream remote.
- [ ] A `.github/workflows/upstream-spec-watch.yml` runs weekly, diffs the
    pinned commit against the upstream default branch for each protocol,
    and opens (or updates) a single tracking issue listing protocols with
    drift, including the upstream changelog entries since the pin.
- [ ] The watch job is non-blocking for PRs but is documented as a Tier-1
    follow-up obligation in `docs/strategy-pack-operations.md`.
- [ ] xray-core watch is wired into the same job so
    [[recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes]]
    can be closed by reference.

## Definition of done

- All seven protocol crates carry pinned `SPEC_VERSION.md` files.
- Weekly job has fired at least once on `main` and produced an issue with
  a drift report (or "no drift" comment).
- Verify command exits 0 in CI and locally.

## Risks / open questions

- Cadence: weekly may be too noisy for Hysteria/TUIC (slow release) and
  too sparse for xray-core (fast release). Consider per-protocol cadence
  if signal-to-noise is poor after 4 weeks.
- Some upstream changes (e.g. xray-core `allowInsecure` auto-disable on
  2026-06-01) are config-surface changes, not wire changes; the diff
  step should grep changelogs for both kinds.

## Links

- [[recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes]]
- [[Epic - Control-plane hardening]]
- [[Sign host-pack manifests with app-trusted keys]]
