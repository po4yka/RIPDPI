---
task_id: RLY-1794491312461911
change: tun08-snowflake-repairs
commit_sha: f7f5ddeb3b4775f88b1663754ef7210a59cf2b9e
local: passed
local_evidence: 2332 JVM tests and 3 build-logic tests passed; full staticAnalysis passed. Final ci-gates, architecture and locked metadata passed; see commands and logs below.
remote_ci: required
remote_ci_evidence: Pending pushed revision checks.
device: not_applicable
device_evidence: No physical device authorization; Android runtime acceptance is explicitly unverified.
artifact: passed
artifact_evidence: Actual Snowflake-only producer and strict prebuilt roundtrip passed for four Android ABIs; stale linker flags rejected. This is helper build evidence, not APK execution.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TUN08-SOURCE | RLY-1794491445870654 | Official source host/Android builds, actual managed PT, loopback DTLS/DataChannel, producer/prebuilt receipt checks | Passed for local scope |
| REQ-TUN08-RENDEZVOUS | RLY-1794491446372538 | RED/GREEN settings, service and app tests; independent review with AMP correction | Passed |
| REQ-TUN08-SCOPE | RLY-1794491446921184 | Existing Tor-only regressions, independent review and combined gates | Passed for local scope |

## Repair commits

- F1 was already fixed on main by `0552aaae27948fd626b471f9cdf1cc0e841197f4`: standalone Snowflake fails closed with Tor guidance. No duplicate repair or automatic bridge identity was added.
- F3: `0251d54182` rejects missing, malformed and conflicting HTTP/AMP/SQS rendezvous before persistence and runtime setup. SQS credential content remains upstream-owned; validation does not prove credential validity or remote availability.
- F2: `df39c30969f5a8a30fb0a4198b80875dc2103bbf` builds official Snowflake separately from Lyrebird and enables DTLS mimic in the active Tor process. Explicit bridge overrides retain upstream precedence. Broker uTLS is an explicit bridge option. Source-specific Android linker arguments are recorded and verified.
- The managed PT/version/DONE, numeric loopback endpoint, stdout/stderr, control-line limit, process ownership, readiness timeout and stdin shutdown repairs were already on the base `3e01ef8bdbcc7c815f136ef6e8ccb80a3bac9513`. Preserve their tests. The old cancellation observation is not a confirmed production defect: the outer supervisor owns non-cancellable cleanup. DIA-10 remains a reachability diagnostic, not Snowflake compatibility proof.

## Observed regressions and independent review

- F3 RED: 9 tests, 2 failures; AMP RED: 10 tests, 1 failure. Final owning GREEN: settings 10, service 13, app 1, no errors or skips. Logs `/tmp/tun08-rendezvous-{red,amp-red,green-final}.log`.
- F2 RED: the active manager returned no mimic arguments. GREEN: 29 service tests (Tor configuration 2, helper locator 14, existing PT 13), plus 3 build-logic tests. Logs `/tmp/ripdpi-tun08-build/kotlin-{red2,green}.log`. Placeholder files in locator tests establish lookup/configuration only; they are not executable artifact evidence.
- Independent reviewers approved both final behavior changes. F3 review found AMP publisher/cache constraints; those were added and retested. The first combined gate found four overlong new lines. The next found a ReturnCount violation in the new validator. Both were corrected without changing behavior, rules or baselines.
- Final combined Gradle run passed in 6m 40s: 93 settings, 2220 service and 19 app tests (2332 total), zero failures/errors/skips, plus full staticAnalysis. Native builds are disabled only for JVM/lint tasks; this flag is not APK build evidence. The final source revision includes the behavior-equivalent ReturnCount refactor `f7f5ddeb3b4775f88b1663754ef7210a59cf2b9e`, independently reviewed.

Final `just ci-gates` and `cargo metadata --locked` passed on the final source tree. Architecture indicators stayed at 18 with zero new or worsened entries; all source gates passed. The standard macOS API gate skips the Linux-owned `ripdpi-runtime-platform` snapshot; no Linux result is claimed. Task/OpenSpec validation passed after board regeneration.

## Source and wire evidence

Official source: [Snowflake SHA 3bd77dc0](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/-/tree/3bd77dc0f574255d072f70a840e18c0af3d43866), tag v2.15.3, Go 1.26.10, package `./client`. Lyrebird remains at `fc105a03c0e0acc2479301c361c012ffed359c43` for obfs4. No Rust dependency or baseline changed.

The exact host binary SHA256 is `057228e04232f8f23d20cd75e370497a5d80b779924a3b5dfff84e9720559d8a`. A real process emitted VERSION 1, CMETHOD snowflake socks5 with a loopback endpoint, and CMETHODS DONE. Requesting obfs4 emitted CMETHOD-ERROR. With mimic arguments, closing stdin exited 0. No broker connection was made.

A temporary test used the pinned Snowflake CovertDTLS parser/settings and two real Pion WebRTC peers over loopback, with no ICE servers. DataChannel payload arrived in both disable and mimic modes. Captured ClientHello lengths were 124 and 164 bytes. Mimic stable fields matched the recorded browser capture bundled with pinned covert-dtls, after excluding random/session/cookie; disable differed. This is an independent capture oracle for the local library wire path, not a full application or Tor network test. Trace and command: `/tmp/ripdpi-tun08-build/dtls-loopback.log`, temporary `auditcheck/covert_test.go` in the research clone.

