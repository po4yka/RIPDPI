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

- 2026-10-29, TRN-1793272217604418: added bounded authenticated candidate selection and deferred codec context commit. Both tunnel and endpoint probe use the same receive boundary. Actual encrypted tests cover false handshake layouts before valid transport/response, provisional cookie fallback, replay, and terminal malformed transport. A negative control with first-layout-only decoding failed as expected. Probe regressions reject cookie-only readiness and prevent a ready UDP send after expiry. Final pinned checks: core 159 passed, zero failed/ignored; workspace/all-target Clippy with `-D warnings`, formatting, and architecture health passed. Backend sources were unchanged; the preceding 33-test result remains valid. Independent protocol and async reviews found no remaining issue after explicit deadline guards were added. Host/device collision evidence remains required.

- 2026-10-29, TRN-1793272218210665: specified UDP-window lifetime and extended existing tests without changing runtime behavior. MTU 1280/1420 and S4 0/12/200 cases prove the 500-byte initial bound, no learning from a spoofed 65 KB decode, exact 16-byte receive-tag subtraction, the MTU/framing ceiling, maximum payload, and fresh-codec reset. The real UDP test proves rekey retains the window for malformed, corrupted, and accepted empty-packet cases. It uses a permitted zero-low retry range to avoid waiting for the default minimum spacing. Core tests passed: 159, zero failed/ignored. Existing encrypted replay and foreign-endpoint tests remain in this passing suite. G4-G5 peer/device observations remain required.

- 2026-10-29, TRN-1793272218937903: one model parser now defines PersistentKeepalive input for `.conf`, URI, and editor activation. It accepts 0/0-0, positive scalar/equal/ordered ranges through 65535; invalid input and 0-N fail without coercion. Existing absent, `.conf off`, and stored zero values retain their meaning. A shared invalid-input corpus runs through all three paths. The old `.conf` and URI behavior failed on 0-1 before the fix. Review found and corrected import retaining old raw keepalive when a new profile had off or no value. Final XML results: model 59, runtime-state 363, data 975, app 3214 tests passed; zero failures/errors/skips. The app suite was rerun after interruption because no completed app report existed. It used the supported prebuilt Xray path after the current verifier accepted its provenance and digest; repository-owned native prerequisites built from this worktree. Independent review reported no remaining finding. Full static, visual, peer, and device gates remain assigned to their later steps.

- 2026-10-29, TRN-1793272219644936: editor readiness and activation projection now use the same v3-aware obfuscation checks. Each HP prefix has an actionable field error in all ten locales. State and ViewModel regressions reproduced the old ready/save behavior; coverage includes all S1-S4 values 0/11, valid 12/13, explicit clearing, all-zero HP key off, no persisted profile or service activation on invalid input, and resolver rejection. Independent review found and cleared the raw-empty/retained-form-value gap. Final stable-tree checks: app 3216 and service 1998 tests passed, zero failures/errors/skips; app and service Android lint passed with zero errors. App tests/lint were repeated after the last raw-input correction; unchanged service results were retained. Visual inspection remains part of G5. The existing `vpn-awg-editor.html` field-error pattern and shared RDS text field are retained.

- 2026-10-29, TRN-1793272220215141: exposed all nine v3 fields with range units, localized help, and the existing secret reveal gate for HP. Legacy preset selection now keeps v3 values until explicit confirmation; cancel keeps the draft, confirmation clears all old raw obfuscation values. Tests reproduced immediate data loss, stale I-field text, and raw-secret diagnostic output before the fix. State, Compose interaction, ViewModel, Room storage, and native JSON tests cover editing, save/reload/activation, relocking, and secret exclusion from Room JSON. Existing wg-quick export tests remain in the passing data suite. Final results: app 3222, data 975, engine-api 70 tests passed, zero failures/errors/skips; app/service lint and app detekt passed. Architecture health has zero new or worsened indicators. The editor dispatch was split by identity, legacy obfuscation, and v3 fields to remove its complexity suppression. Independent review found no remaining issue. Rendered locale/editor acceptance remains assigned to G5; no golden was changed.

