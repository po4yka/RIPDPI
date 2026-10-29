---
task_id: TRN-1793271658511991
change: awg31-behavior-parity
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change; the shared arm64 deployment guard remains unchanged.
---

# Verification

This is an implementation acceptance plan. Step-level checks are recorded below; the combined implementation gates remain required. `commit_sha` stays null until results identify an implementation revision. The documentation commit and planning validation must not fill the implementation evidence fields.

## Requirement evidence

Each row names the primary owning step. Its step contract includes supporting native, configuration, and acceptance steps. All results below are pending.

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG3-WIRE | TRN-1793272215891212 | Required G1 length/MTU regressions and G4-G5 pinned-peer observations for data, automatic and persistent keepalive, HP and trailers | Required; not run for the implementation |
| REQ-AWG3-HOSTILE | TRN-1793272218210665 | Required G1 hostile/authenticated/replay/reset cases and G4-G5 carrier-bound receipts | Required; not run for the implementation |
| REQ-AWG3-CONTRACT | TRN-1793272220215141 | Required G2 import/edit/storage/export/activation and redaction tests plus G5 UI evidence | Required; not run for the implementation |
| REQ-AWG31-TIMERS | TRN-1793272215354505 | Required G1 deterministic role/state/boundary tests and G4-G5 timed peer events after the backend decision | Required; not run for the implementation |
| REQ-AWG31-CLASSIFY | TRN-1793272217604418 | Required G1 independent authenticated collision, failure-state and replay tests plus G4 peer case | Required; not run for the implementation |
| REQ-AWG31-EMPTY | TRN-1793272216407538 | Required G1 authenticated zero/nonzero malformed/replay tests and G4 empty-packet peer result | Required; not run for the implementation |
| REQ-AWG31-COOKIES | TRN-1793272216991110 | Required G1 and G4 both flag states, both handshake directions, cookie messages, MAC failure and bounded load | Required; not run for the implementation |
| REQ-AWG31-INPUT | TRN-1793272218937903 | Required G2 shared grammar and HP readiness tests, including supporting step TRN-1793272219644936 | Required; not run for the implementation |
| REQ-AWG31-EDITOR | TRN-1793272220215141 | Required G2 preset replacement/cancel and v3 tests, G3 locale parity, G5 rendered editor checks | Required; not run for the implementation |
| REQ-AWG31-SUBSET | TRN-1793272220792888 | Required G1-G3 CPS/numeric/header/junk boundaries and updated compatibility documentation | Required; not run for the implementation |
| REQ-AWG31-EVIDENCE | TRN-1793272223722013 | Required G4 validator negative controls, pinned matrix, G5 emulator, G6 physical/artifact and G7 hosted CI/review receipts | Required; not run for the implementation |

## Gate commands and acceptance

Run from the repository root unless a gate states another directory. Use the repository build gate for compilation. Commands below are planned gates, not a record of commands run in this planning task. Re-run passing checks only after their inputs change.

### G1 — Native behavior

From `native/rust`:

```sh
build-gate --lane ripdpi -- cargo test --manifest-path vendor/boringtun/Cargo.toml --lib --locked --jobs 4
build-gate --lane ripdpi -- cargo test -p ripdpi-warp-core --locked --jobs 4
cargo fmt --all -- --check
cargo fmt --manifest-path vendor/boringtun/Cargo.toml -- --check
build-gate --lane ripdpi -- cargo clippy --workspace --all-targets --locked --jobs 4 -- -D warnings
```

The owning tests must fail on the old behavior and pass after each fix. Include all step-contract cases for timers, retry/expiry, both session roles, padding precedence and empty packets, candidate collisions, replay, malformed authenticated data, cookie load states, bounded parsing and UDP-window reset/MTU. Use controlled clocks or backend timer injection for exact boundaries, not long sleeps as the unit-test oracle. Keep independent Go-derived vectors and MAC/AEAD validation. Existing tests that already prove a case need not be duplicated.

### G2 — Configuration and UI behavior

