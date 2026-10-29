# TRN-1793271658511991: Complete AmneziaWG 3.1 behavior and verification

## Objective

Close findings F01-F16 from `design.md` and satisfy the linked delta specification. Execution status is recorded below. Step checks do not replace the combined acceptance gates.

## Ownership

Native paths below use `N = native/rust/crates/ripdpi-warp-core`. Editor paths use `U = app/src/main/kotlin/com/poyka/ripdpi/ui/screens/awg`. Peer paths use `P = scripts/fixtures/amneziawg-peer`. All are repository-relative. The design assigns module lanes and serialized shared files. Do not overlap writers on the native tunnel or locale files.

## Stage order

1. Resolve the backend boundary in TRN-1793272189489544 before native state-machine changes. Record concrete transition semantics and the supported extension mechanism in the design.
2. Implement native behavior and regression tests in TRN-1793272215354505 through TRN-1793272218210665. Profile input and editor work in TRN-1793272218937903 through TRN-1793272220215141 can run independently with separate ownership. Complete compatibility documentation in TRN-1793272220792888 with the final behavior.
3. Complete host peers and feature validators in TRN-1793272221414509 and TRN-1793272221979823 before device acceptance. Harness development can start earlier; passing acceptance requires the combined implementation.
4. Complete emulator, physical/artifact, and final gates in the last three steps. A missing physical device or failed upstream peer remains a reported blocker, not a waived result.

## Execution

- [x] TRN-1793272189489544 Define the boringtun extension boundary and timer-cookie state contracts with independent regression oracles #research !high @item:TRN-1793271658511991
- [x] TRN-1793272215354505 Apply configurable rekey retry expiry idle and attempt limits with deterministic role and boundary tests #bug !high @item:TRN-1793271658511991
- [x] TRN-1793272215891212 Pad all outgoing transport including automatic and persistent keepalives with independent length and MTU tests #bug !high @item:TRN-1793271658511991
- [ ] TRN-1793272216407538 Distinguish authenticated padded keepalives from malformed payloads and verify replay-safe result handling #bug !high @item:TRN-1793271658511991
- [ ] TRN-1793272216991110 Apply DisableCookies through backend load handling and verify both flag states authentication and work bounds #bug !high @item:TRN-1793271658511991
- [ ] TRN-1793272217604418 Authenticate ambiguous receive candidates and verify valid transport collision fallback without duplicate delivery #bug !high @item:TRN-1793271658511991
- [ ] TRN-1793272218210665 Retain bounded authenticated UDP-window learning and verify MTU endpoint reset and hostile datagrams #feature !high @item:TRN-1793271658511991
- [ ] TRN-1793272218937903 Unify keepalive input grammar across editor config and URI with invalid-input and stored-value regressions #bug !high @item:TRN-1793271658511991
- [ ] TRN-1793272219644936 Validate Header Protection and v3 constraints before save and activation readiness with editor regressions #bug !high @item:TRN-1793271658511991
- [ ] TRN-1793272220215141 Expose supported v3 settings and explicit cohort replacement with secret-safe round trips and locale checks #feature !high @item:TRN-1793271658511991
- [ ] TRN-1793272220792888 Document and test numeric header junk and CPS compatibility limits without copying upstream unsafe behavior #chore !high @item:TRN-1793271658511991
- [ ] TRN-1793272221414509 Add pinned Go and Linux host peer profiles covering real v3 behavior and isolate upstream startup failures #feature !high @item:TRN-1793271658511991
- [ ] TRN-1793272221979823 Make wire validators assert padding trailers idle timers and cookie behavior with missing-feature negative controls #feature !high @item:TRN-1793271658511991
- [ ] TRN-1793272222631098 Run current-source pooled Android arm64 emulator feature and payload acceptance and retain scoped receipts #feature !high @item:TRN-1793271658511991
- [ ] TRN-1793272223181197 Run authorized physical arm64 production-service and exact-artifact acceptance while preserving the deployment guard #feature !high @item:TRN-1793271658511991
- [ ] TRN-1793272223722013 Complete affected-module static contract and independent-review gates and record hosted CI and final evidence #chore !high @item:TRN-1793271658511991

## Step contracts

