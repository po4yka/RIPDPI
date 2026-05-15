---
title: Populate xray-protos crate with parsed Xray config schema
type: task
status: backlog
area: outbound
priority: high
owner: unassigned
parent: epic-xray-provider-mode
blocks: [add-xray-provider-regression-matrix, render-validated-xray-client-configs]
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Populate xray-protos crate with parsed Xray config schema #repo/RIPDPI #area/outbound #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `populate-xray-protos-crate-with-config-schema`
- **Verify:** `cargo test -p xray-protos && cargo build -p xray-protos --release`
- **Scope (only modify these + this file + the ledger):** `xray-protos/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

The `xray-protos/` crate is currently a stub but is depended on by the
Xray provider mode epic. Decide whether to vendor protobuf definitions
from xray-core or maintain a hand-rolled `serde_json` schema, then ship
a parser + validator + golden tests for the chosen path.

## Context

The repo has an `xray-protos/` directory containing only an empty
`main/` placeholder. Several backlog tasks in `epic-xray-provider-mode`
depend on a parsed Xray config representation:

- [[add-xray-provider-regression-matrix]]
- [[render-validated-xray-client-configs]]

This is the largest single roadmap gap visible in the protocol-spec
audit.

## Decision

The implementer should record their pick in a short ADR-style note
under `docs/architecture/`:

- **Option A — vendor `app.proto` and other xray-core `.proto` files**
  from a pinned upstream tag, run `prost-build`, expose typed structs.
  Pros: full fidelity, every Xray config representable. Cons: large
  protobuf surface, ties build to a `protoc` toolchain, harder to
  evolve independently.
- **Option B — hand-rolled `serde_json` schema** covering only the
  config shapes RIPDPI exposes via the editor (VLESS/REALITY, XHTTP,
  routing). Pros: small, no proto build dep, easy to validate. Cons:
  must be kept in sync with upstream manually.

## Acceptance criteria

- [ ] ADR note documents the choice and the rationale.
- [ ] `xray-protos` builds and ships either generated Rust types or a
    hand-rolled schema, with a public API the engine can call.
- [ ] Round-trip tests parse a known-good Xray client config and
    re-serialize it without semantic loss.
- [ ] Validation rejects (a) VLESS without flow when the host-pack
    targets a post-2026-06-01 xray-core, and (b) any known broken
    combinations called out in the upstream-watch task.
- [ ] At least three positive and three negative golden configs live
    under `xray-protos/tests/fixtures/`.

## Definition of done

- `cargo test -p xray-protos` is green and exercises validation paths.
- `epic-xray-provider-mode` no longer lists "config schema" as an open
  blocker.

## Risks / open questions

- Option A pulls a protobuf compiler into every developer's build path;
  factor that into the decision.
- Either way, this task pairs naturally with
  [[add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols]]
  so the schema's upstream tag is pinned and watched.

## Links

- [[Epic - Xray provider mode]]
- [[add-xray-provider-regression-matrix]]
- [[render-validated-xray-client-configs]]
- [[recurring-upstream-watch-for-xray-core-reality-ech-xhttp-changes]]
