---
title: Add ripdpi-vpn-deploy fleet compatibility golden-file tests
type: task
status: backlog
area: testing
priority: high
owner: unassigned
parent: epic-ripdpi-vpn-deploy-fleet-compatibility
blocks: []
blocked_by: []
created: 2026-05-14
updated: 2026-05-14
---

- [ ] #task Add ripdpi-vpn-deploy fleet compatibility golden-file tests #repo/RIPDPI #area/testing #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-ripdpi-vpn-deploy-fleet-compatibility-golden-file-tests`
- **Verify:** `./gradlew :core:data:runtime-state:testDebugUnitTest --tests "*FleetCompat*"`
- **Scope (only modify these + this file + the ledger):** `core/data/runtime-state/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective

Lock the client↔deployer interface with a **golden-file regression
suite**: import every literal output variant of
`ripdpi-vpn-deploy/scripts/emit-singbox.sh` (plus QR, recipient HTML,
and `.conf` AWG cohorts) and assert byte-stable
`parse → save → re-export`. A deployer schema change must make this
suite go **red** — never be silently absorbed by a lenient parser.

This is the epic-level integration gate. It is the **last** child
task to reach `#status/done`: it depends on every other child task's
parser/model work existing.

## Context

### Why a dedicated suite

The wider client-compat matrix
([[Add client compatibility regression matrix for fleet profiles]])
covers cross-client compatibility (SFA, v2rayNG, NekoBox, husi, …).
This task is **narrower and stricter**: the exact bytes the sibling
deployer emits for every supported
`PROVIDER × ENV × COHORT × CLIENT × FLAGS` combination must import
correctly today and stay correct after any client-side parser change.

### Fixture sourcing

Mirror the deployer's fixtures under
`core/data/runtime-state/src/test/resources/fleet-fixtures/<scenario>/`
so the suite is self-contained. A tooling script
`scripts/refresh-fleet-fixtures.sh` regenerates them by running
`emit-singbox.sh` against a **frozen secret-set** (all UUIDs /
shortIds / keys / passwords are fixed test values like
`00000000-0000-0000-0000-000000000001`, never production tokens) at
a **pinned deployer git SHA**. Bumping that pin is the deliberate
operator action that signals a deployer contract change and triggers
review.

## TDD workflow

This task **is** test infrastructure, so "test-first" means: the
fixtures and the harness assertions are authored and made to fail
against today's incomplete parsers, then the sibling parser tasks
turn them green.

1. **Red** — create all fixture directories and `expected-*.json`
   files first, wire the harness, and run it against the current
   `main`. Every scenario that exercises an unimplemented parser
   path **must fail** with a readable structural diff. Record the
   failing scenario list in the Work log — that list **is** the
   epic's remaining-work ledger.
2. **Confirm failures are correct** — each failure must be a
   genuine "parser does not yet handle X" diff, not a fixture typo
   or harness bug. A fixture that fails to load is not a valid red.
3. **Green (incremental)** — as each sibling child task lands, its
   scenarios flip green. This task is `#status/done` only when the
   **whole** suite is green.
4. **Refactor** — once green, deduplicate fixture-loading
   boilerplate; keep the structural-diff output readable.
5. **Verify** — intentionally regress a parser and confirm the
   suite fails with a readable diff (see `## Completion criteria`).

## Acceptance criteria

- [ ] Fixture root
    `core/data/runtime-state/src/test/resources/fleet-fixtures/`
    with at minimum these scenarios:
    - `p0-only/` — REALITY + Vision, single cohort
    - `p1-only/` — VLESS + xHTTP, plain TLS
    - `p2a-hysteria-only/` — Hysteria2 with Salamander on
    - `p2a-hysteria-port-hop/` — Hysteria2 with port range +
      hop interval
    - `p2b-amneziawg-rtk-south/` — AmneziaWG, RTK South cohort
    - `p2b-amneziawg-default/` — AmneziaWG, default cohort
    - `multi-cohort-p0-p1-p2a/` — three transports, selector +
      urltest
    - `multi-host-failover/` — `HOSTS="upcloud:prod,hetzner:prod"`,
      two hosts × P0 + P2a, selector + urltest
    - `per-app-bypass-and-via-tun/` — both `--per-app-bypass` and
      `--per-app-via-tun` set
    - `bootstrap-bundle/` — same content served from
      `/bootstrap/<token>`
- [ ] Each scenario directory contains: `bundle.json` (literal
    `emit-singbox.sh` output), `expected-profiles.json`,
    `expected-group.json` (if any), `expected-routing.json` (if
    any), `meta.json` (deployer git SHA + generation timestamp).
