---
task_id: TRN-1794371393565180
change: fix-xudp-control-and-target-admission
commit_sha: 2d256d7c6f9fde6264a6376776e55e06cd44c08b
local: required
local_evidence: Rebased code passes 164 unit tests, all-target Clippy, locked Cargo metadata, and both pinned-peer tests. The repeated source gate on the new base is pending.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not an acceptance gate for this local codec repair; local source gates and peer tests are required. No hosted result is claimed.
device: not_applicable
device_evidence: No Android, JNI, socket routing, or device lifecycle code changes.
artifact: not_applicable
artifact_evidence: No release or packaged artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-XUDP-CTRL-01 | TRN-1794371467864574 | /tmp/trn03-control-red-clean.log: 2 expected failures; /tmp/trn03-control-green.log: 160 lib tests and all-target clippy pass; independent code and async review clear | Passed |
| REQ-XUDP-TARGET-01 | TRN-1794371468380869 | Targeted target_validation_tests: 4 passed; combined crate: 164 passed; REALITY Vision peer and REALITY xHTTP peer in both modes passed; independent review clear. | Passed |

## Observed evidence

- F1 regressions failed before the repair: UnsupportedOption(0) for a valid control and InvalidData instead of EOF for End. The final tests cover fragmented controls, receive cancellation, arbitrary KeepAlive IDs, D=0, D=1, and terminal End.
- F2 reproduction at the unchanged audit base showed successful Rust encoding of bad!.example and rejection by the independent pinned Xray parser. Four permanent target regressions pass. They cover NEW/KEEP and initial-source preservation, domain length and alphabet, malformed downlink domains, and five IP literal forms.
- Final crate gate: cargo test --locked -p ripdpi-vless --jobs 2 passed 164 unit tests and zero doctests. Eight pre-existing fixture/live integration tests remain ignored in the default run; the relevant upstream case was separately executed below.
- Final lint: cargo clippy --locked -p ripdpi-vless --all-targets --jobs 2 -- -D warnings passed. Formatting passed.
- Pinned upstream Xray 0afec66085a85191e9dd2a4a6646c7c9a94688d1: xudp_two_targets_exchange_with_upstream passed with invalid targets before NEW and between accepted datagrams. The production relay-core vless_reality_xhttp_xudp_with_upstream test passed for stream-up and stream-one. Each selected invocation executed one test with zero failures and zero ignored tests.
- Cargo metadata --locked --format-version 1 passed on the recorded code revision. Full log: /tmp/trn03-combined-validation.log; runner: /tmp/trn03-fixed-interop.py.
- Independent pr-reviewer and async-cancel-safety reviews found no unresolved findings. The IP literal compatibility regression found during review was corrected and re-reviewed before the F2 commit.
- The earlier just ci-gates run passed. The same complete gate is running again after rebasing onto c3ebd14b10f6778d7946bd2425cb501dec037a64. No updated-base pass is claimed yet.
- No device, external server, real profile, root activation, release artifact, or production deployment was used. Evidence establishes host codec and concrete loopback-peer behavior.
