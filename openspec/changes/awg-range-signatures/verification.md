---
task_id: TRN-1792776081374051
change: awg-range-signatures
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: Physical ARM64 acceptance is pending. A connected device and a local peer are available; the separate acceptance owner runs that lane without external nodes.
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: This client change does not deploy infrastructure.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-RANGE | TRN-1792776231660643 | Native tests and pinned upstream interoperability | pending |
| REQ-AWG-SIGNATURE | TRN-1792776231660643 | Signature parser and generated packet tests | pending |
| REQ-AWG-CONTRACT | TRN-1792776232432318 | Kotlin tests and coordinated schema v2 goldens | pending |
| REQ-AWG-SAFETY | TRN-1792776233107642 | Native and Android source gates; physical acceptance remains separate | pending |

## Intermediate integration checkpoint

This source revision supplies the Android acceptance build dependency. The task
remains in progress. It is not a completed feature or a release candidate.

Observed local checks:

- Native AWG core: 107 tests passed, including scanner packet order and typed errors.
- Native proxy configuration: 145 unit tests and 5 round-trip tests passed.
- Native Android bridges: 6 AWG tests and 11 WARP tests passed.
- The Go peer helper tests passed.
- Independent AWG 3.1 interoperability passed at MTU 1280 and 1420, with four
  sessions each. IPv4/IPv6 TCP and UDP, fresh signature order, all five
  signature tags, and varied H4 values in both directions were observed.
- AWG evidence Python tests: 14 passed.
- Fleet fixture Python tests: 28 passed.
- The actual pinned producer regenerated seven fleet bundles; the read-only
  regeneration comparison passed. The standalone port-hop reference remains a
  parser reference, not producer evidence.
- Architecture health: 21 existing indicators, zero new or worsened indicators.
- Kotlin formatter: passed for the changed Kotlin files.
- Task contracts: 149 tasks and 462 steps validated.

The first Kotlin test run found a missing mandatory native wire marker and
missing imports in the new fleet test. The source fixes are present, but the
full rerun also found old positive test inputs and a disabled-default preset
inference error. Those source fixes are present; the full rerun is pending.
The first interoperability attempt stopped at a Cargo
build directory lock before any wire test. The runner now executes the
compiled test directly after it starts the peer; the complete matrix then
passed. The complete
Android compile/test gate, hosted CI, and physical acceptance remain pending
for this exact revision. Build queues do not count as passing evidence.

Native create and endpoint probe now use the existing contract encoder so
required default fields survive the Kotlin-to-Rust boundary. Regression tests
inspect the actual create payload, including default MTU, manual endpoint,
header strings, zero padding, and the wire profile. The scanner also sends
signature slots and junk before initiation, with typed errors for invalid
profiles and a real UDP ordering regression.