- [ ] `scripts/refresh-fleet-fixtures.sh` regenerates every fixture
    against the sibling repo at a pinned SHA (the pin is a single
    line in the script); the frozen secret-set is documented inline
    so the deployer side matches.
- [ ] Harness: iterates every scenario, imports `bundle.json`
    through the **production** import pipeline (parser → entity
    merge → store), diffs the in-memory model against
    `expected-*.json`, and fails with a structural (jq-style) diff
    on mismatch.
- [ ] Round-trip: the harness re-exports the imported model back to
    sing-box JSON and diffs against `bundle.json` modulo
    documented-allowed deltas (e.g. outbound ordering, idempotent
    `default:"auto"`); the allowed-delta list is explicit in the
    harness, not a fuzzy match.
- [ ] CI gate: the suite runs on every PR touching the subscription
    parser, relay model, routing model, or AWG model; a red suite
    blocks merge.
- [ ] On a deployer SHA bump, CI runs the suite at the new SHA and
    surfaces the fixture diff in the PR — the operator's signal that
    a client-side change is needed.
- [ ] No secrets in any fixture: all credentials are frozen test
    values; a guard test greps every `bundle.json` for
    production-token shapes and fails if any is found.
- [ ] `bootstrap-bundle/` exercises the one-shot consumption flow
    against a faked HTTP backend.
- [ ] The suite includes the cross-repo diff asserting the client
    AWG cohort catalog matches `ripdpi-vpn-deploy/docs/AWG-COHORTS.md`
    (shared with [[Add AmneziaWG Russian ISP cohort preset catalog]]).

## Test plan

| Layer | Element | Assertion |
|---|---|---|
| Golden-file | each scenario `bundle.json` | imports to the expected profiles / group / routing |
| Golden-file | round-trip | re-export ≈ input modulo documented deltas |
| Golden-file | `bootstrap-bundle/` | one-shot consume against faked HTTP backend |
| Guard test | every `bundle.json` | no production-token shapes present |
| Cross-repo | AWG catalog | client values == `docs/AWG-COHORTS.md` |
| CI | regression check | a deliberately broken parser makes the suite red with a readable diff |

## Completion criteria

`#status/done` only when **every** item holds, with evidence in the
`## Work log`:

- [ ] All `## Acceptance criteria` checkboxes checked.
- [ ] All fixture directories + `expected-*.json` exist and were
    authored **before** the sibling parser work (the initial
    red-run scenario list is in the Work log).
- [ ] The **entire** suite is green:
    `./gradlew :core:data:runtime-state:testDebugUnitTest --tests "*FleetCompat*"`
    — output attached.
- [ ] `scripts/refresh-fleet-fixtures.sh` runs clean against the
    pinned deployer SHA and reproduces byte-identical fixtures —
    output attached.
- [ ] The CI workflow change that gates merges on this suite is
    committed and shown passing on this task's own PR.
- [ ] **Regression proof**, recorded in the Work log: a parser is
    deliberately broken, the suite fails with a readable structural
    diff, the break is reverted, the suite goes green again.
- [ ] The no-secrets guard test is green.
- [ ] The cross-repo AWG catalog diff is green.
- [ ] Reviewed by a separate `verifier` / `code-reviewer` pass.
- [ ] `## Work log` added: changed files, pinned deployer SHA,
    test output, residual risk (fixture staleness cadence).

## Source references

- Deployer bundle emitter:
  `ripdpi-vpn-deploy/scripts/emit-singbox.sh` (405 lines)
- Deployer QR emitter:
  `ripdpi-vpn-deploy/scripts/emit-qr.sh`
- Deployer recipient renderer:
  `ripdpi-vpn-deploy/vpnd/src/pages/recipient.rs`
- Deployer AWG cohort vars:
  `ripdpi-vpn-deploy/ansible/roles/amneziawg/vars/cohorts/`
- Deployer-side fixtures (mirror source):
  `ripdpi-vpn-deploy/vpnd/tests/*.rs`,
  `ripdpi-vpn-deploy/contract-fixtures/`
- Wider matrix:
  [[Add client compatibility regression matrix for fleet profiles]]

## Links

- [[Epic - ripdpi-vpn-deploy fleet compatibility]]
- [[Add client compatibility regression matrix for fleet profiles]]
- [[Add sing-box JSON subscription parser]]
- [[Add sing-box selector and urltest group import from subscription]]
- [[Add sing-box route.rules Android per-app routing import]]
- [[Add AmneziaWG Russian ISP cohort preset catalog]]
- [[Add bootstrap one-time subscription token import flow]]
- [[Decouple VLESS xHTTP transport from the Reality relay kind]]
- Sibling repo: `/Users/npochaev/GitHub/ripdpi-vpn-deploy/`
