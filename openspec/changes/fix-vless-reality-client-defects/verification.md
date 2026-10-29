---
task_id: TRN-1793295071036802
change: fix-vless-reality-client-defects
commit_sha: 480f7a391c071ad2e5c20b671d251de69ac30a01
local: passed
local_evidence: All six fixes have regression coverage. Combined Rust tests passed 417 tests, with one existing Mieru peer test ignored. Runtime-state passed 367 tests. Full staticAnalysis, affected Kotlin lint and detekt, locked Cargo metadata, architecture checks, commit hooks, and independent code and async reviews passed.
remote_ci: required
remote_ci_evidence: All six implementation commits were pushed to origin/main. CI for 480f7a391 is pending at https://github.com/po4yka/RIPDPI/actions/runs/40927299912. Hosted CI and CodeQL acceptance are not claimed.
device: not_applicable
device_evidence: This work fixes parser and stream contracts with local deterministic tests; Android acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No release artifact is owned by these fixes.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IMPORT-INTENT | TRN-1793295233831788 | 6263e2526; URI and JSON tests reject unknown, duplicate, malformed, and unsupported options. Existing supported import and export tests pass. | passed |
| REQ-XUDP-FLUSH | TRN-1793295235248858 | 9fbd4e8d6; concrete buffered carrier tests prove that one send is visible and a blocked flush meets the deadline and closes the carrier. Both tests failed before the fix. | passed |
| REQ-TRANSPORT-ALIAS | TRN-1793295236485819 | 480f7a391; tests cover method-only input, method precedence, invalid or blank methods, and rejection details without imported values. The old parser failed three regressions. | passed |
| REQ-XHTTP-AUTO | TRN-1793295237937226 | 5e7c458cd; tests resolve REALITY auto to stream-one, reject TLS auto because packet-up is unsupported, and preserve explicit modes and Cloudflare stream-up behavior. | passed |
| REQ-VISION-BOUNDARY | TRN-1793295239492208 | 719177e74; six concrete duplex cases cover fragmented and coalesced TLS records. Direct starts only after a complete record. A regression failed before the fix. | passed |
| REQ-REALITY-VERSION | TRN-1793295241880426 | c28220a76; server-side ECDH, HKDF, and GCM decoding checks version bounds, timestamp, and short ID. The old version failed admission. The Go reference reproduces the updated sealed vector. | passed |

## Observed local validation

- `bash ../../scripts/ci/cargo-guarded.sh cargo test --locked -p ripdpi-vless -p ripdpi-xhttp -p ripdpi-relay-core --lib`, from `native/rust`: 140 VLESS, 69 XHTTP, and 208 relay-core tests passed. One existing relay-core test requires an independent Mieru peer and remains ignored. No new test was skipped.
- `./gradlew :core:data:runtime-state:testDebugUnitTest staticAnalysis -Pripdpi.skipNativeBuild=true`: passed. JUnit reports contain 367 tests, zero failures, zero errors, and zero skips.
- `./gradlew :core:data:runtime-state:testDebugUnitTest :core:data:runtime-state:ktlintCheck :core:data:runtime-state:detekt -Pripdpi.skipNativeBuild=true`: passed after the final test fixture variable rename.
- `cargo metadata --locked --format-version 1`, through the Cargo guard: passed for 113 workspace members.
- Architecture health and native architecture contracts passed without baseline changes. Commit hooks ran for every fix, including staged Rust Clippy and formatting where applicable.
- Independent general code review and async cancel-safety review found no remaining actionable defect in the final fixes.

The first combined Rust run exposed an existing Tor deadline failure under parallel load and an XHTTP fixture that only serves stream-up. The unchanged Tor test passed on a targeted check. The fixture now requests explicit stream-up. The final unfiltered combined run passed all applicable tests.

The REALITY compatibility version is an admission value for the reviewed v26.10.29 handshake. It does not claim full upstream feature parity. No independent live Xray handshake, Android device acceptance, or release artifact validation was run.
