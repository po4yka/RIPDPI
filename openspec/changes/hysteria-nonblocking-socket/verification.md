---
task_id: RST-1792838334204120
change: hysteria-nonblocking-socket
commit_sha: 47a5ee708a497086eb29739ca17f35e164119846
local: passed
local_evidence: Locked complete Hysteria crate tests passed 108 unit tests and 2 loopback tests; three existing external-peer tests require separate peer execution. All-targets Clippy with warnings denied, formatting, strict OpenSpec, and task validation passed.
remote_ci: required
remote_ci_evidence: Final published candidate hosted gates remain required. Earlier source 38e64afcf9f097a60c8dd9146ed431c108fe35ef passed 56 checks with 19 planned skips; its Linux io_uring job 120911596401 passed 254 tests with no skips, positive WRITE_FIXED completions and zero fallback.
device: passed
device_evidence: All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. Hysteria completed real encrypted UDP startup, 1 MiB download/upload, a 60-second sleep/wake stream, automatic TCP reserve DNS/download and explicit UDP recovery.
artifact: passed
artifact_evidence: Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HY-SOCKET-ASYNC | RST-1792838410157683 | Both real datagram regressions passed in the complete 108-test unit suite; independent source review approved | Passed locally |
| REQ-HY-SOCKET-ENDPOINT | RST-1792838411619909 | Initial endpoint and rebind regression passed; complete locked crate tests, Clippy, and formatting passed | Passed locally |
| REQ-HY-SOCKET-DEVICE | RST-1792838413137335 | Integrated revision, hosted checks, rebuilt Android artifact, and physical Hysteria payload and UDP run | Pending |

## Pre-fix evidence

The device owner observed the Hysteria profile reach Running at `40d76fe9f66888ce5cf0bf318bfad0d8f5a7b812`. UDP DNS then failed. The native panic points to `salamander.rs` socket registration and states that a blocking socket was passed to Tokio. This physical failure does not replace the required local regression and post-fix device acceptance.

## Local regression before the fix

At `40d76fe9f66888ce5cf0bf318bfad0d8f5a7b812`, the new blocking-input regression executed before the production change. The locked Cargo command exited 101: zero passed and one failed. Tokio rejected the blocking socket at `salamander.rs:24`, which matches the physical failure. The one-line constructor fix enables nonblocking mode before registration. Independent source review approved that change and the real datagram and rebind regressions. The complete integrated crate gate remains required.

## Integrated local result

The change was applied on integrated base `5bcbea7a71f96c00be801af3cfdbea4a2ec62139`. The exact serial gate completed with exit code 0:

```text
build-gate -- bash -c 'cargo test --locked -p ripdpi-hysteria2 --jobs 4 && ../../scripts/ci/cargo-guarded.sh cargo clippy --locked -p ripdpi-hysteria2 --all-targets --jobs 4 -- -D warnings'
```

The test command ran the full crate without a filter: 108 unit tests and two loopback integration tests passed, with zero failures. The three existing independent-peer tests remain opt-in and were not executed in this local gate. All three new socket and endpoint regressions passed. Clippy completed with warnings denied. Formatting, strict change validation, and task validation passed. Independent source review approved the one-line admission fix and real datagram/rebind coverage. The final native source uses the current port-hopping endpoint signatures.

The integrated hosted checks, rebuilt Android artifact, and physical Hysteria transfer remain required. The portfolio task remains doing; the local result does not close its physical acceptance step.

## Final physical candidate 47a5ee70

All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. Hysteria completed real encrypted UDP startup, 1 MiB download/upload, a 60-second sleep/wake stream, automatic TCP reserve DNS/download and explicit UDP recovery.

Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
