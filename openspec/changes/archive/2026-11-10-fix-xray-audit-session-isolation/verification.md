---
task_id: RLY-1794335291032951
change: fix-xray-audit-session-isolation
commit_sha: 14026caa85f63e0a18069e45dbe5b117b64c10a8
local: passed
local_evidence: Owning Kotlin, Rust and pinned Go regressions, race checks, independent loopback interop, staticAnalysis, just ci-gates and independent re-review passed.
remote_ci: not_applicable
remote_ci_evidence: No manual remote workflow dispatch is needed; push checks are recorded separately.
device: not_applicable
device_evidence: This change uses host regression and real Go/Rust loopback tests for the private hop; Android platform APIs are unchanged and device execution is not claimed.
artifact: passed
artifact_evidence: Full four-ABI build and --release verifier passed; content-bound receipt is artifacts/libxray-artifact.json; AAR SHA256 a2011027f9bfae605f443dc56b3ae4959b3cbc681946d059a16a008761491df2.
deployment: not_applicable
deployment_evidence: No deployment or release is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-XRAY-UDP-SESSION | RLY-1794335407480209 | Actual pinned Go RED for CMD3 bypass and stop with live control; full Go suite PASS; race count=5 PASS; final 14-row managed RUF1 loopback matrix gives 40 exact echoes and two explicit expected UDP443 rejections; independent re-review clean | passed |
| REQ-UDP-LOCAL-SESSION | RLY-1794335407480209 | Rust RED then 96 passed, zero failed, one pre-existing ignored timing test; clippy all-targets -D warnings and fmt PASS; actual final Rust helper to final Go listener gives two independent 25-byte exact echoes | passed |
| REQ-XRAY-STUB-CLEANUP | RLY-1794335408051246 | Actual stub RED before fix; Gradle actual-stub regression + 18 RipDpiXrayRuntime tests GREEN; engine ktlint/detekt PASS; independent review no findings | passed |

## Local evidence

- Stub RED/GREEN: actual XrayNativeBridgeStubTest and 18 RipDpiXrayRuntimeTest cases; engine ktlint/detekt pass. Linked cleanup ownership guards are unchanged.
- Full Android source gate: `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true -Pripdpi.requireLibXray=false` passed in 10m33s (826 actionable tasks).
- Rust: `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-transport --locked`; 96 passed, zero failed, one pre-existing ignored route-bucket timing test. `cargo clippy --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-transport --all-targets --locked -- -D warnings` passed through the owning cargo-guarded wrapper.
- Pinned Go host source: full `scripts/native/test-libxray-protection.py` passes; `go test -race -mod=readonly -count=5 -timeout=90s -run TestManagedUDP -v ./xray` passes. Regressions include raw/CMD3 rejection, wrong nonce/source/replay, control EOF, stop with idle control and pending handshake, reply pins, authority limits, empty local datagrams and 8192-byte boundary behavior.
- Final Rust-to-Go loopback: `/tmp/rly18-interop-final.log`; two new authenticated associations each returned the exact 25-byte payload. This uses the actual production diagnostic helper and patched Go binary, without fake bridge or copied request algorithm.
- Final TLS/REALITY x TCP/xHTTP matrix: `/tmp/rly18-final-matrix-path` points to full temporary logs/configs. All 14 rows pass TCP and UDP53; 12 pass UDP443 and two normal Vision rows explicitly reject it. All 14 wrong-password attempts fail RFC1929 authentication. TLS uses a temporary CA verification overlay, not allowInsecure. No external network or device acceptance is claimed.
- Architecture check passed with zero new/worsened/stale indicators; locked cargo metadata resolves 113 workspace members. Final `just ci-gates` passed, including source contracts, locale exports and Rust API snapshots. The Linux-owned runtime-platform snapshot is skipped by the owning recipe on Darwin. Full-ABI AAR evidence follows.
- Independent review found two issues in the first proposed fix (runtime-owned UDP teardown and wrong-target diagnostic errors). Both were reproduced, corrected and re-reviewed without remaining findings.

## Artifact evidence

The repository build recipe produced an AAR for armeabi-v7a, arm64-v8a, x86 and x86_64, then the explicit `scripts/native/verify-libxray-artifacts.sh --release` command passed. The unmodified generated schema-2 receipt is saved in `artifacts/libxray-artifact.json`. The build used pinned Go 1.27.3, gomobile and Android NDK; no signing, release upload or physical-device action was performed.

- Patch SHA256: `b82b9dbcc2c07c4c98a4ad0e22a1b862bce1c5bc39c34092b4f9bb742e018e1a`.
- AAR SHA256: `a2011027f9bfae605f443dc56b3ae4959b3cbc681946d059a16a008761491df2`.
- Patch-manifest SHA256: `b50674e901098875de215124e275d12c4ccb57815e2e8d6c9ddd5b8172d07b61`.
- Local artifact directory: `/var/folders/z7/0w5jl61d0qq811sptzvn99n5ww3hp6/T/rly18-verified-aar-ehtruxs1`.
- Build log: `/tmp/rly18-verified-aar-build.log`.

An earlier AAR attempt failed with `No space left on device`; a concurrent pre-commit clippy hook also failed. After removing only the task-owned disposable Go test cache, the same hooks passed without source changes and the complete four-ABI build passed. An earlier source snapshot build was deliberately stopped when review required the lifecycle correction.

This receipt proves the artifact built for this change. It does not attest historical APK payloads, external server compatibility or Android JNI/VPN execution. Linked bridge unit checks compile against this actual AAR but do not load Android gojni on the host.

Linked AAR check: `:core:engine:testDebugUnitTest --tests com.poyka.ripdpi.core.XrayNativeBridgeLibXrayImplTest` with the actual verified prebuilt AAR passed 16 tests, zero failures/errors/skips, in a successful Gradle build. Host unit tests do not execute Android JNI.

## Published history after rebase

The source changes were rebased onto `49543da310d16fcd38dcddd18cae46f650739f6d` before integration because parallel relay/service fixes reached main. The immutable archive receipt retains the original tested source snapshot `14026caa85f63e0a18069e45dbe5b117b64c10a8`. Its published equivalent is `69b186cc290dba133f10c9e21bf2adeda0ddcdf5` (UDP fix); the stub fix is `dc16daadb8f3ca2051570526c2d10642bdc7929d`.

A scoped Git diff between original and rebased snapshots is empty for all changed engine, Xray patch, diagnostic client and Go test paths. The pinned source/patch digest and AAR content receipt therefore remain unchanged. The rebased Kotlin service tests and full static analysis with the actual AAR, plus offline CI gates, are rerun for the changed upstream service inputs. Final results are recorded below.

Rebased results: `:core:service:testDebugUnitTest --tests '*Xray*' staticAnalysis` with the verified real AAR and `requireLibXray=true` passed in 3m21s. All 68 Xray service tests (nine classes) passed with zero failures/errors/skips. The full `just ci-gates` also passed on this rebased tree. No code changed after these checks. Historical snapshot and archive receipt fields remain intact; the current published source equivalence is documented above.