```sh
build-gate --lane ripdpi -- ./gradlew :core:data:model:testDebugUnitTest :core:data:runtime-state:testDebugUnitTest :core:data:testDebugUnitTest :core:engine-api:testDebugUnitTest :core:engine:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest --max-workers=4 -Pripdpi.nativeCpuBudget=4
```

Extend existing suites named in the step contracts. The shared invalid-input corpus must run through editor, `.conf`, and URI paths. Test old stored profiles, all nine v3 values, keepalive ranges, HP prefix readiness, secret storage/redaction, explicit cohort replacement and cancel, and native serialization. Unknown keys and known-but-unapplied fields must not be conflated.

### G3 — Static, locale, and contract gates

```sh
build-gate --lane ripdpi -- ./gradlew staticAnalysis --max-workers=4 -Pripdpi.nativeCpuBudget=4
build-gate --lane ripdpi -- ./gradlew :app:lintGithubFullDebug :core:service:lintDebug --max-workers=4 -Pripdpi.nativeCpuBudget=4
python3 scripts/ci/check_architecture_health.py
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1
```

Locale lint is required when strings change. Include en, ru, es, de, fr, fa, ar, zh-CN, hi, and pt-BR. Run applicable schema/golden/JNI contract checks if the actual implementation changes those contracts; record their exact commands when the backend decision resolves the diff. Do not relax baselines or bless fixtures without the required workflow.

### G4 — Host peers and feature validators

Existing entry points to extend and run:

```sh
build-gate --lane ripdpi -- python3 scripts/tests/run-standalone-awg-interop.py
python3 -m unittest scripts.tests.test_standalone_awg_interop_runner scripts.tests.test_android_awg_interop_evidence
```

From `scripts/fixtures/amneziawg-peer`, run `build-gate --lane ripdpi -- go test -mod=readonly ./...` with the Go toolchain pinned by its manifest. Record the exact toolchain and peer binary hash. Extend the host runner and fixture under their existing owners; publish the concrete matrix and isolated Linux runner command in this file before accepting the implementation. A kernel module must not be loaded into the developer's host as a side effect of these checks.

| Peer | Exact revision | Required treatment |
|---|---|---|
| Go v3.1.20260828 | b5928efb6ca19f0153958460c3d141f04abc5c2e | Primary 3.1 reference; resolve or explicitly block on upstream startup failures |
| Go v3.1.20260814 | 1b86b2ae0e493e7ea93f8c1a0f0cb6735b1551f1 | Compatibility run; isolate older cookie behavior and other patch differences |
| Linux AWG | 4569c4c67f3a57414969260cafbbd04694fbaae0 | Disposable Linux environment; identify fixed expiry behavior and CPS/junk trailer patch |

For each peer, preserve the baseline and exercise HP on/off, fixed/ranged H, equal/unequal S, CPA only, RT only, both on and both off. Observe initiation, response, cookie and transport separately. Include empty transport, idle, retries, rekey, expiry, load, controlled loss, endpoint changes and restarts. Include wrong HP key, corrupt MAC/ciphertext, replay, ambiguous classification, malformed authenticated payload, and MTU boundaries. Peer defects must have an explicit case result; they do not justify weakening RIPDPI's requirements or replacing evidence with a skipped test.

Validator tests must take a valid observation and remove or alter each asserted property, including timer events and idle packets, then require failure. Match packet sizes to independent expected bounds and sample policy. Do not use a configured flag or a successful payload hash as proof that the corresponding wire feature occurred. Prove trailers apply only to their intended packet types and do not modify CPS/junk.

### G5 — Current-source Android emulator and editor

The runner already owns a command-scoped arm64 pool lease. Use an installed compatible image; do not create an unowned AVD. Example existing 5.x entry point, with a fresh run nonce and output directory supplied at execution:

```sh
build-gate --lane ripdpi -- python3 scripts/tests/run-android-awg-interop.py --profile amneziavpn-5x --api 35 --output-dir "$AWG_EVIDENCE_DIR" --nonce "$AWG_RUN_NONCE"
python3 scripts/tests/validate-android-awg-interop.py --evidence "$AWG_EVIDENCE_JSON" --nonce "$AWG_RUN_NONCE" --source-sha "$AWG_SOURCE_SHA"
```

