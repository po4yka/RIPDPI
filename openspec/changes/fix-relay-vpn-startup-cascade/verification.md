---
task_id: RLY-1786707070050078
change: fix-relay-vpn-startup-cascade
commit_sha: ad73c8069153966b2efd9d2fa46ef04a7de98bd7
local: passed
local_evidence: Rebased combined-tree Gradle, Rust, architecture, diagnostics, task, and OpenSpec gates passed.
remote_ci: passed
remote_ci_evidence: Full CI run 31880794002 passed for exact SHA ad73c8069153966b2efd9d2fa46ef04a7de98bd7; exact-SHA CodeQL and fleet-fixtures runs also passed.
device: passed
device_evidence: Pixel 7 API 37 with privacy-safe device scope 961ec7c9f194bbd46a0fd381bb77db26c8f23b4f0ff34091db571dd11f9e22da passed the dad-phone matrix, restoration, and post-underlay-recovery validation.
artifact: passed
artifact_evidence: Signed githubSimpleRelease APK bc6ff2ac8d9b8b06c2ef6e7e41f9473d022aa489d01ec44c3c01f8a311a2e40c passed package, signature, native ELF, and embedded-bundle verification.
deployment: not_applicable
deployment_evidence: No owner-controlled relay or production deployment is changed by this work.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY-1786707070050078-001 | RLY-1786707671531053 | Profile-derived startup/runtime plan and typed classifier tests | passed |
| REQ-RLY-1786707070050078-002 | RLY-1786707671565045 | Current-generation data-plane success suppresses failed target probe and clears negative state | passed |
| REQ-RLY-1786707070050078-003 | RLY-1786707671531053 | Target-only, repeated stage, and permanent rejection tests with native stage provenance | passed |
| REQ-RLY-1786707070050078-004 | RLY-1786707671565045 | Single-flight, 20-second reuse, two-attempt bound, persistent/session cooldown, and recovery tests | passed |
| REQ-RLY-1786707070050078-005 | RLY-1786707671608747 | TCP-only session and serialized cleanup/listener ownership tests | passed |
| REQ-RLY-1786707070050078-006 | RLY-1786707671642403 | Service/UI/notification projection tests for local-ready, checking, validated, inconclusive, and exhausted | passed |
| REQ-RLY-1786707070050078-007 | RLY-1786707671706117 | Rust/Kotlin wire, seeded migration, JSONL order, completeness, and redaction evidence | passed |
| REQ-RLY-1786707070050078-008 | RLY-1786707671742424 | Exact-SHA signed artifact and restored Pixel 7 `dad-phone` acceptance record | passed |

## Local gates

- Focused TDD cycles: exact named test after every RED/GREEN behavior.
- Kotlin modules:
  - `./gradlew :core:service:testDebugUnitTest :app:testGithubSimpleDebugUnitTest`
  - `./gradlew :core:engine:testDebugUnitTest :core:diagnostics-data:testDebugUnitTest :core:diagnostics:testDebugUnitTest`
  - `./gradlew :app:lintGithubSimpleRelease :core:service:lintDebug`
  - `./gradlew staticAnalysis`
- Rust workspace:
  - `cargo nextest run --locked -p android-support -p ripdpi-relay-core -p ripdpi-vless -p ripdpi-relay-android`
  - `cargo fmt --all -- --check`
  - `cargo clippy --locked -p android-support -p ripdpi-relay-core -p ripdpi-vless -p ripdpi-relay-android --all-targets -- -D warnings`
  - `cargo metadata --locked --manifest-path native/rust/Cargo.toml`
- Contracts:
  - `python3 scripts/ci/check_rust_api_snapshots.py`
  - `python3 scripts/ci/check_architecture_health.py`
  - diagnostics Kotlin/Rust manifest and archive golden suites on the rebased tree.
- Task/spec gates: `./taskctl validate`, `./taskctl verify RLY-1786707070050078`, and strict OpenSpec validation. Archive-ready verification remains forbidden until every evidence category is resolved.

Observed result on the rebased tree:

- The combined Gradle gate completed successfully in 18 minutes 5 seconds across service, app, engine, diagnostics-data, diagnostics, lint, static analysis, and `githubSimpleRelease` assembly.
- Native relay packages completed 304 nextest cases with 2 intentional skips; `cargo fmt`, strict targeted `clippy`, and locked metadata passed.
- Architecture health passed 26 of 26 checks, the Rust API snapshot unit suite passed 5 of 5, diagnostics contract and golden suites passed, and both task/OpenSpec validators passed. The standalone macOS API snapshot script reports only the known Darwin `cfg` projection of `ripdpi-runtime-platform`; the authoritative exact-SHA Linux CI snapshot job passed.

## Remote CI gates

