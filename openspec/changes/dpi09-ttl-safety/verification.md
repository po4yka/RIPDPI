---
task_id: ENG-1794557091175687
change: dpi09-ttl-safety
commit_sha: null
local: required
local_evidence: Pending implementation, regression tests, review and combined gates.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is required as local implementation proof.
device: not_applicable
device_evidence: No device, external server or root activation is part of this repair.
artifact: not_applicable
artifact_evidence: No APK or release artifact is published by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-OOB-TTL-RESTORE | ENG-1794557266916478 | Locked lowering tests, accepted progress and restored socket read-back | pending |
| REQ-OOB-TTL-FAMILY | ENG-1794557268725615 | Locked IPv4/IPv6 active-family socket tests | pending |
| REQ-OOB-STAGE-DEADLINE | ENG-1794557269707410 | Linux blocked-peer send-stage regression in both wait modes | pending |
| REQ-OOB-WIRE-EVIDENCE | ENG-1794557270566971 | Sequence-aware oracle tests and strict local packet-smoke echo | pending |
| REQ-OOB-PLATFORM-SCOPE | ENG-1794557271420486 | Independent review and docs commit b67136437d; normal hooks passed | passed |
| REQ-OOB-TTL-RESTORE | ENG-1794557272429801 | Combined locked tests, Clippy, source gates and independent review | pending |

## Current-main closure map

The DPI-09 audit used main at 7cdc4bd49de465a85058236132ecba3597b76638.
This repair started at 2d74ada2c26d38423439c3e00bd612f312cf3f84.
Static rechecks found these existing ancestors; current local tests will verify
the shared contracts again before integration.

| Audit item | Existing main repair | Current source evidence |
|---|---|---|
| F1 partial OOB progress | 8c48353d1e, 326e396ea6 | Progress-tracked original prefix, one urgent byte, logical counts |
| F2 multiple urgent marks | b3a51e8971 | One urgent mark in the initial original payload; later steps become Split |
| F3 ignored repeat and skip | 197df0b548, c9d964d440 | CLI, JSON, Kotlin and planner reject unsupported urgent offsets |
| H3 generic urgent projection | 398e05e47c | Generic plan retains typed WriteUrgent |

## Remaining repair commits

| Audit item | Requirement | Commit | Result |
|---|---|---|---|
| H1 failed TTL restoration | REQ-OOB-TTL-RESTORE | pending | pending |
| H2 active TTL address family | REQ-OOB-TTL-FAMILY | pending | pending |
| H4 send-stage deadline | REQ-OOB-STAGE-DEADLINE | pending | pending |
| F4 packet-smoke oracle | REQ-OOB-WIRE-EVIDENCE | pending | pending |
| F5 platform documentation | REQ-OOB-PLATFORM-SCOPE | b67136437d | passed |

## Initial local checks

At native source base 2d74ada2c26d38423439c3e00bd612f312cf3f84, the locked
library tests for ripdpi-config, ripdpi-desync and ripdpi-proxy-config passed:
189, 180 and 164 tests (533 total). The target was isolated at
/private/tmp/dpi09-integration-target and compiler work used the ripdpi build
lane with four jobs. The final combined tree must rerun affected gates after
upstream changes or the remaining repairs change their inputs.