Also run the baseline `awg-3.1-ranges-v1` and the feature profiles added by the harness steps. Record exact commands after the new profile interface is defined. Require the existing IPv4/IPv6 TCP, UDP and DNS transfers at MTU 1280/1420 and restarts, plus the new feature assertions. The 5.x scope keeps 4 MiB TCP payloads and 512 lockstep UDP exchanges and wrong-HP-key rejection. A test-only native/JNI bridge result does not prove full production VpnService behavior.

Use a leased emulator to render the production editor. Inspect v3 normal/error states, HP validation, preset replacement/cancel, keyboard/scrolling, accessibility, RTL and long locale text. Record source and screenshots without secrets; do not infer visual correctness from unit tests alone.

### G6 — Physical arm64 and exact artifacts

Follow `test-lab/acceptance/README.md#owned-physical-transport-runs` after explicit authorization for the named device. Extend the existing acceptance owner if its AWG production-service scenario cannot prove the contract; record its exact scenario and command before the run. Require the exact serial, device lock, fresh-install ownership receipt, private LAN addresses, a reachable direct peer, and the production VpnService path. Keep private installation details out of committed evidence.

Record source SHA, clean-tree state, APK SHA-256, each tested native artifact SHA-256 and ABI, device/OS, peer revision and binary/container digest, sanitized profile parameters, MTU, test matrix, traces, and pass/fail result. Verify protected socket routing, DNS, IPv4/IPv6 traffic, restart/handover, no unintended bypass, and AWG feature observations. A loopback sentinel or the USB test-only datagram bridge is not direct device-to-LAN evidence. Emulator success is not physical evidence.

Rebuild and test the exact candidate artifacts; do not reuse an earlier APK and label it current-source. The shared `core/data/src/test/resources/contract/amneziawg-arm64-version-floor.json` guard remains unchanged even when this gate passes. Its later change belongs to its existing owner decision.

### G7 — Combined review, hosted CI, and completion

Run independent `pr-reviewer` review on the implementation diff. Add async cancellation, unsafe Rust, and JNI review when the diff triggers the relevant repository rules. Resolve correctness findings without weakening gates. Record hosted workflow URLs, exact checked commit and conclusion after the implementation is pushed under separate authorization. Planning-branch CI does not satisfy this implementation gate.

Populate every evidence category only from its observed result. `device` must include both fresh emulator and required physical acceptance; `artifact` must refer to the exact rebuilt and tested binaries. If a required peer, device, or hosted run is unavailable, mark it required or blocked with its reason. Do not archive or close the portfolio task until the required categories are resolved through the task lifecycle.

## Execution record

- 2026-10-29, TRN-1793272189489544: inspected the locked boringtun 0.7.1 source and Go `b5928efb6ca19f0153958460c3d141f04abc5c2e`; resolved the vendor patch, timer sample/equality rules, typed authentication outcomes, carrier source identity and bounded cookie admission in `design.md`. The boringtun archive checksum matches the existing lockfile. Independent read-only review identified retry-reset, zero-range and expiry-boundary corrections; all were applied and the reviewer reported no remaining blocker to the contract commit or timer step. Task and strict OpenSpec validation passed. This completes the boundary study only; G1-G7 remain required.

- 2026-10-29, TRN-1793272215354505: added the opt-in timer policy inside the pinned boringtun backend and removed the outer AWG scheduler. All five range policies and persistent keepalive now use backend session events. The ordinary WireGuard constructor retains its existing policy. Tests reproduced the one-second retry defect, the padded-empty idle defect, and two key-generation refresh defects before their fixes. Final checks used Rust/Cargo 1.98.1: backend `--lib` 23 passed, zero failed/ignored; `ripdpi-warp-core` 150 passed, zero failed/ignored; workspace Clippy with all targets and `-D warnings` passed; both rustfmt checks, locked Cargo metadata, architecture health, shell syntax, and harness locked-command audit passed. The CI workspace test script now runs the backend suite explicitly. Independent protocol and async reviews found no remaining blocker after corrections. The root lockfile contains Cargo resolver normalization of compatible existing getrandom/Windows dependency edges; no registry package version was added or removed. G4-G7 traffic, device, artifact, and hosted evidence remains required. Authenticated zero-padding recognition moved forward from the empty-payload step because timer correctness depends on it; that step's full result/replay/malformed-payload contract is still open.

