---
task_id: RLY-1794300186609700
change: ssh-relay-audit-fixes
commit_sha: 172da1724db082f00a8f36ba411e04a1b38cf91d
local: passed
local_evidence: 357 Kotlin tests, full staticAnalysis, just ci-gates, locked metadata, 43 native SSH tests, 7 independent Go SSH peer cases, and 338 shared configuration/routing tests passed. The last rebase changed task/spec documents only; all code and build inputs are identical to the observed run checkpoint.
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

Published-source checkpoint: `172da1724db082f00a8f36ba411e04a1b38cf91d`. Integration base: `c7a658b95075a4bf44a18e8639611a2869120446`.
Observed run checkpoint: `d475cd0b8376f8d6b16e95e15d67fd7d1a9576e7`. The last main update archived Tor task/spec documents only.
The following check returned no differences for all application, native, script, and build inputs:

```sh
git diff --exit-code d475cd0b8376f8d6b16e95e15d67fd7d1a9576e7 HEAD -- app core native scripts build-logic gradle gradle.properties justfile rust-toolchain.toml .cargo
```

## Corrections

| Finding | Change | Separate correction commit |
|---|---|---|
| F1 | Enforce the host-key pin on each rekey. | `31ffd2804576ba0480eeffa9acf50bb3ad736d45` |
| F2 | Validate endpoint/auth/required secret/pin; use the existing explicit trust editor for unpinned imports. | `0faffeb6e57f6c28a3cd369160c9306560217df7` |
| F3 | Preserve nonempty whitespace passphrases in share links. | `ed4c1446d1d465852c29285358bd47fa7cf869df` |
| F4 | Reserve discovery time for later addresses and retry recoverable failures. | `1fd1b7e9c0e0a31bf2538da4e1e1c09603146166` |
| F5 | Cancel the owned worker before waiting for its state mutex. | `dafd80b98258a63dee4a6ec1ee8a1bb2d464ff66` |

The runner integration correction is `87561ea152111057e1272323c42b1f03bd3911f6`.
It keeps the main branch Mieru/AnyTLS scenarios and restores the SSH rekey/flood peer modes.
The shared-validator follow-up is `f9d9ee9d1d8f76c3d6c58f39d70285caca4b4cf1`.
It keeps the dispatcher within the method-length gate without changing any validation check.

The exact russh 0.65.1 source is vendored. Provenance, license, and focused patches are in
`native/rust/vendor/russh/README.ripdpi.md`. Relative to the integration base, Cargo.lock only removes
its registry source and checksum. No package version or dependency edge changes in the SSH corrections.
No baseline, gate exclusion, locale, saved-profile schema, or JNI signature changes in these corrections.

## Requirement evidence

| Requirement | Execution step | Evidence and scope | Result |
|---|---|---|---|
| REQ-SSH-REKEY-PIN | RLY-1794300262750090 | Same-key 4 MiB echo and changed-key rejection with Go peer. Strict peer case; pinned TOFU uses the same check but has no separate peer claim. | C + V/E |
| REQ-SSH-IMPORT-TRUST | RLY-1794300278412406 | Admission, import/editor seed, accept/reject, and mapping tests. Invalid endpoint/auth/pin or missing required secret does not activate. Android route was not run. | C + V/JVM |
| REQ-SSH-PASSPHRASE | RLY-1794300282442646 | Actual share mapper and URI round trip retain exactly one space. The original audit separately tested a real encrypted key with that passphrase. | C + V/JVM |
| REQ-SSH-DISCOVERY-FALLBACK | RLY-1794300285482245 | Observer suite: first-address timeout, later observed key, and network/protection revocation. No Android network peer claim. | C + V/JVM |
| REQ-SSH-CLOSE-BACKPRESSURE | RLY-1794300288104643 | Retained unread receiver, concurrent pending open, and repeat close with Go peer. Both cases passed the bounded-close watchdog; each whole test took 0.56 s. | C + V/E |

PEM parsing/decryption remains native authority. A malformed key or wrong passphrase can still fail at connection time;
structural admission does not prove that a server will accept credentials.
The original audit and its complete protocol/provider matrix remain at
`/Users/npochaev/Downloads/RIPDPI_network_audits/RLY-11.md`.

## Observed commands and results

Heavy commands used the user-approved temporary rly11 build-gate lane with at most two compiler jobs:

```sh
build-gate --lane rly11 -- env CARGO_BUILD_JOBS=2 CARGO_TARGET_DIR=/tmp/rly11-native-target zsh /tmp/rly11-final-tor-integration-checks.sh
```

The script ran these Kotlin/source checks:

