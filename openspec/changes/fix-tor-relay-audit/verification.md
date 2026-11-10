---
task_id: RLY-1794300120922878
change: fix-tor-relay-audit
commit_sha: null
local: required
local_evidence: null
remote_ci: not_applicable
remote_ci_evidence: Local gates are required; hosted CI is not an acceptance gate for this repair.
device: not_applicable
device_evidence: No physical device or external Tor endpoint is authorized. Local process and loopback regressions cover this repair.
artifact: not_applicable
artifact_evidence: No release artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TOR-DNS | RLY-1794300204878737 | Targeted regression and independent review completed; combined gates pending | local passed |
| REQ-TOR-BUDGET | RLY-1794300205521753 | Targeted regression and independent review completed; combined gates pending | local passed |
| REQ-TOR-RETRY | RLY-1794300206174299 | Targeted regression and independent review completed; combined gates pending | local passed |
| REQ-TOR-SHUTDOWN | RLY-1794300206828819 | Targeted regression and independent review completed; combined gates pending | local passed |
| REQ-TOR-STATE | RLY-1794300207611622 | Targeted regression and independent review completed; combined gates pending | local passed |
| REQ-TOR-VALIDATION | RLY-1794300208607627 | Targeted regression and independent review completed; combined gates pending | local passed |

## Observed local behavior

- Rust proxy runtime: 383 unit tests passed. `tor_remote_dns`: two real loopback regressions passed, including SOCKS4a, SOCKS5, HTTP, plaintext address input in Mixed/BytePrefixed mode, authentication, active Stop, upstream REP5, policy REP2 and unsupported-command REP7. Pinned Rust 1.98.1 logs: `/tmp/rly13-telemetry-tests.log`, `/tmp/rly13-telemetry-clippy.log`. The loopback cases also prove production tx/rx counters and upstream forwarding evidence for the four-byte exchange.
- After rebase: config 182, proxy-config 154, session 88, proxy-runtime 383 plus two loopback tests, and Tor 14 tests passed: 823 total. Logs: `/tmp/rly13-rebased-config-tests.log`, `/tmp/rly13-rebased-runtime-tests.log`, `/tmp/rly13-rebased-tor-tests.log`. Rust 1.98.1 and `--locked` were used from `native/rust`, with a worktree-local target directory.
- Tor baseline: ten local tests passed. Bootstrap retry regression passed. Three PT regressions passed: quiet/stubborn process shutdown, injected reader spawn failure, and missing executable error classification. Logs: `/tmp/rly13-lifecycle-parent.log`, `/tmp/rly13-lifecycle-final-verified.log`, `/tmp/rly13-pt-final-tests.log`. Chutney early-return tests are excluded from interoperability evidence.
- Kotlin: 51 selected tests passed: service paths (2), settings bridge validation (4), app draft validation (1), Xray import (42), and Trojan rejection (2). This includes distinct unpaired UTF-16 code units in state keys. Final targeted log: `/tmp/rly13-kotlin-targeted-complete.log`.
- Independent PR reviews covered all six repairs. Findings on bridge identities, Snowflake absent address, URL validation, SOCKS reply codes, thread startup ownership and PT startup classification were fixed before final review. Async review found no semantic cancellation defect; its documentation findings were fixed.

## Integration gate work

- Initial static analysis found dispatcher complexity introduced by the Tor branch and a pre-existing private test constant naming violation. The dispatcher now owns its Tor error mapping in a separate function. The constant rename is a separate mechanical prerequisite commit; no fixture value changed.
- The broad static-analysis run was interrupted before completion so final checks can use the latest upstream base. Its partial output is not a passing gate. Final `just ci-gates`, `staticAnalysis`, locked Cargo metadata and architecture health remain pending. No baseline or golden fixture is blessed or extended.
- Local behavior runs used the owning worktrees. Final combined-source evidence will name the integrated source commit. No public Tor circuit, Android runtime, release artifact or deployment is claimed.

- Final Tor domain-path review restored application-byte counters and forwarding evidence. The loopback regression uses the production telemetry observer and checks only bytes committed by successful writes. Pinned Rust 1.98.1 and a worktree-local target directory are used for final Rust validation.

- Final rebase onto upstream `4f3b6298e2e19a6b965195aeda11f702a2b7991c` completed without conflicts. Independent post-rebase review found no Tor caller compatibility defect. Architecture health (`--check`) and locked Cargo metadata passed.

## Final command set

Rust commands run from `native/rust` with Rust 1.98.1, `RUSTC_WRAPPER=sccache`, and `CARGO_TARGET_DIR` set to this worktree's `native/rust/target`.

```sh
cargo test -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-session --locked --lib
cargo test -p ripdpi-proxy-runtime --locked --lib --test tor_remote_dns
cargo test -p ripdpi-tor --locked --lib --test bootstrap_retry --test bridge_pt_config --test client --test pt_shutdown --test state_dns
cargo clippy -p ripdpi-proxy-runtime --locked --all-targets -- -D warnings
cargo fmt -p ripdpi-proxy-runtime -- --check
cargo metadata --locked --format-version 1 --no-deps
```

Root commands:

```sh
python3 scripts/ci/check_architecture_health.py --check
./gradlew staticAnalysis \
  :core:service:testDebugUnitTest \
    --tests '*TorRuntimePathProviderTest' \
    --tests '*RelaySocketProtectionPolicyTest' \
    --tests '*PluggableTransportManagerTest' \
    --tests '*UpstreamRelayRuntimeConfigResolverTest' \
    --tests '*PluggableTransportSocksBridgeWireTest' \
    --tests '*AppsScriptRelayCertificateStoreTest' \
  :core:data:settings:testDebugUnitTest --tests '*TorBridgeValidationTest' \
  :app:testGithubFullDebugUnitTest --tests '*ConnectionTorValidationTest' \
  :core:data:runtime-state:testDebugUnitTest \
    --tests '*XrayConfigImportParserTest' --tests '*TrojanImportRejectionTest' \
  -Pripdpi.skipNativeBuild=true --max-workers=4
just ci-gates
```

No real Tor circuit, physical Android device, external bridge, or production secret is used. The remaining external and device checks in the audit stay open. Unresolved Tor domains with Direct routes or applicable IP rules fail closed; this compatibility boundary is documented in `docs/native/proxy-engine.md`. Safe legacy profile directories remain in use. Ambiguous legacy directories are retained without copying or deletion.

- The first post-rebase static-analysis run found a pre-existing MagicNumber violation in the new Apps Script certificate store. The unchanged 65,536-byte bound now has a named constant. Its existing certificate-store test is included in the final command. This is a separate mechanical prerequisite commit; no baseline change is used.

- Completed gates before the final mechanical rebase: `staticAnalysis` and `just ci-gates` passed (`/tmp/rly13-android-final.log`, `/tmp/rly13-rebased-ci-gates.log`). The corrected resolver-class filter passed in `/tmp/rly13-service-final.log`. JUnit XML totals were 56 service, 4 settings, 44 runtime-state and 1 app tests: 105 tests, zero failures, errors or skips.
- A final fetch found upstream commits through `b6c7274ebb6d59e64cf3502a88e80d4a8bb13351` that independently resolve the same mechanical gate prerequisites. Keep the published versions during rebase and recheck the affected Kotlin files.
