## Context

Task ID: `TRN-1793271658511991`. Baseline: RIPDPI `941f92ada478ec202b7b335d0f0b487deeee763b`. This document records source findings and an implementation plan, not completed fixes.

RIPDPI uses its own Rust AWG envelope around boringtun 0.7.1. Updating a Go dependency cannot fix this implementation. The current code already supports S1-S4, H1-H4 ranges, bounded CPS and junk, Header Protection, random trailers, content padding for ordinary data, and persistent keepalive ranges. All nine v3 fields can pass through Kotlin configuration and storage. The Header Protection key has a separate encrypted credential path. Preserve these capabilities.

Reference behavior is pinned to [amneziawg-go v3.1.20260828](https://github.com/amnezia-vpn/amneziawg-go/tree/b5928efb6ca19f0153958460c3d141f04abc5c2e). Compare the [3.1 announcement](https://amnezia.org/blog/amneziawg-3-1-is-here), [parameter reference](https://docs.amnezia.org/documentation/amnezia-wg/), and pinned source. A product version, container `latest`, or backend version string does not identify the patch set.

### Findings and disposition

Paths below are relative to the repository root. Line numbers are baseline observations and can move during implementation.

| ID | Evidence and current behavior | Required disposition |
|---|---|---|
| F01 | `native/rust/crates/ripdpi-warp-core/src/awg_v3.rs:77`: RekeyTimeout, RejectAfterTime, KeepaliveTimeout, and MaxHandshakeAttempts are validated then discarded. | Carry the values into a coherent backend timer policy and test their observable boundaries. |
| F02 | `src/wireguard/tunnel.rs:259` and `src/wireguard/awg_timers.rs:9` in that crate: RekeyAfterTime adds early send-side rekey, but backend timing and a local five-second guard still constrain behavior. | Cover both session roles, standard timer ceilings, retries, key expiry, idle transitions, and range resampling. |
| F03 | `src/wireguard/tunnel.rs:377`: direct empty encapsulation bypasses custom padding; automatic response keepalives also bypass it. With S4 = 12, an empty transport can remain 44 bytes. | Route all transport through one padding policy, including default 16-byte alignment when custom modes are off. |
| F04 | `src/wireguard/tunnel.rs:470`: authenticated InvalidPacket is used to recognize padded keepalives, but the result can also represent malformed nonzero IP data. | Add a precise authenticated empty-payload outcome; reject malformed data and replays. |
| F05 | `src/awg_v3.rs` omits DisableCookies from runtime parameters; `src/wireguard/tunnel.rs:440` calls backend decapsulation without a source address. The backend can still enter its load path. | Define and implement both flag states for initiation, response, and cookie handling; preserve authentication and bounded work. |
| F06 | `src/awg_v3.rs:142` chooses the first plausible packet type; `src/amneziawg.rs:536` does not retry classification after authentication fails. Different S offsets, wide H ranges, and trailers can cause a false handshake match. | Authenticate bounded candidates without committing failed-candidate state; deliver valid transport once. |
| F07 | `src/awg_v3.rs` learns a UDP window from authenticated traffic and caps it at supported payload plus transport overhead. | Retain the authentication gate and bound; test effective MTU, endpoint/session reset, replay, and all carrier overhead. Do not describe this heuristic as PMTUD. |
| F08 | `AmneziaWgEditorState.kt` converts malformed keepalive text to a scalar or off. Configuration parsing rejects some forms; zero-based ranges can become off. | Use one strict grammar across editor, URI, and config import. Reject malformed and 0-N input; preserve existing stored off values. |
| F09 | `AmneziaWgEditorActivation.kt:23` does not include all v3 validation in readiness. HP with S1 = 8 can look ready before later rejection. | Validate v3 constraints at input, save, and activation readiness with actionable errors. |
| F10 | The editor exposes legacy knobs but keeps imported `form.v3` largely hidden. Accepted settings can appear supported while runtime ignores them. | Expose v3 editing and actual runtime support; preserve every supported value through round trips. |
| F11 | `AwgCohortCatalog.kt:185` clears v3 settings when a cohort preset is selected. Import avoids automatic v3 preset matching. | Keep import preservation and make intentional preset replacement explicit, including its secret and server-coordination effects. |
| F12 | RIPDPI uses bounded u16 parameter ranges, stricter H constraints and initiation/response size checks, and inclusive junk-size bounds; Go has wider numeric parsing and an exclusive junk upper bound. | Document the portable subset and boundary behavior. Do not silently broaden ranges, rewrite server parameters, or weaken checks. |
| F13 | CPS accepts bounded b/t/r/rc/rd syntax, lowercase hex, no inter-tag whitespace, positive lengths, at most 1280 bytes and 64 tags. Upstream also has undocumented d/ds/dz forms. | Preserve the strict subset with negative and boundary tests; unsupported tags get explicit errors. Adding undocumented tags is not required. |
| F14 | `tests/standalone_awg_interop.rs:147` uses legacy S values and v3 defaults even with a Go 3.1 peer. | Add real 5.x/v3 feature profiles while preserving the baseline profile. |
| F15 | `scripts/fixtures/amneziawg-peer/wire_observation.go` collects sizes for the Android fixture, but `scripts/tests/validate-android-awg-interop.py:64` mainly asserts configured S values and absence of clear headers. | Assert actual padding/trailer distributions and lengths, idle behavior, timers, cookies, and negative controls. A successful transfer alone cannot pass these features. |
| F16 | Existing emulator evidence is tied to an earlier revision; the fixture exercises the native engine rather than full production VpnService and direct physical transport. | Run current-source emulator and separately authorized physical arm64/artifact acceptance; keep the shared deployment guard unchanged. |

The nine v3 settings are HeaderProtectionKey, ContentPaddingAddition, RandomTrailers, DisableCookies, RekeyAfterTime, RekeyTimeout, RejectAfterTime, KeepaliveTimeout, and MaxHandshakeAttempts. Persistent keepalive is a separate scalar/range setting.

### Upstream differences to isolate

- Go `v3.1.20260814`, SHA `1b86b2ae0e493e7ea93f8c1a0f0cb6735b1551f1`, is used by several inspected client source pins. It precedes the August 28 fixes. Keep it as a compatibility peer, not the sole oracle.
- Go `v3.1.20260828`, SHA `b5928efb6ca19f0153958460c3d141f04abc5c2e`, is the primary behavior reference. Its [timer code](https://github.com/amnezia-vpn/amneziawg-go/blob/b5928efb6ca19f0153958460c3d141f04abc5c2e/device/timers.go) has a reported duration race; its configuration paths have invalid-length and junk-range hazards. Do not copy those hazards.
- Linux SHA `4569c4c67f3a57414969260cafbbd04694fbaae0` includes the CPS/junk trailer fix. Its [send expiry check](https://github.com/amnezia-vpn/amneziawg-linux-kernel-module/blob/4569c4c67f3a57414969260cafbbd04694fbaae0/src/send.c#L464-L475) still uses a constant despite configurable RejectAfterTime. Record this peer limitation separately; do not change RIPDPI's intended timer contract to match a defect.
- The research run of upstream `go test ./device ./replay ./ratelimiter ./tai64n` passed the last three packages but failed the device ping test. The suspected startup S4 issue was not independently proved. Isolate this failure before using that test as an interoperability gate; do not skip it and claim success.
- Older audits predate 3.x. Neither this plan nor payload tests establish a new security audit or measured resistance to a particular DPI system.

## Goals / Non-Goals

- Goal: close F01-F16 with explicit behavior, safe compatibility limits, and evidence that fails when a feature is absent.
- Goal: preserve existing AWG/WireGuard profiles, secret isolation, replay protection, offline use, and VPN socket protection.
- Non-goal: replace cryptographic primitives, deploy a server, migrate external users, add a required service, promise DPI bypass, or lift the shared arm64 deployment guard.
- Non-goal: production implementation in this planning commit.

## Decisions

1. Keep the Rust engine and its existing configuration boundaries. Do not switch to Go or create a second tunnel stack to gain timers.
2. Add a minimal maintained backend adaptation for timer policy, cookie policy, and authenticated payload outcomes. First map the boringtun call/state boundaries and record the exact extension mechanism and ownership. Prefer an existing supported hook; if none can express the full policy, use an explicitly pinned minimal patch through the repository's dependency policy. Do not simulate complete support with an outer timer while fixed inner timers remain active. A new production dependency or changed public contract requires its normal approval and an updated design before that step proceeds.
3. The backend decision is a prerequisite for timer, cookie, empty-payload, and candidate-classification implementation. The initial contract study has a concrete exit: every required transition and failure result has an owner and a reachable test point. If the proposed boundary cannot preserve authentication and replay state, revise this design rather than weaken acceptance.
4. Use authenticated candidate selection with bounded work. Merely trying transport first is insufficient; neither should imported S/H values be normalized to hide ambiguous classification.
5. Use one transport padding path: custom content addition, otherwise random-trailer padding, otherwise standard alignment. Preserve distinct handshake trailer rules and never apply them to CPS/junk. Include automatic and explicit empty packets.
6. Share input parsing in the lowest existing owning model/runtime-state layer. Keep source errors visible in the editor. Scalar zero and 0-0 mean off; positive ordered ranges include equal endpoints; 0-N with N greater than zero is invalid. This validation correction is deliberate and must have release documentation.
7. Preserve strict bounded CPS/numeric/header behavior. These are documented compatibility limits, not reasons to emulate unsafe upstream allocation or add undocumented syntax.
8. Record actual support during staged delivery. A field that is only stored must not be presented as applied. At final acceptance all nine supported settings must work; temporary warnings cannot satisfy the runtime requirements.
9. Peer observations must measure configured effects. Use deterministic clocks for exact timer boundaries and controlled packet traces for integration behavior; avoid relying only on elapsed sleeps or random variation from a single sample. Validator tests must reject an observation with the feature removed or altered.

## Contracts and ownership

| Lane | Owned implementation and tests | Shared boundary |
|---|---|---|
| Native runtime | `native/rust/crates/ripdpi-warp-core` envelope, backend adaptation, timers, host tests | Serialized backend dependency change; configuration names and authenticated result contract |
| Profile and UI | `core/data/model`, `core/data/runtime-state`, `core/data`, `core/engine-api`, `core/service`, AWG editor under `app` | Existing JSON fields, secret references, readiness, all locale resources |
| Acceptance | `testing/awg-interop`, `scripts/fixtures/amneziawg-peer`, `scripts/tests`, owning CI workflow and wire-semantics documentation | Versioned observation schema and validators; exact source/artifact receipts |

Future parallel writers need separate worktrees and explicit file ownership before they start. One integration owner controls `native/rust/Cargo.lock`, `gradle/libs.versions.toml`, any protobuf/JNI/schema constants, all locale resource sets, golden fixtures, and shared policy fixtures. No such contract change is assumed. The deployment guard file has no writer in this change.

Preserve `AmneziaWgV3Parameters`, `AwgActivationRequest`, `AmneziaWgRuntimeConfigResolver`, native JSON names, and credential references. A source-address requirement in the cookie path must preserve VPN protection and privacy. Read the VPN lifecycle, protect invariant, privacy, concurrency, and Rust review rules before affected implementation work. New diagnostics must not log Header Protection keys, private keys, PSKs, or full sensitive profiles.

## Risks / Trade-offs

- Backend adaptation can fork timer/session behavior. Keep the patch narrow, pinned, and covered by initiator/responder state tests and upstream-peer tests.
- Candidate fallback can increase unauthenticated work or mutate state twice. Bound candidates by packet types, test failed-candidate side effects, and preserve rate limits.
- Correct padding changes packet lengths and MTU pressure. Assert payload, prefix, AEAD, trailer, and outer-carrier bounds at MTU 1280 and 1420 and at the supported maximum.
- Cookie behavior changes under load. Test MAC1 failure, both handshake directions, both flag values, and resource bounds; do not disable all load protection.
- Strict keepalive parsing rejects formerly tolerated text. Explain the error and retain valid stored values. Do not infer the original text from an already normalized database value.
- A preset can break server agreement. Present its replacement effect before application; do not automatically delete v3 fields on import.
- Historical or different-source device success can hide regressions. Require source and artifact hashes in fresh evidence and keep environment scopes separate.

## Migration Plan

1. Publish the plan in backlog. No implementation or runtime evidence is marked complete.
2. Resolve and record the backend boundary, then add independent regressions before each fix. Native packet fixes and profile input work can proceed independently after boundary ownership is assigned.
3. Complete runtime behavior, UI/round-trip handling, and compatibility notes. Keep persisted JSON and secret formats unchanged where possible; no Room migration is currently required. If a migration becomes necessary, revise the spec and add backward/forward fixtures before implementing it.
4. Extend pinned host peers and wire validators, then run current-source Android emulator acceptance. Keep baseline profiles alongside HP on/off, fixed/ranged H, unequal S, CPA/RT modes, idle, retry/rekey/expiry, malformed input, replay, loss, load, restart, and endpoint-change cases.
5. Run separately authorized physical arm64 acceptance with the production service and exact rebuilt artifacts. Obtain hosted CI and exact-artifact receipts. Preserve the shared deployment guard and route its later removal through its existing owner decision.
6. Roll back implementation commits and binaries together if acceptance fails. Do not clear saved profiles or secrets. Document the newly rejected input grammar so rollback is not presented as restoring source text already lost by older parsing.

## Validation strategy

The execution tasks and `verification.md` are the authoritative gate mapping. Extend existing tests before adding new suites. Native unit coverage belongs in `awg_v3_tests.rs`, `awg_timers.rs` and the owning tunnel tests; interoperability belongs in `standalone_awg_interop.rs`. Kotlin coverage belongs in existing parser, editor, resolver, repository, and serialization suites.

Required implementation gates include `cargo test -p ripdpi-warp-core --locked` from `native/rust`, affected Kotlin module tests, full static analysis, locale lint for changed strings, independent review, and the host/emulator/physical acceptance receipts. Review uses `pr-reviewer`; add async, unsafe, and JNI specialist review only when the actual diff touches those areas. Do not bless or weaken fixtures or baselines to obtain a pass.

Research baseline only: `cargo test -p ripdpi-warp-core --lib --locked` observed 151 passed, zero failed, zero ignored at the baseline SHA. The historical emulator record reports 96/96 at `ba228c17c7cfb1f90a09f6af03a1c5837e1a100c`. Neither result proves the future requirements or physical acceptance. Planning validation is separate from every implementation gate.