| Execution step | Findings and owned paths | Observable completion and named gates |
|---|---|---|
| TRN-1793272189489544 | F01-F06; `N/src/wireguard`, `N/Cargo.toml`, design; dependency files only through the serialized owner | Map backend timers, role/session start, retry reset, RejectAfterTime high-bound expiry, cookie load state, empty authenticated payload, and failed-candidate state. Specify units/defaults/bounds and independent expected outcomes. Resolve the extension mechanism before dependent edits; obtain approval if a new production dependency is necessary. Review the contract against pinned Go and the existing backend. |
| TRN-1793272215354505 | F01-F02; `N/src/awg_v3.rs`, `N/src/awg_profile.rs`, `N/src/config.rs`, `N/src/amneziawg_runtime.rs`, `N/src/wireguard` | Apply all timer fields for initiator and responder, retry exhaustion/reset, expiry, idle, and persistent-range resampling. Deterministic tests cross low/high/default boundaries, including rekey above and below 120 seconds and retries below the former five-second guard. Gate G1; later G4-G5 confirm actual traffic. |
| TRN-1793272215891212 | F03; `N/src/awg_v3.rs`, `N/src/wireguard/tunnel.rs`, owning tests | Verify CPA-only, RT-only, both-on precedence, both-off alignment, normal data, explicit empty transport, and automatic keepalive. Independently derive lengths and carrier bounds. Gate G1; wire observations under G4-G5. |
| TRN-1793272216407538 | F04; `N/src/wireguard/tunnel.rs` and the approved backend boundary | Distinguish authenticated all-zero padding from nonzero malformed IPv4/IPv6, wrong-key, corrupted, and replayed packets. Assert payload delivery, keepalive metrics, and window changes. Gate G1 and peer negative cases in G4. |
| TRN-1793272216991110 | F05; `N/src/awg_v3.rs`, runtime configuration and backend decapsulation/load paths | Test both DisableCookies values below/above load thresholds for initiation, response, and incoming cookies. Invalid MAC1 and AEAD still fail; resource limits hold. Pin any source-address handling and keep privacy/VPN invariants. Gate G1 and controlled load cases in G4. |
| TRN-1793272217604418 | F06; `N/src/awg_v3.rs`, `N/src/amneziawg.rs`, tunnel boundary | Construct an independently authenticated transport with unequal S and a false H1/H2 candidate under RT. Verify delivery once, replay rejection, bounded candidates, and no state mutation on failed candidates. Gate G1 and G4. |
| TRN-1793272218210665 | F07; `N/src/awg_v3.rs`, tunnel tests | Preserve 500-byte initial window and bounded authenticated learning. Specify endpoint/session resets; test spoofed 65 KB, replay, MTU 1280/1420, maximum payload, and overhead accounting. Gate G1 and G4-G5. |
| TRN-1793272218937903 | F08; `core/data/model`, `core/data/runtime-state`, parser tests in `core/data`, `U/AmneziaWgEditorState.kt` | One grammar accepts 0, 0-0, positive scalar/equal/ordered ranges through 65535; rejects reversed, malformed, extra separators, 0-N, negative, and overflow input. Extend WireGuardConfParserTest, AmneziaWgUriCodecTest, and AmneziaWgEditorStateTest. Preserve stored off values. Gate G2. |
| TRN-1793272219644936 | F09; `U/AmneziaWgEditorActivation.kt`, editor state/view model, v3 validation | HP with any S below 12 cannot be saved or shown as ready. Display validation without secrets; test all prefix fields and valid boundaries. Extend editor/view-model and runtime resolver tests. Gate G2 and UI inspection in G5. |
| TRN-1793272220215141 | F10-F11; `U`, `core/data/runtime-state/src/main/kotlin/com/poyka/ripdpi/data/awg/AwgCohortCatalog.kt`, model/storage/resolver/DTO tests, locale resources | Expose nine v3 fields and actual support. Test import-edit-save-reload-activate/export round trips, secret redaction and storage, old profiles, and explicit preset replacement/cancel. Extend AmneziaWgWgQuickConfTest, AwgProfileRepositoryRoomTest, RipDpiAmneziaWgConfigSerializationTest, and editor/resolver tests. Gates G2-G3 plus rendered editor inspection in G5. |
| TRN-1793272220792888 | F12-F13; `N` parsers/tests, Kotlin parser tests, `docs/native/amneziawg-3x-wire-semantics.md` | Document numeric bounds, H and message-size constraints, inclusive junk sizes, supported CPS grammar and limits. Test boundaries and malformed lengths without unbounded allocation. Correct obsolete cookie/timer limitations after fixes. Gates G1-G3; no unsupported claim of full Go parser equivalence. |
| TRN-1793272221414509 | F14; `N/tests/standalone_awg_interop.rs`, `P`, `scripts/tests/run-standalone-awg-interop.py`, owning CI workflow | Preserve baseline and add actual v3 profiles for Go August 14/August 28 and the pinned Linux revision. Isolate Linux kernel testing in a disposable VM/CI environment. Resolve or report upstream startup failures; distinguish known peer limitations without silently skipping cases. Gate G4 records exact versions and results. |
| TRN-1793272221979823 | F15; `P/wire_observation.go`, its Go tests, Android validator and Python tests, `testing/awg-interop` | Validate HP, each packet type's trailer bounds, data/empty padding lengths, timing events, cookie load behavior, and loss/restart outcomes. Keep CPS/junk free of unintended trailers. Tests remove or alter each claimed feature and require validator failure. Gates G4-G5. Version the observation schema if its contract changes. |
| TRN-1793272222631098 | F16; pooled Android runner, `testing/awg-interop`, evidence owned by these tools | Run current-source arm64 baseline and 5.x/v3 profiles at MTU 1280/1420 with restarts, IPv4/IPv6 TCP/UDP/DNS, exact hashes, zero unexplained payload loss, wrong-HP-key rejection, and feature assertions. Inspect v3 editor normal/error/preset states and locale layouts on a leased emulator. Gate G5; record emulator scope explicitly. |
| TRN-1793272223181197 | F16; production-service acceptance integration under `test-lab/acceptance`, exact rebuilt APK/native artifacts, evidence | Use an explicitly authorized owned physical arm64 device, lock and installation receipt, reachable private-LAN peer, and full VpnService path. Verify socket protection, routing, DNS, restart/handover and no unintended bypass along with AWG features. Identify app/native SHA-256, source, device and peer; USB test-only bridge is not direct transport evidence. Gate G6. Do not modify the shared deployment guard. |
| TRN-1793272223722013 | F01-F16; affected tests/CI and `verification.md` | Run G1-G7 on the combined implementation, resolve reviews, attach exact-source hosted CI/device/artifact receipts, and map every requirement. Keep missing categories required or blocked. Never treat planning validation as implementation acceptance. |

## Verification

Gate definitions, commands, requirement mapping, and separate evidence states are in `verification.md`. Each implementation step includes its own regression gate; the final step consolidates results rather than replacing those checks. Follow the repository task lifecycle only after all required evidence is observed.