- 2026-10-29, TRN-1793272215891212: moved transport padding to the backend encryption boundary. The configured codec supplies one bounded policy for IP data, queued data, explicit empty packets, automatic confirmation, and timer keepalives. Timers still use logical plaintext lengths. CPA takes precedence over RT; when both are off, alignment follows the inner MTU. MTU 1280/1420 reaches the codec, and timer output uses the existing full packet capacity. The old automatic-confirmation test failed at 32 versus 332 bytes; a short-output regression also reproduced a panic before the capacity fix. Final pinned checks: backend 25 passed, core 152 passed, zero failed/ignored; workspace/all-target Clippy with `-D warnings`, formatting, and architecture health passed. A real loopback UDP tunnel delivered a 332-byte confirmation and 352-byte padded 20-byte IP payload. Independent review cleared the short-buffer correction. Host-peer and device wire evidence remains required.

- 2026-10-29, TRN-1793272216407538: added explicit backend receive outcomes for rejection, cookie traffic, handshake messages, valid empty/IP transport, and authenticated malformed transport. Removed the InvalidPacket/length heuristic from readiness and UDP-window learning. Malformed payload consumes replay state but does not update peer state or receive metrics. IPv4 IHL/length validation is a documented hardening exception for ordinary WireGuard. The prior zero-total-length IPv4 case failed before correction. Final pinned checks: backend 29 passed, core 153 passed, zero failed/ignored; workspace Clippy passed, then affected-package all-target Clippy passed after the last test additions; formatting and architecture health passed. Encrypted cases cover zero padding, nonzero malformed payload, IPv4/IPv6 length errors, wrong ephemeral key, corruption, replay, fresh responder confirmation, and valid ordinary WireGuard after rejection. Real UDP tests prove malformed/corrupt transport leaves the 500-byte window unchanged and authenticated empty transport can raise it. Independent review reported no remaining findings. Cookie admission and ambiguous candidate selection remain open in their owning steps.

- 2026-10-29, TRN-1793272216991110: connected DisableCookies to backend admission. Both flag states retain MAC1 and Noise authentication, with independent per-peer limits of ten expensive attempts and ten emitted challenges per second. UDP receives retain and filter the actual address/port; WSS uses a private per-connection identity. Invalid envelope MAC2 is represented as absent for backend load handling. Final pinned checks: backend 33 passed, core 154 passed, zero failed/ignored; workspace/all-target Clippy, both formatting checks, and architecture health passed. Tests cover both handshake directions, both flag/load states, invalid MAC1, forged Noise, incoming-cookie corruption, replay-independent transport, budget exhaustion/reset, valid-MAC2 budget enforcement, UDP address/port and IPv6 binding, connection identity changes, and foreign UDP source rejection. A negative control that ignored DisableCookies failed at the expected challenge/handshake outcome. Protocol and async/protection reviews found no remaining production blocker; the new source test also aborts and joins its receiver. Candidate conversion-state commit is still owned by the next step.

## Historical baseline — not implementation acceptance

- Source `941f92ada478ec202b7b335d0f0b487deeee763b`: research observed `cargo test -p ripdpi-warp-core --lib --locked`, 151 passed, zero failed, zero ignored.
- The checked-in historical emulator observation reports 96/96 at `ba228c17c7cfb1f90a09f6af03a1c5837e1a100c`. It is not a fresh run, a physical receipt, or proof of the new feature validators.
- Upstream research passed replay, ratelimiter, and tai64n packages but failed the device ping test. This is a peer test result, not a RIPDPI implementation result or a proved root cause.

## Planning-only checks

Validate these documents with `./taskctl generate-board`, `./taskctl validate`, `./taskctl openspec cli validate awg31-behavior-parity --strict`, and `git diff --check`. Use `./taskctl verify TRN-1793271658511991` as a completion check: it must reject completion while the 16 execution steps remain open. This expected rejection is not a planning-validation failure. These checks establish a consistent plan only. Keep all execution checkboxes open.
