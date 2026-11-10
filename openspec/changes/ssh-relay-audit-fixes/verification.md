---
task_id: RLY-1794300186609700
change: ssh-relay-audit-fixes
commit_sha: 10c1a907b1111507b4c8602ed92f6cfe0bc5eb35
local: passed
local_evidence: 353 Kotlin tests, full staticAnalysis, just ci-gates, architecture health, locked metadata, 43 native tests, and all 7 independent Go SSH peer cases passed. Native evidence is reused for byte-identical selected SSH inputs after the final rebase.
remote_ci: blocked
remote_ci_evidence: Hosted CI was not observed for this source revision.
device: blocked
device_evidence: No Android device or emulator run was performed. Physical device testing was not authorized.
artifact: blocked
artifact_evidence: No APK or Android native artifact was built. Kotlin checks used ripdpi.skipNativeBuild=true.
deployment: not_applicable
deployment_evidence: This change has no deployment.
---

# Verification

Source revision: `10c1a907b1111507b4c8602ed92f6cfe0bc5eb35`. Integration base: `e2d8bb7771aed3895b06813c8f1e841f7ed6d057`.
The final combined Kotlin run passed 353 tests at this source revision.
The selected SSH Rust dependencies, Cargo configuration, peer oracle, and runner are byte-identical to tested revision `4063e1bcae4501443b5e23b8ea9efbedc7c08275`.
The observed 36 unit, 7 loopback, and 7 independent-peer results remain valid for those unchanged inputs.
The five corrections have separate commits. The peer runner also has an integration correction commit.
The original audit remains at `/Users/npochaev/Downloads/RIPDPI_network_audits/RLY-11.md`.

## Corrections

| Finding | Change | Commit |
|---|---|---|
| F1 | Check the host-key pin on every rekey. | `bfc97a90c52c8e880e1271284091426e4a60f450` |
| F2 | Validate SSH admission and use the existing explicit trust editor for unpinned imports. | `fec138f42895a29d6a326e8634c820700d138409` |
| F3 | Preserve a nonempty whitespace passphrase in share links. | `1be586bd21ae957a97f18c1fa7e37fdd36463138` |
| F4 | Reserve discovery time for each remaining address. Retry recoverable failures. | `9503b84b8d7af38049507ab4f02d9040d2e53e07` |
| F5 | Cancel the owned SSH worker before waiting for the state mutex. Join it with retained unread channels. | `3b2662ef7f11cbaea97f803b9c90e762851e5829` |

The runner integration correction is `7e3c0a4f927af4b29462549a4d719f98d5393312`.
It preserves the main branch Mieru and AnyTLS scenarios and restores the SSH rekey and flood peer modes.
The shared-validator follow-up is `d1d8c8490be0df0fc0ea0db368f26267e0b5344f`. It removes a dispatcher return line to meet the method-length gate after integration, without changing validation.
The exact russh 0.65.1 source is vendored. Its provenance and patches are in
`native/rust/vendor/russh/README.ripdpi.md`. The dependency graph is unchanged.
Cargo.lock only removes the registry source and checksum for the existing russh package.
No baseline, gate exclusion, locale, saved-profile schema, or JNI signature changed in the SSH corrections.

## Requirement evidence

| Requirement | Execution step | Observed evidence | Result and scope |
|---|---|---|---|
| REQ-SSH-REKEY-PIN | RLY-1794300262750090 | `same_host_key_rekey_preserves_payload` and `changed_host_key_rekey_aborts_payload`; exact 4 MiB same-key echo, changed-key rejection | C + V/E with Go peer; Strict peer case. Pinned TOFU uses the same check; no separate TOFU peer claim. |
| REQ-SSH-IMPORT-TRUST | RLY-1794300278412406 | `SshProfileAdmissionTest`, `SshImportAdmissionTest`, `SshProfileViewModelTest`, generic admission and mapping tests | C + V/JVM. Invalid input does not activate; accept/reject and editor seed are covered. The Android route was not run. |
| REQ-SSH-PASSPHRASE | RLY-1794300282442646 | `RelayProfileShareMapperSshTest`: actual mapper/URI round trip retains exactly one space | C + V/JVM. The audit separately tested a real encrypted key with a space passphrase. |
| REQ-SSH-DISCOVERY-FALLBACK | RLY-1794300285482245 | `DefaultSshHostKeyObserverTest`: 7 passed, including first-address timeout, later key, and revocation | C + V/JVM; no Android network peer claim. |
| REQ-SSH-CLOSE-BACKPRESSURE | RLY-1794300288104643 | `unread_channel_close_is_bounded` and `unread_channel_close_is_bounded_multithread`; retained receiver, concurrent pending open, repeat close | C + V/E with Go peer. Both cases passed their bounded-close watchdog; each whole test took 0.56 s. App Stop was not tested. |