- 2026-10-29, TRN-1793272220792888: extended existing Rust and Kotlin parser tests at all six v3 range boundaries, including 0-0, 0-65535, 65535-65535, overflow, extra separators, signs, and non-ASCII digits. Actual conf/URI import paths reject malformed ranges and unsupported CPS tags. CPS tests cover exact 1280-byte literals/mixed programs, 1281-byte output, text above 4096 bytes, unsupported d/ds/dz, whitespace, and count overflow. The wire document now separates pinned reference behavior from current runtime behavior and documents the portable input subset. Detekt found a ReturnCount violation in the step-8 URI helper; a nullable mapping preserves its behavior and removes the violation. Final results: core Rust 159 passed, zero failed/ignored; no doctests exist; workspace/all-target Clippy and formatting passed. Kotlin runtime-state 363 and data 977 passed, zero failures/errors/skips; both module detekt tasks passed. Architecture health has no new or worsened indicators. Review clarified outgoing window growth and selected provisional cookie context; no production correctness finding remains. G4-G7 remain required.

- 2026-10-29, TRN-1793272221414509, Go slice only (step remains open): added seven explicit profiles for both immutable Go revisions, with CPA-only, RT-only, combined precedence, aligned transport, equal/unequal S, and fixed/ranged H coverage. Each of 28 profile/version/MTU cases passed four real IPv4/IPv6 TCP/UDP sessions and runtime shutdown. The runner records source plus candidate patch, binary digests, upstream revision/checksum, build results, case results, and separate logs; CI retains JSON/log artifacts. A deterministic test proves the upstream initial TUN read captures S4 before IpcSet (offset 16 instead of 28 for S4=12). The fixture returns one empty read after configuration, so upstream reloads S4 before consuming its first packet; Close also releases the gate. This is a fixture startup adjustment, not an upstream patch. Both pinned peer test suites, six Python runner tests, feature-enabled core Clippy, formatting, actionlint, architecture health, and independent protocol/async review passed. Review corrections preserve download subprocess cleanup, separate diagnostic stderr from JSON stdout, and record peer process failures. Final local receipt: `build/reports/awg31/step12-go-reviewed/results.json`; Linux, semantic wire validators, and devices remain required. A new owned Linux VM has matching 6.8.0-142 headers; pinned module/tools build successfully, but kernel payload acceptance has not run yet.

- 2026-10-29, TRN-1793272221414509, Linux slice: kernel SHA `4569c4c67f3a57414969260cafbbd04694fbaae0` and tools SHA `ee0f0a9aa34ff0a0da4b3433b9512781cfe02843` built without source changes on the owned VM kernel `6.8.0-142-generic`. All 14 profile/MTU cases passed four real IPv4/IPv6 TCP/UDP sessions, configuration readback, authenticated kernel counters, and runtime shutdown. The runner checks source archive digests, binds the ownership receipt to guest machine-id hash/hostname, refuses an already-loaded module, builds unprivileged, drops privileges for payload processes, and records artifact hashes and PCAP. The first run correctly failed at private-key readback because the kernel clamps X25519 scalars; the comparison now permits only that normalization. Five Python contract tests passed, including all-nine-field readback negatives and foreign-instance receipt rejection. Go fixture tests and the two-MTU 5.x host regression passed after the new OS echo mode. Independent review cleared process-group cleanup, independent cleanup attempts, final-report persistence, and response-shape checks. Observed final cleanup: no test namespaces remain and the owned module is unloaded. Local receipts are under the owned VM output `kernel-matrix-2/results.json`; host command logs are under `build/reports/awg31/`. Step 12 is complete; step 13 must still prove feature-specific wire behavior, and G5-G7 remain required. The documented Linux fixed age-expiry limitation is not treated as a RIPDPI requirement.

