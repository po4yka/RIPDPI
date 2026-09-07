---
id: CIC-1788601572312278
title: Remove duplicate test execution and CI setup work
kind: chore
status: done
area: ci
priority: high
owner: npochaev
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-05
updated: 2026-09-05
spec_reason: tooling-only
closed_at: "2026-09-05T10:25:08Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Local workflow contracts, full Rust workspace, dedicated network/turmoil/nested lanes, independent AWG peer, inventories, clippy, formatting, and architecture gates passed. Measurements and residual flaky-test/hosted-CI limits are recorded in the task.
---

## Goal

Remove duplicate Rust test execution, extra build graphs, and unused test setup from CI.

## Acceptance criteria

- Existing unit, network, nested-proxy, turmoil, and startup smoke coverage remains executable.
- The workspace and dedicated test lanes avoid duplicate unit execution.
- Failed preflight blocks expensive jobs; lint remains a final required gate.
- JNI symbol validation builds only its consumed ABI.
- MASQUE auth tests use immediate QUIC refusal; the separate idle-timeout fallback test remains intact.
- Workflow and launcher regression tests pass; real Rust tests and test inventories validate the selection.

## Verification evidence

- Final macOS CI-profile workspace run: 5272 passed in 35.584 seconds. One existing Shadowsocks fragmented-read test passed on its configured second attempt; its first attempt returned an empty first payload. No retry policy changed.
- Startup smoke: 1 passed, using the existing workspace binaries. Turmoil lane: 16 passed in 0.834 seconds.
- Network lane: 30 passed; nested-proxy lane: 6 passed. Their runtime name sets are disjoint and their union equals the previous 36-case macOS inventory. Linux-only cases require hosted CI. The independent AWG peer check passed with the pinned upstream implementation.
- Workspace inventories retain 751 relay/fixture tests and 462 ordinary tunnel/resolver tests without repeating them in dedicated lanes. All 153 independent desync tests remain, including 50 TCP-plan tests; only the nested Cargo meta-test was removed.
- MASQUE auth test: 0.119 seconds in the final workspace run, down from about 60 seconds. The separate real idle-timeout fallback test remains and passed in 30.064 seconds.
- Python workflow and launcher contracts: 158 passed. Taskctl and harness-link tests: 33 passed. actionlint, pinact, shell syntax, Cargo formatting, targeted fixture/MASQUE clippy, locked metadata, architecture contracts, architecture health, and hotspot budgets passed.
- A plain-profile QUIC network timeout also reproduced on the unchanged base commit adff870627417f47c78d117af193f327c7a8271d. Final CI-profile network checks passed.
- Hosted CI duration and Android/Linux execution remain to be measured after publication. Local times are observations, not a prediction of the full pipeline duration.