## Observed checks

Heavy commands used the user-approved temporary `rly11` build-gate lane with at most two compiler jobs. No gate or baseline was weakened.

```sh
build-gate --lane rly11 -- env CARGO_BUILD_JOBS=2 CARGO_TARGET_DIR=/tmp/rly11-native-target zsh /tmp/rly11-final-shared-main-checks.sh
```

The following commands ran from the job checkout:

```sh
./gradlew :app:testGithubFullDebugUnitTest --tests '*RelayProfileShareMapperSshTest' --tests '*SshProfileViewModelTest' --tests '*SshImportAdmissionTest' --tests '*ImportConfirmViewModelTest' --tests '*SubscriptionReserveRefreshTest' --tests '*ConnectionCloudflareValidationTest' --tests '*ConnectionNaiveProxyValidationTest' --tests '*ConnectionViewModelTest' --tests '*RelayProfileMappingTest' \
  :core:data:runtime-state:testDebugUnitTest --tests '*SshProfileAdmissionTest' --tests '*NativeRelayProfileValidationResultTest' --tests '*AnyTlsImportPinTest' --tests '*XrayConfigImportParserTest' --tests '*TrojanImportRejectionTest' --tests '*ProxyUriCodecTest' \
  :core:data:testDebugUnitTest --tests '*SelectorRelayProfileMappingTest' --tests '*ProxyUriCodecTest' --tests '*ProxyUriCodecExportTest' --tests '*SingBoxSubscriptionParserTest' \
  :core:service:testDebugUnitTest --tests '*DefaultSshHostKeyObserverTest' --tests '*AppsScriptRelayCertificateStoreTest' --tests '*CloudflarePublishRuntimeTest' --tests '*NaiveProxyRuntimePolicyTest' --tests '*NaiveUpstreamLauncherTest' --tests '*NaiveUpstreamOutputParserTest' --tests '*SubprocessRelayReadinessSignalTest' -Pripdpi.skipNativeBuild=true --max-workers=2 --continue
./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=2 --continue
PATH=/tmp/rly11-locked-tools:$PATH just ci-gates
python3 scripts/ci/check_architecture_health.py
```

| Check | Observed result |
|---|---|
| Targeted Kotlin suites | 353 passed: app 95, runtime-state 102, data 79, service 77. No failures, errors, or skips. |
| Full staticAnalysis | Passed; 826 tasks, 6 min 21 s. |
| just ci-gates | Passed: architecture, LoC/hotspots, contracts/fixtures, boundary/route checks, locales, translations, and eligible Rust API snapshots. |
| Rust API snapshots | 12 checked snapshots matched; the Linux-only runtime-platform snapshot was skipped by the existing macOS policy. |
| Separate architecture health | 18 current/baseline indicators; 0 new, worsened, or stale entries; 112 crates and 354 internal edges. |
| Locked Cargo metadata | Passed. Exactly one russh 0.65.1 package, from the local vendor path. Cargo.lock stayed unchanged during checks. |
| Native suite | 36 unit + 7 loopback passed. The 7 peer tests are ignored by the ordinary suite and were then run explicitly, one by one. |
| Independent peer suite | All 7 passed, 0 ignored in each selected run. Peer binary SHA-256: `903cec91661ecb5f66b3dee3368d9c12c2cb11cd86932dde7f66abe9cd9ca528`. |

The metadata, native, and peer commands ran at `4063e1bcae4501443b5e23b8ea9efbedc7c08275`:

