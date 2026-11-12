---
task_id: RLY-1794477185649013
change: tun07-contract-repairs
commit_sha: 070fbb675736db08844e93230eb7e07598f228f8
local: passed
local_evidence: All eight fixes passed independent review; combined 2331 tests, staticAnalysis and final ci-gates passed at the recorded source commit. Exact commands and scope are below.
remote_ci: required
remote_ci_evidence: Final published commit has not been checked.
device: not_applicable
device_evidence: No device or external transport behavior is enabled by these fixes; Android runtime compatibility remains unverified.
artifact: passed
artifact_evidence: Pinned Lyrebird built locally for all four Android ABIs with Go 1.22.12 and NDK 29; receipts are recorded below. APK and installed ABI receipts remain unverified.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TUN07-RUN | RLY-1794478845733549 | Reader isolation RED 8/2failed;GREEN 29 tests plus ktlint/detekt ; independent review | Passed |
| REQ-TUN07-CERT | RLY-1794478726909442 | Cert RED 6/1failed;GREEN 30 tests plus affected ktlint/detekt ; independent review | Passed |
| REQ-TUN07-RELAY | RLY-1794477279590881 | F1 rejection and UI suites ; 49 tests, ktlint/detekt, independent review | Passed |
| REQ-TUN07-IDENTITY | RLY-1794477280143697 | Permission preflight suite ; 9 tests, ktlint/detekt, independent review | Passed |
| REQ-TUN07-STARTUP | RLY-1794477280711169 | Managed parser/readiness/wire/supervisor suites ; 64 tests and independent review | Passed |
| REQ-TUN07-STARTUP | RLY-1794479241816365 | RED 22/1 failed; GREEN 33 tests, ktlint/detekt and independent review | Passed |
| REQ-TUN07-STOP | RLY-1794477281462969 | Loopback stop/handshake tests in managed64-test run ; closed admission lock reviewed | Passed |
| REQ-TUN07-ARGS | RLY-1794477282071499 | Reserved-separator expected output in manager suite ; 64-test run and independent review | Passed |
| REQ-TUN07-EVIDENCE | RLY-1794477282626394 | Pending implementation and observed checks | Required |

## Observed repair checks

- F1: 49 targeted service/app tests passed, including legacy-profile rejection, Tor selection and unchanged screenshot verification; affected ktlint and detekt passed. Log: `/tmp/tun07-f1-green.log`.
- F2: nine permission-preflight tests passed, including hex/dollar/base64 RSA, RSA/Ed25519 ordering, IPv4/IPv6 and LAN policy; affected ktlint and detekt passed. Log: `/tmp/tun07-f2-green.log`.
- Managed PT: 64 targeted manager, wire adapter, supervisor and component tests passed; detekt and formatting passed. Log: `/tmp/tun07-pt-combined-check3.log`.
- Independent reviewer approved these slices before their commits. Subsequent review found the stopped-reader isolation defect; the follow-up requires its own checks and review.
- Initial combined `just ci-gates` passed at `d306affbd5`. The Linux-owned runtime-platform API snapshot is skipped on Darwin by the gate. Its existing API tool invokes Cargo internally without a locked option; the lockfile stayed unchanged. Explicit metadata check used `cargo metadata --manifest-path native/rust/Cargo.toml --locked --offline --format-version 1 --no-deps` and passed.

## Source and artifact receipt

Lyrebird source commit: `fc105a03c0e0acc2479301c361c012ffed359c43`. Builds used Go 1.22.12, NDK 29.0.14206865, Android API 27, GOOS=android, ABI-specific GOARCH, CGO_ENABLED=1 and NDK compilers; GOARM=7 for armeabi-v7a. Host: Darwin arm64, CGO disabled.

Command: `go build -p=4 -trimpath -buildvcs=false '-ldflags=-s -w -buildid=' -o <temporary ABI output> ./cmd/lyrebird`. Environment: `GOFLAGS=-mod=readonly -trimpath -buildvcs=false`, `GOTOOLCHAIN=local`, `GOMAXPROCS=4`; GOPROXY used the local module-download cache. Invoked through `build-gate --lane ripdpi -- python3 /private/tmp/ripdpi-tun07-evidence/build.py`.

Exact commands, compilers, environment and ELF headers: `/private/tmp/ripdpi-tun07-evidence/build-receipt.json`. All builds exited zero. NDK llvm-readelf confirmed Android ELF class, machine and DYN type.

| Target | Bytes | SHA-256 |
|---|---:|---|
| host | 15081538 | `47703344a96cfba552dbe5923a118ab0135e3ab3aeb58f3dfba1a0523b0a2114` |
| armeabi-v7a | 15177064 | `f76950b7d8ac1ca1a8f91045b35aa441f5f351aa5bbeff5063178e4362355d27` |
| arm64-v8a | 16376768 | `38639affddff2e9113a4b99662ad9ea5d6308922483eb01abc0fa21d4846e221` |
| x86 | 15420240 | `1d078b5c5c641d9fc1ec32d3f3a28e09e163f58f88c3ba32f7fd2dd4ac40987d` |
| x86_64 | 18342176 | `fe9661d78e0f557c35b0906b5a8c3c85580963984eec6e8f884eafd832984f6a` |

These receipts use the producer's source/build parameters. They do not prove the Gradle producer manifest, packaged APK/AAB, installed ABI or released artifact.

## Actual helper checks