The original Android build failed with anet's private `net.zoneCache` reference. The [pinned upstream build requirement](https://github.com/wlynxg/anet/blob/839bc3a920f1b87dd3ce1386e425aa5ef2e69d24/README.md#how-to-build-with-go-1230-or-later) requires `-checklinkname=0`. Inspection found identical cache field types/order in anet 0.0.5, Go 1.26.10 and x/net 0.57.0. Only Snowflake gets this compatibility option. Private layouts and Android runtime behavior must be rechecked on upgrades; the option does not establish runtime safety by itself.

## Android helper provenance

The actual `buildPluggableTransportAssets` task ran with a temporary Snowflake-only manifest, isolated source clone/output, all four ABIs, NDK 29.0.14206865 and API 27. It produced source receipts and ELF files. A separate direct build produced identical ELF hashes. The real strict prebuilt consumer preserved the manifest and all hashes. Two negative runs removed source flags or changed artifact flags and recomputed the receipt digest: both failed with `unexpected Go linker flags`.

Receipt: `/tmp/ripdpi-tun08-build/producer/metadata/pluggable-transports.json`, SHA256 `0fb9b7dc3cd327da1b6392947031e6e594384cb6a1fb4f36dfc2ef54da9793ef`.

| ABI | Bytes | ELF SHA256 |
|---|---:|---|
| armeabi-v7a | 16911348 | `69fc6264eb86ea136061c5dce68a1693f4b284567a4b8ebbb9dab04308b45476` |
| arm64-v8a | 17751488 | `272128d3ea471278b4dccc5e2a28fbe2f2c36d5705014dda0ec4faa16b4c8be7` |
| x86 | 17199884 | `3f2ce556c05134caf89c4cc45adc7897461374c11d0d3e0c2ff0e64e3584da84` |
| x86_64 | 20127680 | `52843bfe308563ee3522f13901b68f640008720da0aa47155ce8ca672833a392` |

All are PIE ET_DYN with the expected machine and Android loader. The 64-bit PT_LOAD alignment is 0x4000; 32-bit is 0x1000. Separate Snowflake increases packaging size while Lyrebird retains unused bundled code; compressed APK size was not measured.

## Commands and logs

All heavy commands used `build-gate --lane ripdpi --`, at most four workers/jobs, isolated Cargo targets, and no device or external transport probes.

```sh
# Coordinator checkout /tmp/ripdpi-tun08-fixes-20261112
./gradlew --offline -Pripdpi.skipNativeBuild=true --max-workers=4 -Pripdpi.nativeCpuBudget=4 \
  '-Dorg.gradle.jvmargs=-Xmx5g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:SoftRefLRUPolicyMSPerMB=50 -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8' \
  :core:data:settings:testDebugUnitTest :core:service:testDebugUnitTest \
  :app:testGithubFullDebugUnitTest \
  --tests com.poyka.ripdpi.ui.screens.connection.ConnectionTorValidationTest \
  --tests com.poyka.ripdpi.ui.screens.connection.ModeEditorScreenTest staticAnalysis --console=plain
# Logs: /tmp/tun08-combined-gradle.log (formatting failure), /tmp/tun08-combined-gradle-2.log (ReturnCount failure), /tmp/tun08-combined-gradle-3.log (final run)

env CARGO_BUILD_JOBS=4 CARGO_INCREMENTAL=0 \
  CARGO_TARGET_DIR=/tmp/ripdpi-tun08-fixes-20261112/native/rust/target RUSTC_WRAPPER=sccache just ci-gates
# Logs: /tmp/tun08-ci-gates.log and /tmp/tun08-ci-gates-final.log
```

Direct final metadata check: `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`. The existing ci-gates API script does not add `--locked` to all internal Cargo invocations; it ran unchanged. The separate locked metadata check verifies the checked-in dependency resolution. No lockfile changed.

Exact source/producer/prebuilt commands and their temporary init scripts are in `/tmp/ripdpi-tun08-build/F2-evidence.md`. Host Go commands use `GOTOOLCHAIN=go1.26.10`, `GOMAXPROCS=4`, `-p=4`, and isolated Go caches. Producer command uses `:core:engine:buildPluggableTransportAssets --init-script /tmp/ripdpi-tun08-build/producer.init.gradle --no-configuration-cache`; consumer adds its init script, producer directory and pinned receipt SHA256. `/tmp/ripdpi-tun08-build/negative-receipts.sh` runs the two expected rejection checks.

## Evidence boundaries and remaining acceptance

- **C:** active Tor path, source pin, process defaults, validator and receipt contracts checked.
- **V:** JVM/build tests, real host managed PT, loopback DTLS/DataChannel, Android cross-compilation and actual producer/consumer checks. Installed Android APK execution is not established.
- **S:** selected upstream CLI/rendezvous and PT v1 contracts checked; no claim of complete WebRTC/Tor conformance.
- **E:** no external broker/front/STUN/proxy/bridge/Tor compatibility established.

Still required for release acceptance: full source-built APK/AAB and installed digest; Android process/ABI and interface/IPv6-zone behavior; configured broker/front/ICE exchange, Tor bootstrap and destination-correct TCP through an exit; reconnect and stop. Physical device, external transport probes, root and real credentials were not authorized. No such probes were run. Remote CI state remains separate from local results.
