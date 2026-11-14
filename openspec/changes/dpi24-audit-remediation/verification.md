---
task_id: ENG-1794637907795927
change: dpi24-audit-remediation
commit_sha: null
local: required
local_evidence: Pre-merge local checks passed on upstream 4ca0947; final verification must include the later upstream multiplexed correlation contract from 90728fab83.
remote_ci: required
remote_ci_evidence: Pending push and hosted workflow results.
device: not_applicable
device_evidence: This local correction uses host socket regressions; no physical-device or external-network acceptance is claimed.
artifact: required
artifact_evidence: Pre-merge API, locale, and oracle checks passed; regenerate and check compact correlation-context API after the upstream merge.
deployment: not_applicable
deployment_evidence: No deployment is owned by this source correction.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI24-COUNT | ENG-1794638210639267 | Model tests and full Rust suites cover negative, 0, 64, 65, MAX, repeated sums, internal bypass, and original bytes | Passed |
| REQ-DPI24-CORRELATION | ENG-1794638225627262 | Actual UDP pump with independent RFC v1/v2 replies; decoy VN, invalid tag, generic UDP, malformed requests, and follow-ups; all DPI-24 tests pass | Passed |
| REQ-DPI24-COMPAT | ENG-1794638225627262 | Independent compatible v1/v2 vectors, offer checks, post-Retry DCID, neutral expiry, and connection-identity reset pass; TShark decodes both directions | Passed |
| REQ-DPI24-GREASE | ENG-1794638225627262 | Independent Fixed Bit zero Initial/Retry vectors pass runtime checks; TShark decodes Initial CRYPTO and verifies both Retry tags; raw-AAD tampering fails | Passed |
| REQ-DPI24-PROBE | ENG-1794638233396968 | Loopback RED then GREEN; direct, SOCKS, route, fixed-deadline, and generic compatibility tests pass in full suites | Passed |
| REQ-DPI24-NAME | ENG-1794638240063808 | Stable ID and old-alias tests, all-locale lint/parity, translation export, and inspected editor captures pass | Passed |
| REQ-DPI24-VERIFY | ENG-1794638243019809 | Full eight-crate suite, app/model tests, lint, staticAnalysis, ci-gates, architecture contracts, locked metadata, and independent review pass on upstream 4ca0947 | Passed |
| REQ-DPI24-MULTIPLEX | ENG-1794650771683081 | Compact contexts, deduplication, delayed earlier replies, overflow, neutral identity, reselection, full combined suites and review | Pending |

## Observed checks

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