- 2026-10-29, TRN-1793272221979823, padding evidence slice (step remains open): all 56 pinned Go/profile/scenario/MTU cases passed four sessions, and the final independent validator accepted all saved observations. Wire schema 2 adds bounded packet events, full length histograms, successful TUN IP lengths, post-AEAD/replay keepalive counts, and redacted peer counters. Two 17-byte UDP requests per session supply the 45-byte IP length oracle for CPA, RT, precedence, and alignment. Semantic negatives preserve matching counters while removing each claimed padding behavior. Review found that all-zero RT transport samples were accepted; both directions now require observed padding, and the regression failed before correction. This is aggregate length evidence, not per-counter plaintext association. Fifty Python tests, both pinned Go fixture suites, workspace/all-target and feature-enabled Clippy, rustfmt, actionlint, architecture health, and independent protocol/async reviews passed. Completion output is drained while the peer exits, with a real large-output child regression. Local receipt: `build/reports/awg31/step13-padding-matrix/results.json`; the final validator rechecked all 56 cases without rerunning unchanged traffic code. Timer, cookie, loss, and device evidence remains required; step 13 is not complete.

- 2026-10-30, TRN-1793272221979823, timer evidence slice (step remains open): six real timer scenarios passed for both pinned Go peers and both MTUs, for 24 single-session cases. They prove persistent interval bounds, two passive keepalives after two data events, one-response loss/retry, max-attempt exhaustion followed by at least 3.5 seconds of silence, age rekey with new receiver index and accepted payload, and upper-bound key expiry with a timed failed UDP probe plus fresh-key recovery. The expiry association remains open during rejection, so later queued replies do not produce unrelated ICMP. Explicit dropped-response events, monotonic timestamps, complete histograms, and authenticated counts form the oracle. The first test assumptions omitted the second passive keepalive and minimum initiation spacing; these were corrected from the existing contract. A measured 1.501-second retry exposed an omitted 250 ms poll allowance; the final 1.75-second ceiling includes 1 second, less than 333 ms jitter, one poll, and 167 ms scheduling margin. Exhaustion observes 7.5 seconds and retains its silence requirement, including a two-times-1.75-second boundary test. Exact lower/upper expiry boundaries and random resampling remain backend-unit evidence. Final receipts: 16 unchanged cases revalidated from `step13-timer-matrix`, four expiry cases from `step13-expiry-final`, and four exhaustion cases from `step13-exhaust-final`, under `build/reports/awg31/`; original failed receipts remain intact. Fifty-five Python tests, both pinned Go fixture suites, feature-enabled all-target Clippy, rustfmt, architecture health, strict task/OpenSpec checks, and protocol/async review passed. Eight baseline/CPA payload and small-datagram regression cases passed after the fixture changes. Cookie load and device evidence remains required.

## Historical baseline — not implementation acceptance

- Source `941f92ada478ec202b7b335d0f0b487deeee763b`: research observed `cargo test -p ripdpi-warp-core --lib --locked`, 151 passed, zero failed, zero ignored.
- The checked-in historical emulator observation reports 96/96 at `ba228c17c7cfb1f90a09f6af03a1c5837e1a100c`. It is not a fresh run, a physical receipt, or proof of the new feature validators.
- Upstream research passed replay, ratelimiter, and tai64n packages but failed the device ping test. This is a peer test result, not a RIPDPI implementation result or a proved root cause.

## Planning-only checks

Validate these documents with `./taskctl generate-board`, `./taskctl validate`, `./taskctl openspec cli validate awg31-behavior-parity --strict`, and `git diff --check`. Use `./taskctl verify TRN-1793271658511991` as a completion check: it must reject completion while the 16 execution steps remain open. This expected rejection is not a planning-validation failure. These checks establish a consistent plan only. Keep all execution checkboxes open.
