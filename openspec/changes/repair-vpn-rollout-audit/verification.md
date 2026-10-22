---
task_id: VPN-1792657107995294
change: repair-vpn-rollout-audit
commit_sha: 130827437
local: passed
local_evidence: Targeted Kotlin and Rust tests, staticAnalysis, workspace Clippy, Cargo Deny, architecture health, native contracts, and locked metadata passed.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: This change does not deploy or change live server configurations.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SHARE-CREDENTIALS | VPN-1792657277467804 | BackupGroupRedactionTest: 7 passed; SHARE URL carrier matrix and FULL preservation | Passed |
| REQ-RELAY-DNS | VPN-1792657278981644 | ripdpi-relay-android and ripdpi-native-protect: 26 passed, including 5 JVM/JNI regressions | Passed |
| REQ-RUNTIME-CLEANUP | VPN-1792657282574861 | core:service:testDebugUnitTest: 2257 passed, including 12 RuntimeExitCleanupTest scenarios | Passed |
| REQ-SUBSCRIPTION-REFRESH | VPN-1792657284863659 | App subscription/import tests: 104 passed again after scheduler adapter change; Hilt/KSP and staticAnalysis passed | Passed |
| REQ-RECIPIENT-COMPATIBILITY | VPN-1792657285994594 | core:data import/backup suite: 101 passed; generated backend bundle and AWG-only refresh passed | Passed |
| REQ-COMPATIBILITY-EVIDENCE | VPN-1792657287269408 | 122 backend checks passed; 22 common contract JSON files byte-identical; staticAnalysis passed; Android gates pending | Pending |

## Scope and commands

- Backend reference: `794202c393648986ceea7afd7d022933fbe44dce` in a clean detached checkout.
- Generate the regression document with `python3 scripts/tests/generate_deployment_bundle_fixture.py --backend <clean-backend-checkout>`.
- Generated payload SHA-256: `583d1a51c7f864b66726255d6656dd9bb40e8cf08a97a1cdb47c748232d501d3`.
- Run `cargo test -p ripdpi-relay-android -p ripdpi-native-protect --locked --jobs 4` from `native/rust`.
- Kotlin checks use `:core:data:testDebugUnitTest`, `:core:service:testDebugUnitTest`, and `:app:testGithubFullDebugUnitTest`. Data tests cover backup, selector, bundle, sing-box, and AWG contracts. App tests cover subscription and import classes. The service suite runs in full. These JVM checks use `ripdpi.skipNativeBuild=true`; they do not prove APK contents.
- Backend checks cover recipient boundaries, port hopping, recipient rendering, emit roundtrip, and transport semantics. The official parser check passed with backend-CI-pinned sing-box 1.14.3.
- Three backend native transport tests require a disposable Linux root environment and did not pass on macOS. They are not counted among the 122 passing tests.
- The generated document uses synthetic authority data and the real materializer. It proves serialization and import behavior, not server reachability or a user VPN session.
- No live server configuration, deployment, signing key, or real user credential was changed.

## Independent review

Separate agents reviewed runtime cleanup, backup privacy, subscription scheduling,
selector topology, JNI ownership, and backend compatibility. Confirmed review
findings were fixed. Follow-up review found no remaining actionable issue in
those changes. Runtime and device results remain separate gates.

The final `staticAnalysis` and 104 app tests passed after all Detekt fixes.
The selector regression suite passed again after the root-selection refactor.

The post-reboot Rust rerun passed all 26 tests and full workspace Clippy.
`cargo deny --locked --manifest-path native/rust/Cargo.toml check` passed
(advisories, bans, licenses and sources); the existing warning policy was unchanged.
Final architecture health measured 116 crates and 4109 source files, with no new
or worsened indicator. The backend fixture regenerated to the same payload hash.
New validation logs are stored in the worktree under `build/rollout-evidence/`.