```sh
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 > /tmp/rly11-final-cargo-metadata.json
cd native/rust
cargo test --locked -p ripdpi-ssh --jobs 2
cd ../..
peer_source='/tmp/claude-2042741446/-Users-npochaev--claude-spares-spare-p4pFzT/68adf53a-c622-4945-87dc-4772ddfe72c4/scratchpad/mieru-up'
for test in \
  password_auth_exchanges_payload_with_upstream \
  encrypted_private_key_auth_exchanges_payload_with_upstream \
  changed_host_key_is_rejected_before_authentication \
  same_host_key_rekey_preserves_payload \
  changed_host_key_rekey_aborts_payload \
  unread_channel_close_is_bounded \
  unread_channel_close_is_bounded_multithread
do
  python3 scripts/tests/run-outbound-interop.py --protocol ssh --jobs 2 --source-dir "$peer_source" --test "$test"
done
```

After rebase onto main `e2d8bb7771aed3895b06813c8f1e841f7ed6d057`, this check passed:

```sh
git diff --exit-code 4063e1bcae4501443b5e23b8ea9efbedc7c08275 HEAD -- \
  native/rust/crates/ripdpi-native-protect \
  native/rust/crates/ripdpi-ssh \
  native/rust/vendor/russh \
  native/rust/Cargo.toml \
  native/rust/Cargo.lock \
  native/rust/rust-toolchain.toml \
  native/rust/.cargo \
  rust-toolchain.toml \
  .cargo \
  scripts/tests
```

Cargo metadata identifies three local packages in the selected SSH test dependency closure: ripdpi-ssh, ripdpi-native-protect, and the vendored russh. Their sources and the Cargo/toolchain/peer inputs above did not change. Cloudflare and NaiveProxy changes are outside this native closure. The final Kotlin and source gates ran again on the rebased tree.
The rebase includes main's four detekt corrections. A method-length issue in the combined SSH dispatcher was corrected by returning the validator result; all checks then passed. Earlier failed checks remain in the local logs.

The repository API snapshot script invokes a Cargo plugin that has no `--locked` option. Temporary Cargo/rustup wrappers in `/tmp/rly11-locked-tools` added `--locked` to the real metadata and rustdoc commands. The plugin executable and repository scripts stayed intact. The invocation trace confirms locked workspace resolution; help and version queries do not resolve the workspace.

Local receipts:

- `/tmp/rly11-shared-main-kotlin.log` and `/tmp/rly11-shared-main-kotlin-results.json`
- `/tmp/rly11-shared-main-static.log` and `/tmp/rly11-shared-main-ci-gates.log`
- `/tmp/rly11-source-gates.log` and `/tmp/rly11-final-cargo-metadata.json`
- `/tmp/rly11-cargo-locked-invocations.jsonl`
- Earlier red cases: `/tmp/rly11-f2-red.log`, `/tmp/rly11-root-red.log`, `/tmp/rly11-root-second.log`
- Intermediate gate failures and resolution: `/tmp/rly11-static-post-rebase.log`, `/tmp/rly11-admission-finish.log`


## Review

The Android native build includes `vendor/**/*` in the cacheable task inputs (`build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts:64,1700`). This is source evidence; no Android build was run.

The native corrections passed independent source review and async cancel-safety review.
The import, share, and observation changes passed independent source review.
Integration review found missing peer-mode setup after rebase. The setup was restored before the final peer runs.
The final integration review found no remaining actionable source findings.

## Limits and next acceptance

C means current source evidence. V means an observed run. S means a checked specification requirement.
E means compatibility with one named independent peer. Source review does not increase V, S, or E.
The original audit records the checked RFC requirements. These corrections do not claim full SSH conformance.
Host peer acceptance applies to Go x/crypto/ssh v0.33.0 from clean Mieru source revision
`155ebbd60f86e472586a60d7ffe58ec8f8682cb1`. It does not prove every OpenSSH or algorithm combination.

- Run the real Android SSH import route: unpinned password/key import, inspect the displayed fingerprint, accept or reject it, and verify the selected profile.
- Run SOCKS/JNI traffic under backpressure, then Stop. Verify no active sockets or native tasks remain without a process kill.
- Build the Android artifacts and observe hosted CI for the integrated revision.
- Use a bounded local high-work-factor encrypted-key fixture to test the audit H1 KDF/cancel hypothesis. No defect is claimed for H1 yet.
- Keep the focused russh vendor patches until a pinned upstream release provides both corrections.
- SSH UDP and certificate authentication remain outside the declared scope. No device, external server, root activation, or real secret was used.

The local implementation is complete. The task remains in review. It is not archived or closed while CI, device, and artifact acceptance are unavailable.
