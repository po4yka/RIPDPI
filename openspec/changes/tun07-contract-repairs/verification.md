---
task_id: RLY-1794477185649013
change: tun07-contract-repairs
commit_sha: null
local: required
local_evidence: Regression suites, static analysis, CI source gates and independent review are pending.
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
| REQ-TUN07-RUN | RLY-1794478845733549 | Pending process isolation and observed checks | Required |
| REQ-TUN07-CERT | RLY-1794478726909442 | Pending format validation and observed checks | Required |
| REQ-TUN07-RELAY | RLY-1794477279590881 | Pending implementation and observed checks | Required |
| REQ-TUN07-IDENTITY | RLY-1794477280143697 | Pending implementation and observed checks | Required |
| REQ-TUN07-STARTUP | RLY-1794477280711169 | Pending implementation and observed checks | Required |
| REQ-TUN07-STARTUP | RLY-1794479241816365 | Pending helper-failure retention through SOCKS readiness | Required |
| REQ-TUN07-STOP | RLY-1794477281462969 | Pending implementation and observed checks | Required |
| REQ-TUN07-ARGS | RLY-1794477282071499 | Pending implementation and observed checks | Required |
| REQ-TUN07-EVIDENCE | RLY-1794477282626394 | Pending implementation and observed checks | Required |

## Observed repair checks

- F1:49 targeted service/app tests passed, including legacy-profile rejection, Tor selection and unchanged screenshot verification; affected ktlint and detekt passed. Log: `/tmp/tun07-f1-green.log`.
- F2:nine permission-preflight tests passed, including hex/dollar/base64 RSA, RSA/Ed25519 ordering, IPv4/IPv6 and LAN policy; affected ktlint and detekt passed. Log: `/tmp/tun07-f2-green.log`.
- Managed PT:64 targeted manager, wire adapter, supervisor and component tests passed; detekt and formatting passed. Log: `/tmp/tun07-pt-combined-check3.log`.
- Independent reviewer approved these slices before their commits. Subsequent review found the stopped-reader isolation defect; the follow-up requires its own checks and review.
- Initial combined `just ci-gates` passed at `d306affbd5`. The Linux-owned runtime-platform API snapshot is skipped on Darwin by the gate. Its existing API tool invokes Cargo internally without a locked option; the lockfile stayed unchanged. Explicit metadata check used `cargo metadata --manifest-path native/rust/Cargo.toml --locked --offline --format-version 1 --no-deps` and passed.

## Source and artifact receipt

Lyrebird source commit: `fc105a03c0e0acc2479301c361c012ffed359c43`. Builds used Go1.22.12, NDK29.0.14206865, Android API27, GOOS=android, ABI-specific GOARCH, CGO_ENABLED=1 and NDK compilers; GOARM=7 for armeabi-v7a. Host:Darwin arm64, CGO disabled.

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

`python3 /private/tmp/ripdpi-tun07-evidence/loopback.py` passed against the source-built host helper. An independent Python PT/SOCKS client drove actual Lyrebird client/server processes and loopback echo. Each iat-mode0/1/2 carried and returned5,200bytes over authenticated obfs4. Transcript:VERSION1, matching loopback CMETHOD, CMETHODS DONE. Both processes exited zero on stdin EOF; managed version2 returned VERSION-ERROR and exit1. All owned processes exited. Receipt: `/private/tmp/ripdpi-tun07-evidence/loopback-receipt.json`.

This is V evidence for a real host helper. Both cryptographic peers use the same implementation; it does not establish independent wire interoperability, Android process behavior, Tor bootstrap, IAT timing or external E evidence. Tor+obfs4 uses Arti's PT owner; the shared Kotlin adapter checks do not prove that end-to-end path.

## Remaining acceptance

- Match source-strict producer metadata and helper hashes to APK/AAB; check installed ABI and packaged method negotiation.
- Run isolated Tor-network routing/trust acceptance and Android stop/cancel/parent-death checks.
- External bridge, physical device, root and real-secret checks were not run and need separate explicit authorization.
- Read final pushed-commit CI state; local tests do not prove remote CI.
