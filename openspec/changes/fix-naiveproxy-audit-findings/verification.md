---
task_id: RLY-1794300094981437
change: fix-naiveproxy-audit-findings
commit_sha: f06b92c7db18117892f4d0777d5295aad7e2d23d
local: passed
local_evidence: 95 Rust/CLI tests, 184 Kotlin tests, independent Caddy acceptance, staticAnalysis, ci-gates, architecture health, locked Cargo metadata, and independent review passed.
remote_ci: required
remote_ci_evidence: Check the workflows after the authorized main push.
device: not_applicable
device_evidence: Host regression tests cover these fixes. Android VPN routing and Chromium H3 acceptance remain unverified audit scope.
artifact: not_applicable
artifact_evidence: No artifact pin or packaging contract changed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

The tested code is committed in `f06b92c7db18117892f4d0777d5295aad7e2d23d` after rebase onto the current main branch. The final rebased run passed 184 Kotlin tests: TrojanImportRejectionTest: 2 passed; XrayConfigImportParserTest: 42 passed; ProxyUriCodecTest: 42 passed; NaiveUpstreamLauncherTest: 29 passed; NaiveUpstreamOutputParserTest: 10 passed; SubprocessRelayReadinessSignalTest: 7 passed; NaiveProxyRuntimePolicyTest: 9 passed; ConnectionViewModelTest: 28 passed; ConnectionNaiveProxyValidationTest: 7 passed; SubscriptionReserveRefreshTest: 8 passed. Later task-state and commit metadata changes do not change test inputs.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY-1794300094981437-001 | RLY-1794300167988729 | NaiveUpstreamLauncherTest: first-valid IPv6 and IPv4 MAP rules, malformed result rejection | passed |
| REQ-RLY-1794300094981437-002 | RLY-1794300169032673 | NaiveUpstreamOutputParserTest and SubprocessRelayReadinessSignalTest: classified proxy failures, destination exclusion, failure clearing | passed |
| REQ-RLY-1794300094981437-003 | RLY-1794300170082929 | ProxyUriCodecTest, ConnectionNaiveProxyValidationTest, NaiveProxyRuntimePolicyTest, Rust config tests, and actual helper with independent Caddy | passed |

## Local commands and observed results

Final rebased commands (job worktree, pinned Rust 1.98.1, per-worktree Cargo target, isolated Gradle home, installed JDK 21 with JDK 17 available):

```sh
cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-naiveproxy --locked --jobs 2
python3 -B /tmp/rly12-credential-acceptance.py /Users/npochaev/GitHub/RIPDPI/.worktrees/rly12-audit/native/rust/target/debug/ripdpi-naiveproxy
./gradlew :core:data:runtime-state:testDebugUnitTest --tests com.poyka.ripdpi.data.subscription.TrojanImportRejectionTest --tests com.poyka.ripdpi.data.subscription.XrayConfigImportParserTest --tests com.poyka.ripdpi.data.uri.ProxyUriCodecTest :core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.NaiveUpstreamLauncherTest --tests com.poyka.ripdpi.services.NaiveUpstreamOutputParserTest --tests com.poyka.ripdpi.services.SubprocessRelayReadinessSignalTest --tests com.poyka.ripdpi.services.NaiveProxyRuntimePolicyTest :app:testGithubFullDebugUnitTest --tests com.poyka.ripdpi.ui.screens.connection.ConnectionViewModelTest --tests com.poyka.ripdpi.ui.screens.connection.ConnectionNaiveProxyValidationTest --tests com.poyka.ripdpi.subscription.SubscriptionReserveRefreshTest --max-workers=4 -Pripdpi.skipNativeBuild=true
./gradlew staticAnalysis --max-workers=4 -Pripdpi.skipNativeBuild=true
just ci-gates
python3 /tmp/rly12-check-metadata.py
```

The metadata wrapper runs `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1` and records the member and package counts. Each command exited 0. The build-gate used an original lane only; configuration and foreign processes stayed unchanged.

