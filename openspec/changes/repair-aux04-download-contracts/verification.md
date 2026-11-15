---
task_id: SVC-1794755640074922
change: repair-aux04-download-contracts
commit_sha: null
local: required
local_evidence: Owning Kotlin and Rust regressions, JNI checks, source gates, static analysis, and independent review remain pending.
remote_ci: required
remote_ci_evidence: The published exact commit must run hosted CI. No result exists yet.
device: not_applicable
device_evidence: Local loopback and host tests are in scope. Physical-device and external-network acceptance is not authorized by this repair.
artifact: not_applicable
artifact_evidence: No release binary or golden fixture is produced.
deployment: not_applicable
deployment_evidence: Git integration and publication only. No service deployment.
---

# Verification

Portfolio: SVC-1794755640074922. Source audit: /Users/npochaev/Downloads/RIPDPI_network_audits/AUX-04.md.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AUX04-F1 | SVC-1794755720003749 | Real response reads for known and unknown length success and error bodies; service regressions pending. | pending |
| REQ-AUX04-F2 | SVC-1794755720588786 | Real TLS close-delimited, Content-Length, and chunked closure controls pending. | pending |
| REQ-AUX04-F3 | SVC-1794755721277092 | Pre-cancel, stalled socket cancellation, release races, and independent request checks pending. | pending |
| REQ-AUX04-F4 | SVC-1794755722049880 | Native Content-Type and browser charset/binary regressions pending. | pending |

## Combined gates

SVC-1794755722719619 owns cargo test --locked for the fetch adapter, owning Kotlin tests, JNI/API contract checks, just ci-gates, staticAnalysis, architecture health, locked metadata, independent review, and separate finding commits integrated into main and pushed to origin.

C, V, S, and E remain separate. Local loopback evidence does not prove physical-device or external interoperability. Hypotheses from the audit require confirmation before code changes.
