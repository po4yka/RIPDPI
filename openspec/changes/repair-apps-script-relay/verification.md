---
task_id: RLY-1794300111377171
change: repair-apps-script-relay
commit_sha: 7c86c94990ba0cafb0bb0294f98bf5c83b7fbf32
local: passed
local_evidence: "Targeted native and Kotlin tests, Clippy, cargo-deny and all ci-gates passed. Follow-up fixes cleared naming, MagicNumber, ComplexCondition and ReturnCount; full staticAnalysis and 72 subscription tests passed."
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

## Initial repair checks

- `cargo test -p ripdpi-apps-script-core --locked`: 73 combined tests passed before the additional test-only CA regression. `cargo clippy -p ripdpi-apps-script-core --all-targets --locked -- -D warnings` passed.
- The additional `client_must_explicitly_trust_the_relay_ca` native regression passed before F7 commit. Its empty trust store fails; explicit CA trust succeeds.
- `:core:service:testDebugUnitTest --tests '*AppsScriptRelayCertificateStoreTest'`: 3 passed. `:core:engine:testDebugUnitTest --tests '*RelayNativeConfigTest'`: 16 passed, and passed again after the mapper was split to satisfy the existing long-function budget.
- `:app:testGithubFullDebugUnitTest --tests '*AppsScriptCaExportTest'`: 1 passed with native Robolectric graphics; disclosure, dismiss, picker and error paths were exercised.
- `:app:compileGithubFullDebugKotlin :app:lintGithubFullDebug :core:service:lintDebug` passed with `-Pripdpi.skipNativeBuild=true --max-workers=4`.
- `just ci-gates` passed on combined validation HEAD `4c1b396ac87d26cbb284196aac5afd90ace98f42`, tree `358b4da7a84ec0b64cacd5fe03360abb8991c716`. The initial integration worktree had identical production, resource, contract and peer-document inputs. Commit ordering differed because the workers used isolated branches.
- The initial `staticAnalysis` attempt ran and failed at `app/src/test/kotlin/com/poyka/ripdpi/subscription/SubscriptionReserveRefreshTest.kt:19`. `MALFORMED_VLESS_ID` violates `TopLevelPropertyNaming`. At that point, the file was byte-identical to audited base `ed21bbfd241b62fe8eb233573df21da38dfb9544`; no naming rule or baseline was changed.
- `cargo deny --locked check` passed with local cargo-deny 0.19.0. CI pins 0.19.8; that CI tool version was not run locally. The lockfile adds only an existing constant_time_eq dependency edge.
- Independent reviewers checked native correctness, async cancellation and Kotlin trust/export. The Connection-nominated Content-Type leak through `ct`, ignored public fixture and missing UI interaction test were fixed. The section-mapper refactor received a second review.

## Local evidence files

Ephemeral logs: `/tmp/rly15-combined-native-tests.log`, `/tmp/rly15-combined-native-clippy.log`, `/tmp/rly15-auth-deny.log`, `/tmp/rly15-trust-parent.log`, `/tmp/rly15-trust-commits-v2.log` and `/tmp/rly15-http-commits-leased.log`.

## Follow-up local validation

The code in commit `5ae32a22237d202aa0cf97ca3e1b3cb1da2efaf3` clears the four static-analysis findings without changing fixture values, TLS acceptance, skip reasons or configured budgets. The Sing-box password reader already rejects blank values; its early return preserves that contract. Xray uses a single `when` after credential validation.

Observed on this source tree before commit:

- Full `staticAnalysis`: BUILD SUCCESSFUL, with `-Pripdpi.skipNativeBuild=true --max-workers=4`.
- `just ci-gates`: passed, including host-appropriate Rust API snapshots.
- Parser tests: TrojanImportRejectionTest 2, XrayConfigImportParserTest 42 and SingBoxSubscriptionParserTest 20, all passed.
- SubscriptionReserveRefreshTest: 8 passed; no test was skipped.
- All four code commits passed their normal hooks. A temporary dedicated build-gate slot was used without changing the permanent gate or shared validation rules.

Logs: `/tmp/subscription-naming-and-parser-final.log` and `/tmp/subscription-reserve-refresh-test.log`.

After rebase onto `origin/main` at `4f3b6298e2e19a6b965195aeda11f702a2b7991c`, the checks ran again on `7c86c94990ba0cafb0bb0294f98bf5c83b7fbf32` in the dedicated slot:

- Full `staticAnalysis` passed in 28 minutes 43 seconds with the same Gradle options.
- The same 72 subscription tests passed without failures, errors or skipped tests.
- `just ci-gates` passed. The Linux-owned `ripdpi-runtime-platform` API snapshot was excluded on macOS by the checker policy; all host-appropriate snapshots matched.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1` passed with 112 workspace members.

Log: `/tmp/subscription-rebased-validation.log`.

## Remaining acceptance

Physical-device public-CA installation, a real owner-controlled Google deployment, fronting compatibility and load/capacity tests remain unverified. Local tests do not establish external interoperability. The OpenSpec change stays in review; it is not archived or declared fully accepted.
