---
id: RST-1794556971423909
title: Fix multidisorder delivery and configuration contracts
kind: bug
status: review
area: rust-native
priority: high
owner: DPI-13 team
parent: null
blocked_by: []
spec_mode: required
openspec_change: dpi13-audit-fixes
created: 2026-11-13
updated: 2026-11-13
status_detail: Five finding commits passed independent review and combined local gates
---

## Goal

Resolve DPI-13 audit findings F1-F5 while preserving the native multidisorder scope and fail-closed behavior.

## Acceptance criteria

- A possibly delivered genuine payload cannot trigger automatic replay after a multidisorder error.
- The helper accepts bounded negotiated bulk multidisorder requests and rejects invalid limits before mutation.
- Marker sorting, duplicate positions and activation do not silently change accepted grouped settings.
- Explicit original flags do not cause an immediate ordinary payload write after raw emission.
- Documentation states the actual root and fallback gates.
- Targeted regression tests, independent review and combined CI source gates pass; each finding has a separate commit.

## Ownership and serialized lanes

- Runtime worker: ripdpi-desync-runtime, ripdpi-config, ripdpi-desync, ripdpi-proxy-config and Kotlin chain validation/model tests plus the generated ripdpi-config API snapshot; F1 then F3 in separate commits. F1 worktree /tmp/ripdpi-dpi13-runtime-20261113; F3 worktree /tmp/ripdpi-dpi13-settings-20261113. The same worker owns both; F1 inputs stay frozen while its gated test waits.
- IPC worker: ripdpi-root-helper-protocol plus the root-helper client bulk transport tests; F2. Worktree /tmp/ripdpi-dpi13-ipc-20261113. This worker alone owns protocol capability metadata; no schema version bump without review.
- Emitter worker: ripdpi-privileged-ops and narrowly required root-helper handler tests; F4 and evidence for repair cleanup/MTU risks. Worktree /tmp/ripdpi-dpi13-emitter-20261113.
- Integrator: docs/native/proxy-engine.md, planning/task artifacts, final review, combined gates, main integration and push; F5.
- Cargo.lock, dependency manifests, architecture baselines, unrelated API snapshots, golden fixtures and locales have no write owner: do not change them without a demonstrated need and coordination.
- After review, workers handed frozen patches to the integrator for combined checks and separate atomic commits.
- Workers do not edit shared portfolio/OpenSpec files. All workers preserve unrelated work and do not push or integrate main.

## Evidence

Audit: /Users/npochaev/Downloads/RIPDPI_network_audits/DPI-13.md at audited SHA 7cdc4bd49de465a85058236132ecba3597b76638. Rechecked against job base 2d74ada2c26d38423439c3e00bd612f312cf3f84.