- Required Android unit/lint/static-analysis and Rust test/fmt/clippy/API-snapshot jobs must pass for the exact integration SHA.
- Any flaky rerun records both attempts; external-infrastructure failures remain blocked and are not converted into a pass.

Observed result: the full push CI for
[`ad73c8069153966b2efd9d2fa46ef04a7de98bd7`](https://github.com/po4yka/RIPDPI/actions/runs/31880794002)
completed successfully, including Android unit/lint/static-analysis, Roborazzi,
Rust lint/workspace/API-snapshot/cross checks, native packaging, relay
interoperability, instrumented API 27/33/35, and release verification jobs.
Exact-SHA CodeQL run 31880794015 and fleet-fixtures run 31880794007 also passed.

## Artifact gates

- Build `githubSimpleRelease` for `arm64-v8a` from the exact rebased SHA with native build enabled.
- Verify APK signature, package/version, arm64 ELF inventory, build IDs, and byte-identical ignored `dad-phone` embedded bundle using hashes/`cmp` and the repository bundle validator without printing secrets.
- Store only owner-readable APK and redacted evidence; a debug or skip-native artifact cannot satisfy this category.

Observed result: the exact-SHA `githubSimpleRelease` arm64 APK has SHA-256
`bc6ff2ac8d9b8b06c2ef6e7e41f9473d022aa489d01ec44c3c01f8a311a2e40c`,
package `com.poyka.ripdpi.simple`, version `0.1.4-simple` (`20000012`), a valid
v2 signature, and signer certificate SHA-256
`998b983c34427944bcf714320156e9a4885d6d4ede5d1c00bfab2dbfb2c1dd32`.
Repository ELF verification passed for all four packaged ABIs. The embedded
owner-controlled bundle matched its source byte-for-byte at SHA-256
`58cd11802f76fa0839afe0dbd6176d67b667f62e5f71e19cab062b1218307432`.

## Pixel 7 acceptance

1. Record device serial/model/API, installed package/artifact hash, original active profile hash, VPN state, routes, listeners, reverse forwards, and app process state without raw profile/network secrets.
2. Run three normal cold starts; each must reach Android `VALIDATED`, route through the TUN, transfer DNS/HTTPS bytes, select the expected healthy relay, and avoid redundant probes/restarts.
3. Use a test-only target-failure injection after working egress is established; positive traffic must prevent switch and cooldown.
4. Use an isolated invalid primary profile copy; exactly one bounded transition reaches the working fallback after the old session/listener is gone.
5. Inject all-candidate failure locally; startup fails closed once, exports exact stages, and leaves no relay/proxy/tunnel process behind.
6. Restore the original `dad-phone` profile and VPN, remove all temporary reverse forwards/test state, confirm `VALIDATED` and real traffic, then observe 10 minutes without a probe/restart cascade.

Total intentional VPN disruption across steps 2–6 must remain at or below 15 minutes. Server-side relay configuration is never changed.

Observed result:

- The exact debug APK (SHA-256 `cfde274ee7c92a0c50eb245b87abe9346864b4f9c8b71cef085add8b35e3620c`) was installed on a physical Pixel 7, API 37. The installed `base.apk` hash matched. The privacy-safe device scope is `961ec7c9f194bbd46a0fd381bb77db26c8f23b4f0ff34091db571dd11f9e22da`; the original profile-cache hash is `f95773599e1fbebe0b34eb2c5f6d326e9af1c17f5cb20ba5b3c830ed3fc2a295`.
- Three normal Reality cold starts reached Android `VALIDATED`, used the TUN, transferred DNS/HTTPS data, and did not create redundant switches or cooldowns.
- A target-only failure while real traffic continued produced no switch or cooldown during 10 minutes 27 seconds. An isolated invalid primary produced exactly one transition to the healthy fallback without overlapping listeners. All candidates invalid produced one bounded failure and complete process/listener cleanup.
- Restoring the original `dad-phone` profile produced 10 minutes 28 seconds of stable VPN traffic without a retry cascade. A later loss of Internet access on the underlying Wi-Fi was confirmed externally and is not attributed to the relay implementation.
- After the Wi-Fi underlay recovered, the original profile was explicitly restarted and remained stable for more than 13 minutes. The app again reported `VLESS+Reality`; Android reported a validated VPN on `tun1`; privacy-safe DNS plus TCP/443 checks succeeded through two independent names; RX/TX counters increased; exactly two expected loopback listeners and no reverse forwards remained. Intermittent target-level failures were classified as healthy or inconclusive while data-plane evidence remained positive; the trace contained zero `confirmed_failed`, zero applied cooldown, and no transport switch or process restart.
- Server-side configuration was unchanged, temporary test state was removed, and the original profile remained active at handoff.