- `cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-naiveproxy --locked --jobs 2` on the final rebased job worktree with its own target directory (also passed earlier in the credential worktree): 91 helper tests and 4 CLI tests passed. The 4 existing independent-peer tests stayed ignored in this command; they are not counted as passed.
- Initial run before extraction and rebase: combined Gradle tasks `:core:data:runtime-state:testDebugUnitTest`, `:core:service:testDebugUnitTest`, and `:app:testGithubFullDebugUnitTest`, filtered to the six owning test classes: 131 tests passed, no failures, errors, or skips. Class counts: URI 41, launcher 29, readiness 7, output parser 10, runtime policy 9, editor 35. Used `-Pripdpi.skipNativeBuild=true --max-workers=4 --offline`; no APK was assembled.
- After the first staticAnalysis failure, the Naive validation tests were moved to `ConnectionNaiveProxyValidationTest` to reduce the existing large editor test class. An initial separate constant-rename commit was later dropped during rebase in favor of the upstream equivalent. The three affected app test classes passed again after extraction: ConnectionViewModelTest: 28 passed; ConnectionNaiveProxyValidationTest: 7 passed; SubscriptionReserveRefreshTest: 8 passed. No baseline or suppression changed.
- `python3 -B /tmp/rly12-credential-acceptance.py <built-helper>`: three invalid credential inputs failed before READY, with no target receipt. A valid password containing a colon completed CONNECT and echoed 32 bytes. Independent klzgrad/forwardproxy commit `e747854e78743dba85c039b38ed185fb11d93667` on patched Caddy v2.11.7, peer SHA256 `9d6903d83353f986f772ccb2d4f4a43d7ae5d43637853237621d22c7999c51cd`. Cleanup passed.
- The pre-existing static-analysis prerequisites were independently repaired upstream while this work was tested. Rebase kept those upstream parser and constant fixes and removed our duplicates. Existing tests passed after this change: TrojanImportRejectionTest: 2 passed; XrayConfigImportParserTest: 42 passed; ProxyUriCodecTest: 42 passed; SubscriptionReserveRefreshTest also passed again. No baseline or suppression changed.
- `./gradlew staticAnalysis --max-workers=4 -Pripdpi.skipNativeBuild=true`: passed. Environment recovery used installed JDK 17/21 and an isolated Gradle dependency cache; dependency and repository declarations, TLS verification, and gates did not change. The Google Maven Perfetto AAR matched live official metadata SHA256 `e42bb39e5aa180299d94ba230011371bb4b2f6234afda44a0a2e6f830751d4c6`.
- `just ci-gates`: passed on the combined tree. Host-appropriate Rust API snapshots matched; the checker excludes the Linux-owned runtime-platform snapshot on macOS.
- `python3 scripts/ci/check_architecture_health.py`: passed, no new or worsened indicators.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`: passed with the pinned Rust toolchain; 112 workspace members and 934 packages.
- Independent review of F1–F4 and the final rebase integration found no actionable findings. Normal commit hooks, including Rust formatting and clippy, passed.

## Evidence locations

Temporary local evidence: `/tmp/rly12-rust-rebased.log`, `/tmp/rly12-peer-rebased.log`, `/tmp/rly12-kotlin-rebased.log`, `/tmp/rly12-credentials-green.log`, `/tmp/rly12-kotlin-green.log`, `/tmp/rly12-credential-acceptance/result.json`, `/tmp/rly12-app-recheck.log`, `/tmp/rly12-static-analysis.log`, `/tmp/rly12-ci-gates.log`, and `/tmp/rly12-final-validation-results.json`.

Initial Gradle attempts stopped before test or analysis assertions because of a Google Maven TLS/cache issue and missing isolated JDK 17 discovery. Later analysis runs found the documented existing detekt violations; all were repaired without baseline changes. These failures are preserved in the local logs. The final successful commands above are the acceptance evidence.

## Remaining scope

No physical-device, root, external-server traffic, Android H3/concurrency, source-to-binary Chromium rebuild, or golden-blessing operation was run. The old audit hypotheses remain hypotheses; the confirmed F1–F4 findings are repaired.
