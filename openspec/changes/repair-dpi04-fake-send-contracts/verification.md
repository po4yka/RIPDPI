---
task_id: ENG-1794501890187395
change: repair-dpi04-fake-send-contracts
commit_sha: null
local: required
local_evidence: Pending owning regressions, affected Rust checks, and offline CI gates.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not an acceptance gate owned by this local repair; push status will be reported separately.
device: not_applicable
device_evidence: No physical, emulator, or root activation is authorized; Android wire acceptance remains unverified.
artifact: required
artifact_evidence: Pending reviewed per-finding commits and the combined validation record.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-FAKE-ACTIVATION | ENG-1794502313284304 | Owning loopback activation regression | Pending |
| REQ-FAKE-CLEANUP | ENG-1794502315444353 | Queued page cleanup regression and Linux-target check | Pending |
| REQ-FAKE-REPLAY | ENG-1794502317963326 | Send phase and runtime replay outcome regressions | Pending |
| REQ-FAKE-GENUINE | ENG-1794502321010120 | Independent serialized packet checks | Pending |
| REQ-FAKE-INPUT | ENG-1794502324054173 | Unicode and valid escape parser regressions | Pending |
| REQ-FAKE-SEQUENCE | ENG-1794502326974327 | Modulo arithmetic and packet boundary regressions | Pending |
| REQ-FAKE-HELPER | ENG-1794502329017041 | Bounded frame, payload, and legacy helper regressions | Pending |
| REQ-FAKE-IPID | ENG-1794502330712741 | Pre-emission IPv4 rejection and IPv6 compatibility regressions | Pending |

## Combined gates

Execution step ENG-1794502332688030 owns independent review, affected crate
tests and clippy, locked metadata, architecture health, and `just ci-gates`.
Source and local-runtime proof do not establish Android or external-network
compatibility. The final record must name every unexecuted platform path.