```sh
./gradlew :app:detekt :core:data:runtime-state:detekt :core:service:detekt -Pripdpi.skipNativeBuild=true --max-workers=2 --continue
./gradlew :app:testGithubFullDebugUnitTest --tests '*RelayProfileShareMapperSshTest' --tests '*SshProfileViewModelTest' --tests '*SshImportAdmissionTest' --tests '*ImportConfirmViewModelTest' --tests '*SubscriptionReserveRefreshTest' --tests '*ConnectionCloudflareValidationTest' --tests '*ConnectionNaiveProxyValidationTest' --tests '*ConnectionViewModelTest' --tests '*ConnectionTorValidationTest' --tests '*RelayProfileMappingTest' \
  :core:data:runtime-state:testDebugUnitTest --tests '*SshProfileAdmissionTest' --tests '*NativeRelayProfileValidationResultTest' --tests '*AnyTlsImportPinTest' --tests '*XrayConfigImportParserTest' --tests '*TrojanImportRejectionTest' --tests '*TorBridgeValidationTest' --tests '*ProxyUriCodecTest' \
  :core:data:testDebugUnitTest --tests '*SelectorRelayProfileMappingTest' --tests '*ProxyUriCodecTest' --tests '*ProxyUriCodecExportTest' --tests '*SingBoxSubscriptionParserTest' \
  :core:service:testDebugUnitTest --tests '*DefaultSshHostKeyObserverTest' --tests '*AppsScriptRelayCertificateStoreTest' --tests '*CloudflarePublishRuntimeTest' --tests '*NaiveProxyRuntimePolicyTest' --tests '*NaiveUpstreamLauncherTest' --tests '*NaiveUpstreamOutputParserTest' --tests '*SubprocessRelayReadinessSignalTest' --tests '*UpstreamRelayRuntimeConfigResolverTest' --tests '*TorRuntimePathProviderTest' -Pripdpi.skipNativeBuild=true --max-workers=2 --continue
./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=2 --continue
PATH=/tmp/rly11-locked-tools:$PATH just ci-gates
```

| Check | Observed result |
|---|---|
| Targeted Kotlin suites | 357 passed: app 96, runtime-state 102, data 79, service 80. No failures, errors, or skips. |
| Full staticAnalysis | Passed; 826 tasks, 5 min 17 s. |
| just ci-gates | Passed: architecture health, LoC/hotspots, contracts/fixtures, boundary/routes, locales, translations, and eligible Rust API snapshots. |
| Architecture health | No new or worsened indicators. The separate checker also passed before integration. |
| Rust API snapshots | 12 checked snapshots matched. The Linux-only runtime-platform snapshot was skipped by the existing macOS policy. |
| Locked Cargo metadata | Passed; exactly one local russh 0.65.1 package. |
| SSH native suite | 36 unit + 7 loopback passed. The ordinary suite ignores the 7 peer cases; each was then run explicitly with 1 passed and 0 ignored. |
| Independent peer | All 7 passed. Go x/crypto/ssh v0.33.0 from clean Mieru revision `155ebbd60f86e472586a60d7ffe58ec8f8682cb1`. Peer binary SHA-256 `903cec91661ecb5f66b3dee3368d9c12c2cb11cd86932dde7f66abe9cd9ca528`. |
| Shared configuration/routing boundary | 182 config + 154 proxy-config + 2 loopback remote-DNS tests passed. |

The native and boundary commands were:

```sh
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 > /tmp/rly11-tor-integration-cargo-metadata.json
cd native/rust
cargo test --locked -p ripdpi-ssh --jobs 2
cargo test --locked -p ripdpi-config -p ripdpi-proxy-config --lib --jobs 2
cargo test --locked -p ripdpi-proxy-runtime --test tor_remote_dns --jobs 2
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

The Cargo API plugin has no `--locked` option. Temporary Cargo/rustup wrappers in
`/tmp/rly11-locked-tools` added it to the real metadata/rustdoc calls. Repository scripts and plugin
binaries stayed intact. `/tmp/rly11-cargo-locked-invocations.jsonl` records the commands; version/help
queries do not resolve the workspace. No gate was weakened.

Receipts: `/tmp/rly11-tor-integration-{native,boundary-rust,kotlin,static,ci-gates}.log`,
`/tmp/rly11-tor-integration-kotlin-results.json`, and `/tmp/rly11-tor-integration-cargo-metadata.json`.
Earlier red cases remain in `/tmp/rly11-f2-red.log`, `/tmp/rly11-root-red.log`, and `/tmp/rly11-root-second.log`.
The earlier full static run found four main-branch detekt issues and a combined dispatcher length issue;
main's corrections and the validator follow-up resolved them. The final full gate passed.

## Review and build integration

Independent source and async cancel-safety reviews passed. Integration review caught missing peer-mode
environment setup after an earlier rebase; it was restored before the passing peer runs.
The Tor boundary review found that `remote_dns_upstream` defaults to None, is assigned only for Tor,
and is reset by fresh UI configuration creation. SSH auth/fingerprint fields and its 10 s connect budget stay intact.
The Android cacheable native task tracks `native/rust/vendor/**/*` as input
(`build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts:64,1700`). This is C evidence,
not an Android build result.

## Limits and next acceptance

C is current code evidence. V is an observed run. S is a checked specification requirement.
E is compatibility with a named independent peer. Code review does not increase V/S/E.
The original audit records the checked RFC requirements; no full SSH conformance claim is made here.

- Run Android unpinned password/key import, inspect the fingerprint, accept/reject it, and verify profile selection.
- Run real SOCKS/JNI traffic under backpressure, then Stop; verify no active sockets/tasks without a process kill.
- Build Android artifacts and observe hosted CI for the integrated revision.
- Use a bounded high-work-factor encrypted-key fixture to test audit hypothesis H1. No defect is claimed for H1 yet.
- Maintain the focused russh patches until a pinned upstream release supplies both corrections.
- UDP and certificate authentication remain outside the declared SSH scope. No physical device, external SSH server, root activation, or real SSH test credential was used.

The implementation is complete. The task remains in review, not archived or closed, while CI/device/artifact acceptance is unavailable.