`python3 /private/tmp/ripdpi-tun07-evidence/loopback.py` passed against the source-built host helper. An independent Python PT/SOCKS client drove actual Lyrebird client/server processes and loopback echo. Each iat-mode 0/1/2 carried and returned 5,200 bytes over authenticated obfs4. Transcript: VERSION 1, matching loopback CMETHOD, CMETHODS DONE. Both processes exited zero on stdin EOF; managed version 2 returned VERSION-ERROR and exit 1. All owned processes exited. Receipt: `/private/tmp/ripdpi-tun07-evidence/loopback-receipt.json`.

This is V evidence for a real host helper. Both cryptographic peers use the same implementation; it does not establish independent wire interoperability, Android process behavior, Tor bootstrap, IAT timing or external E evidence. Tor+obfs4 uses Arti's PT owner; the shared Kotlin adapter checks do not prove that end-to-end path.

## Remaining acceptance

- Match source-strict producer metadata and helper hashes to APK/AAB; check installed ABI and packaged method negotiation.
- Run isolated Tor-network routing/trust acceptance and Android stop/cancel/parent-death checks.
- External bridge, physical device, root and real-secret checks were not run and need separate explicit authorization.
- Read final pushed-commit CI state; local tests do not prove remote CI.

## Certificate and run-isolation checks

- Certificate RED: `build-gate --lane ripdpi -- ./gradlew :core:data:settings:testDebugUnitTest --tests com.poyka.ripdpi.data.TorBridgeValidationTest --offline --max-workers=4 -Pripdpi.skipNativeBuild=true` produced one expected failure in six tests. After the fix,6 data,1 app,9 preflight and14 service resolver tests passed. The first service filter named the source file instead of its concrete class; XML inspection caught that, and `--tests com.poyka.ripdpi.services.UpstreamRelayRuntimeConfigResolverExternalFamiliesTest` passed separately. Logs: `/tmp/tun07-cert-{red,green,service-green}.log`. All three affected modules passed ktlintCheck/detekt.
- Reader isolation RED: 8 tests, 2 expected stale-event failures. GREEN: `build-gate --lane ripdpi -- ./gradlew :core:service:ktlintCheck :core:service:testDebugUnitTest --tests '*SubprocessRelayProcessSupervisorTest' --tests '*SubprocessRelayComponentsTest' :core:service:detekt -Pripdpi.skipNativeBuild=true --max-workers=4 --offline` passed 29 tests. Logs: `/tmp/tun07-run-red.log` and `/tmp/tun07-run-green2.log`. The first GREEN attempt hit two long-line lint errors; formatting then a separate check resolved them without a baseline change.
- The reader test controls a pending stdout transcript and invokes the real parser. It proves process-generation event isolation at this boundary, not Android scheduling or an Arti/Tor failure. Supervisor locking serializes reset, parse/event delivery, stop invalidation and release. Review found no reverse manager-to-supervisor lock edge.

## Failure-reason retention

`build-gate --lane ripdpi -- ./gradlew :core:service:testDebugUnitTest --tests com.poyka.ripdpi.services.SubprocessRelayComponentsTest --tests com.poyka.ripdpi.services.SubprocessRelayReadinessSignalTest :core:service:ktlintCheck :core:service:detekt --offline --max-workers=4 -Pripdpi.skipNativeBuild=true` passed 33 tests with no failures/errors/skips. Log: `/tmp/tun07-failure-reason-green3.log`. RED had one late-error timeout failure among 22 tests. Formatting resolved long-line lint errors; the cancellation test now asserts the exception type and message, since coroutine stack recovery does not promise object identity.

The manager test controls failure ordering through its existing private callback and checks the actual thrown error and telemetry. It does not prove real Android subprocess timing. Review approved the final diff and the combined supervisor-to-manager lock order. Typed errors take precedence for managed and other subprocess helpers; plain output errors retain their prior behavior.

## Combined validation

At source commit `070fbb6757`, the following command exited zero in 10m 13s:

```bash
build-gate --lane ripdpi -- ./gradlew :core:service:testDebugUnitTest :core:data:settings:testDebugUnitTest :app:testGithubFullDebugUnitTest --tests '*ModeEditorScreenTest' --tests '*ModeEditorRelayEnabledTest' --tests '*ModeEditorScreenshotTest' --tests '*ConnectionTorValidationTest' staticAnalysis --offline --max-workers=4 -Pripdpi.skipNativeBuild=true -Pripdpi.includeRoborazziUnitTests=true -Proborazzi.test.verify=true
```

XML totals: service 2,199; settings 89; app 20; detekt-rule tests 23. Total 2,331 tests with zero failures, errors or skips. Full service/settings suites ran; app selection covers changed Tor validation, legacy editor behavior and existing screenshot verification. `staticAnalysis` completed all configured detekt, ktlint, Android Lint and source checks. Log: `/tmp/tun07-combined.log`.

This command skips native artifact wiring as required for JVM/static checks. It is not APK or JNI-runtime evidence. No golden, baseline, dependency or pinned source change was made.

Final source gates: `build-gate --lane ripdpi -- env CARGO_BUILD_JOBS=4 CARGO_NET_OFFLINE=true just ci-gates` exited zero. Log: `/tmp/tun07-ci-gates-final.log`. Architecture, LoC/hotspot budgets, runtime contracts, fixture versions, diagnostics boundary, route coverage, locale/translation and supported-host API snapshots passed. The Linux-owned runtime-platform API snapshot remains unrun on Darwin by design. Cargo.lock and all baselines stayed unchanged.

Strict OpenSpec validation passed through `./taskctl openspec cli validate tun07-contract-repairs --strict`; task contract validation passed. `git fetch origin` completed and showed no upstream commits beyond the audited base. No external task or message was created.
