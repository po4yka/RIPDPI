---
task_id: RLY-1786707070050078
change: fix-relay-vpn-startup-cascade
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No owner-controlled relay or production deployment is changed by this work.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY-1786707070050078-001 | RLY-1786707671531053 | Profile-derived startup/runtime plan and typed classifier tests | required |
| REQ-RLY-1786707070050078-002 | RLY-1786707671565045 | Current-generation data-plane success suppresses failed target probe and clears negative state | required |
| REQ-RLY-1786707070050078-003 | RLY-1786707671531053 | Target-only, repeated stage, and permanent rejection tests with native stage provenance | required |
| REQ-RLY-1786707070050078-004 | RLY-1786707671565045 | Single-flight, 20-second reuse, two-attempt bound, persistent/session cooldown, and recovery tests | required |
| REQ-RLY-1786707070050078-005 | RLY-1786707671608747 | TCP-only session and serialized cleanup/listener ownership tests | required |
| REQ-RLY-1786707070050078-006 | RLY-1786707671642403 | Service/UI/notification projection tests for local-ready, checking, validated, inconclusive, and exhausted | required |
| REQ-RLY-1786707070050078-007 | RLY-1786707671706117 | Rust/Kotlin wire, seeded migration, JSONL order, completeness, and redaction evidence | required |
| REQ-RLY-1786707070050078-008 | RLY-1786707671742424 | Exact-SHA signed artifact and restored Pixel 7 `dad-phone` acceptance record | required |

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

## Remote CI gates

- Required Android unit/lint/static-analysis and Rust test/fmt/clippy/API-snapshot jobs must pass for the exact integration SHA.
- Any flaky rerun records both attempts; external-infrastructure failures remain blocked and are not converted into a pass.

## Artifact gates

- Build `githubSimpleRelease` for `arm64-v8a` from the exact rebased SHA with native build enabled.
- Verify APK signature, package/version, arm64 ELF inventory, build IDs, and byte-identical ignored `dad-phone` embedded bundle using hashes/`cmp` and the repository bundle validator without printing secrets.
- Store only owner-readable APK and redacted evidence; a debug or skip-native artifact cannot satisfy this category.

## Pixel 7 acceptance

1. Record device serial/model/API, installed package/artifact hash, original active profile hash, VPN state, routes, listeners, reverse forwards, and app process state without raw profile/network secrets.
2. Run three normal cold starts; each must reach Android `VALIDATED`, route through the TUN, transfer DNS/HTTPS bytes, select the expected healthy relay, and avoid redundant probes/restarts.
3. Use a test-only target-failure injection after working egress is established; positive traffic must prevent switch and cooldown.
4. Use an isolated invalid primary profile copy; exactly one bounded transition reaches the working fallback after the old session/listener is gone.
5. Inject all-candidate failure locally; startup fails closed once, exports exact stages, and leaves no relay/proxy/tunnel process behind.
6. Restore the original `dad-phone` profile and VPN, remove all temporary reverse forwards/test state, confirm `VALIDATED` and real traffic, then observe 10 minutes without a probe/restart cascade.

Total intentional VPN disruption across steps 2–6 must remain at or below 15 minutes. Server-side relay configuration is never changed.
