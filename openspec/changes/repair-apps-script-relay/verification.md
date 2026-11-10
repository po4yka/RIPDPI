---
task_id: RLY-1794300111377171
change: repair-apps-script-relay
commit_sha: f0bd0f3568ba75ea2e8acc14fe711f45269c1582
local: blocked
local_evidence: "Targeted native and Kotlin tests, Clippy, cargo-deny and all ci-gates passed. Full staticAnalysis fails on unchanged SubscriptionReserveRefreshTest.kt:19 TopLevelPropertyNaming."
remote_ci: not_applicable
remote_ci_evidence: Hosted CI was not used as acceptance evidence.
device: required
device_evidence: Physical non-root CA trust and Google deployment acceptance were not run.
artifact: passed
artifact_evidence: App Kotlin compilation, native host compilation and app/service lint passed; no release APK acceptance claimed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY15-AUTH | RLY-1794300154444303 | 72 auth-branch tests; 73 combined native tests; Clippy and authenticated runtime HTTP regression passed | Passed locally |
| REQ-RLY15-BOUNDARIES | RLY-1794300155126413 | Preflight and non-HTTP/silent IP sentinel regressions passed; no implicit direct connection | Passed locally |
| REQ-RLY15-HTTP | RLY-1794300155126413 | Header, envelope, redirect and binary encoding regressions passed; Clippy passed | Passed locally |
| REQ-RLY15-TRUST | RLY-1794300156159325 | 3 certificate-store tests, 16 config tests, 1 Compose export test passed; explicit-CA native regression passed | Passed locally |
| REQ-RLY15-CONTROL | RLY-1794300156159325 | Parallel UI/action/key removed in all locales; locale and translation export gates passed | Passed locally |
| REQ-RLY15-PEER | RLY-1794300157206276 | Versioned in-repository contract and carrier assumptions documented; Google service acceptance not run | Passed locally |
| REQ-SEPARATE-CORS-SITES-WITH-PUBLIC-SUFFIX-LIST-BEHAVIOR | RLY-1794300155126413 | Preflight and non-HTTP/silent IP sentinel regressions passed; no implicit direct connection | Passed locally |
| REQ-SEPARATE-CORS-SITES-WITH-PUBLIC-SUFFIX-LIST-COMPAT | RLY-1794300155126413 | Preflight and non-HTTP/silent IP sentinel regressions passed; no implicit direct connection | Passed locally |

## Observed checks

- `cargo test -p ripdpi-apps-script-core --locked`: 73 combined tests passed before the additional test-only CA regression. `cargo clippy -p ripdpi-apps-script-core --all-targets --locked -- -D warnings` passed.
- The additional `client_must_explicitly_trust_the_relay_ca` native regression passed before F7 commit. Its empty trust store fails; explicit CA trust succeeds.
- `:core:service:testDebugUnitTest --tests '*AppsScriptRelayCertificateStoreTest'`: 3 passed. `:core:engine:testDebugUnitTest --tests '*RelayNativeConfigTest'`: 16 passed, and passed again after the mapper was split to satisfy the existing long-function budget.
- `:app:testGithubFullDebugUnitTest --tests '*AppsScriptCaExportTest'`: 1 passed with native Robolectric graphics; disclosure, dismiss, picker and error paths were exercised.
- `:app:compileGithubFullDebugKotlin :app:lintGithubFullDebug :core:service:lintDebug` passed with `-Pripdpi.skipNativeBuild=true --max-workers=4`.
- `just ci-gates` passed on combined validation HEAD `4c1b396ac87d26cbb284196aac5afd90ace98f42`, tree `358b4da7a84ec0b64cacd5fe03360abb8991c716`. The integration worktree has identical production, resource, contract and peer-document inputs. Commit ordering differs because the workers used isolated branches.
- `staticAnalysis` ran and failed at `app/src/test/kotlin/com/poyka/ripdpi/subscription/SubscriptionReserveRefreshTest.kt:19`. `MALFORMED_VLESS_ID` violates `TopLevelPropertyNaming`. The file is byte-identical to audited base `ed21bbfd241b62fe8eb233573df21da38dfb9544`; no naming rule or baseline was changed.
- `cargo deny --locked check` passed with local cargo-deny 0.19.0. CI pins 0.19.8; that CI tool version was not run locally. The lockfile adds only an existing constant_time_eq dependency edge.
- Independent reviewers checked native correctness, async cancellation and Kotlin trust/export. The Connection-nominated Content-Type leak through `ct`, ignored public fixture and missing UI interaction test were fixed. The section-mapper refactor received a second review.

## Local evidence files

Ephemeral logs: `/tmp/rly15-combined-native-tests.log`, `/tmp/rly15-combined-native-clippy.log`, `/tmp/rly15-auth-deny.log`, `/tmp/rly15-trust-parent.log`, `/tmp/rly15-trust-commits-v2.log` and `/tmp/rly15-http-commits-leased.log`.

## Remaining acceptance

Full staticAnalysis remains blocked by the existing unrelated naming violation. Physical-device public-CA installation, a real owner-controlled Google deployment, fronting compatibility and load/capacity tests remain unverified. Local tests do not establish external interoperability. The OpenSpec change stays in review; it is not archived or declared fully accepted.
