---
task_id: RLY-1794335291032951
change: fix-xray-audit-session-isolation
commit_sha: null
local: required
local_evidence: Pending owning Kotlin, Rust, Go tests and offline CI gates.
remote_ci: not_applicable
remote_ci_evidence: No manual remote workflow dispatch is needed; push checks are recorded separately.
device: not_applicable
device_evidence: This change uses host regression and real Go/Rust loopback tests for the private hop; Android platform APIs are unchanged and device execution is not claimed.
artifact: required
artifact_evidence: Pending rebuilt pinned patched AAR and verifier receipt.
deployment: not_applicable
deployment_evidence: No deployment or release is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-XRAY-UDP-SESSION | RLY-1794335407480209 | Actual pinned Go RED for CMD3 bypass and stop with live control; full Go suite PASS; race count=5 PASS; final 14-row managed RUF1 loopback matrix gives 40 exact echoes and two explicit expected UDP443 rejections; independent re-review clean | PASS |
| REQ-UDP-LOCAL-SESSION | RLY-1794335407480209 | Rust RED then 96 passed, zero failed, one pre-existing ignored timing test; clippy all-targets -D warnings and fmt PASS; actual final Rust helper to final Go listener gives two independent 25-byte exact echoes | PASS |
| REQ-XRAY-STUB-CLEANUP | RLY-1794335408051246 | Actual stub RED before fix; Gradle actual-stub regression + 18 RipDpiXrayRuntime tests GREEN; engine ktlint/detekt PASS; independent review no findings | PASS |

## Local evidence

- Stub RED/GREEN: actual XrayNativeBridgeStubTest and 18 RipDpiXrayRuntimeTest cases; engine ktlint/detekt pass. Linked cleanup ownership guards are unchanged.
- Full Android source gate: `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true -Pripdpi.requireLibXray=false` passed in 10m33s (826 actionable tasks).
- Rust: `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-transport --locked`; 96 passed, zero failed, one pre-existing ignored route-bucket timing test. `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-transport --all-targets --locked -- -D warnings` passed through the owning cargo-guarded wrapper.
- Pinned Go host source: full `scripts/native/test-libxray-protection.py` passes; `go test -race -mod=readonly -count=5 -timeout=90s -run TestManagedUDP -v ./xray` passes. Regressions include raw/CMD3 rejection, wrong nonce/source/replay, control EOF, stop with idle control and pending handshake, reply pins, authority limits, empty local datagrams and 8192-byte boundary behavior.
- Final Rust-to-Go loopback: `/tmp/rly18-interop-final.log`; two new authenticated associations each returned the exact 25-byte payload. This uses the actual production diagnostic helper and patched Go binary, without fake bridge or copied request algorithm.
- Final TLS/REALITY x TCP/xHTTP matrix: `/tmp/rly18-final-matrix-path` points to full temporary logs/configs. All 14 rows pass TCP and UDP53; 12 pass UDP443 and two normal Vision rows explicitly reject it. All 14 wrong-password attempts fail RFC1929 authentication. TLS uses a temporary CA verification overlay, not allowInsecure. No external network or device acceptance is claimed.
- Architecture check passed with zero new/worsened/stale indicators; locked cargo metadata resolves 113 workspace members. Final `just ci-gates` and rebuilt full-ABI AAR receipt remain pending below.
- Independent review found two issues in the first proposed fix (runtime-owned UDP teardown and wrong-target diagnostic errors). Both were reproduced, corrected and re-reviewed without remaining findings.
