---
task_id: ENG-1794637907795927
change: dpi24-audit-remediation
commit_sha: 8dc649181546c626d05d7e945fd4defd46e36324
local: blocked
local_evidence: Final native suites passed 1635 tests with 16 existing ignored tests; Clippy for eight crates, ci-gates, contracts, locked metadata, and independent review passed. Final Gradle is blocked before compilation by missing cached dependencies and No route to host from Google Maven; command-scoped IPv4 did not resolve it. Earlier Kotlin tests and lint passed on upstream 4ca0947, not the final source.
remote_ci: required
remote_ci_evidence: Pending push and hosted workflow results.
device: not_applicable
device_evidence: This local correction uses host socket regressions; no physical-device or external-network acceptance is claimed.
artifact: passed
artifact_evidence: Owning generator and final API checks passed with nine expected packets API lines; contracts, locale parity, and translation export passed. Independent rustls/ring vectors were decoded by TShark. Final Android lint is included in the blocked local Gradle checks.
deployment: not_applicable
deployment_evidence: No deployment is owned by this source correction.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI24-COUNT | ENG-1794638210639267 | Final Rust suites pass boundary, repeated-sum, internal-bypass, and original-byte checks; model tests passed before the latest rebase and the final Gradle run is blocked by dependencies | Blocked |
| REQ-DPI24-CORRELATION | ENG-1794638225627262 | Actual UDP pump with independent RFC v1/v2 replies; decoy VN, invalid tag, generic UDP, malformed requests, and follow-ups; all DPI-24 tests pass | Passed |
| REQ-DPI24-COMPAT | ENG-1794638225627262 | Independent compatible v1/v2 vectors, offer checks, post-Retry DCID, neutral expiry, and connection-identity reset pass; TShark decodes both directions | Passed |
| REQ-DPI24-GREASE | ENG-1794638225627262 | Independent Fixed Bit zero Initial/Retry vectors pass runtime checks; TShark decodes Initial CRYPTO and verifies both Retry tags; raw-AAD tampering fails | Passed |
| REQ-DPI24-PROBE | ENG-1794638233396968 | Loopback RED then GREEN; direct, SOCKS, route, fixed-deadline, and generic compatibility tests pass in full suites | Passed |
| REQ-DPI24-NAME | ENG-1794638240063808 | Final native labels and source locale checks pass; app aliases, editor captures, and lint passed on 4ca0947; final app/lint run is dependency-blocked | Blocked |
| REQ-DPI24-VERIFY | ENG-1794638243019809 | Final native, Clippy, source/API gates, architecture contracts, metadata, and review pass; final Gradle and hosted CI remain unresolved | Blocked |
| REQ-DPI24-MULTIPLEX | ENG-1794650771683081 | Final full suites pass compact contexts, deduplication, delayed earlier replies, overflow, neutral identity, reselection, failed-send fallback, and Lua zero-write regressions; independent review approves | Passed |

## Observed checks

- Final source `8dc649181546c626d05d7e945fd4defd46e36324` is based on upstream `a33c8d3afa9ffcc9c5a40bd6430700e4ad965258`. It preserves upstream 90728fab83 multiplexing with compact contexts and integrity-aware acceptance. The final eight-crate run passed 1635 tests, zero failed, 16 existing ignored, zero filtered. Clippy for all eight crates and all targets passed with `-D warnings` and `--no-deps`.
- Final `just ci-gates`, native architecture contracts, their 18 tests, and locked Cargo metadata passed. The generated packets API has nine expected new lines. Independent review approved the exact final assembled source; only two needless test borrows were removed after the worker bundle.
- Final Gradle attempts did not run tests or lint: offline resolution lacked the pinned Kotlin DSL plugin; online resolution downloaded that plugin and then failed on Google Maven with `No route to host`. The offline retry lacked Android/lint dependencies. Separate model and app tasks were also blocked; the model task lacked JaCoCo agent 0.8.15. A command-scoped JVM IPv4 retry still failed on Google Maven. No dependency version, repository, security check, or baseline was changed to bypass these failures.

## Earlier source evidence

- Rebased onto upstream `4ca0947874e644c49966cf482d7dc03e2a0e156d`. Independent review accepted F1 after conflict resolution and confirmed that upstream dummy, CID churn, and ladder limits and tests remain present.
- The full `:core:data:model:testDebugUnitTest` suite passed: 84 tests, no failures, errors, or skipped tests. Three affected app presentation suites passed: 17 tests, no failures, errors, or skipped tests. The offline Gradle command completed in 1 minute 49 seconds with four workers, native build disabled, Gradle heap 5 GiB, and Kotlin heap 3 GiB.
- `:app:lintGithubFullDebug :core:service:lintDebug staticAnalysis --continue` passed offline on the rebased tree in 3 minutes 4 seconds with the same resource limits. Earlier dependency-resolution failures are superseded by this observed successful run; dependencies and build configuration were not changed.
- The H1 loopback regression failed before the transport fix: an unrelated VN followed by a correlated VN produced `quic_error` instead of `quic_response`. Cargo exited 101 after successful compilation. The post-fix probe and transport checks passed in both the worker tree and the final combined Rust suite.
- Temporary editor captures for English, Russian, and Persian passed measured text-layout checks and were visually inspected. No golden files were changed or blessed.
- The final eight-crate Cargo command below passed without a test filter: 1603 passed, zero failed, 16 existing ignored, zero filtered. All 19 DPI-24 regressions passed. The ignored tests cover existing flaky fixture timing, relay-lane cases, load, and soak checks; they provide no execution evidence here.
- `just ci-gates` passed, including the generated API comparison. Native architecture contracts report zero violations and their 18 tests pass. Locked Cargo metadata passes. Only the expected packets API snapshot changed; dependencies, lockfiles, schemas, baselines, and golden files did not change.

```sh
# Run from native/rust with Rust 1.100.0.
build-gate --lane ripdpi -- cargo test \
  -p ripdpi-packets -p ripdpi-proxy-config -p ripdpi-desync \
  -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-proxy-runtime \
  -p ripdpi-diagnostics-transport -p ripdpi-diagnostics-runner \
  -p ripdpi-diagnostics-candidates --locked --jobs 4 -- --test-threads=4
# Run from the checkout root.
build-gate --lane ripdpi -- env CARGO_BUILD_JOBS=4 just ci-gates
```
