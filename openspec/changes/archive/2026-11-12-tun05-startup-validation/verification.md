---
task_id: TRN-1794477195384429
change: tun05-startup-validation
commit_sha: 38b8f4bac65f29e14e6031633ce8611c20101b2f
local: passed
local_evidence: RuntimeReadinessTest 8 passed; AwgActivationRequestTest 18 passed; AmneziaWgEditorStateTest 37 passed. Logs /tmp/tun05-ready-tests-result.log and /tmp/tun05-validation-tests.log. Combined gates and exact-SHA record are parent-owned.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is required for this local fix.
device: not_applicable
device_evidence: Deterministic validation and readiness tests own this change; no device acceptance claim.
artifact: not_applicable
artifact_evidence: No native artifact changes.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-DEADLINE | TRN-1794477308920426 | RuntimeReadinessTest staged setup; 8 tests passed | passed |
| REQ-AWG-URL | TRN-1794477312126131 | AwgActivationRequestTest18 and AmneziaWgEditorStateTest37 passed | passed |

| REQ-AWG-RELOAD | TRN-1794478981961269 | BaseServiceRuntimeCoordinatorTest: red29 tests/1 failure at45s; green29 passed after scoped60s fix. Logs /tmp/tun05-reload-red.log, /tmp/tun05-reload-green2.log | passed |

## Rebased combined verification

Source commit 38b8f4bac65f29e14e6031633ce8611c20101b2f. Application sources match the tested revision 411ce08e731a7fec44808a87c9d6f1857ede8daf byte for byte; only task/OpenSpec records differ. `just ci-gates` passed (/tmp/tun05-rebased-ci-gates.log). Full staticAnalysis and 2288 Kotlin tests passed (/tmp/tun05-rebased-kotlin-gates.log): service2214, request18, editor37, readiness8, serialization11. Native core197 and carrier37 passed; affected-crate clippy passed without warnings. General and async reviews resolved all findings. No device, external-server, packaged-artifact or deployment acceptance is claimed.
