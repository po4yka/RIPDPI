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
| F16 | Existing emulator evidence is tied to an earlier revision; the fixture exercises the native engine rather than full production VpnService and direct physical transport. | Run current-source emulator, editor and exact tested-artifact acceptance. Physical/private-LAN acceptance is excluded by the user decision on 2026-10-30; keep the shared deployment guard unchanged. |

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
- Non-goal: physical-device or private-LAN acceptance, production VpnService transport acceptance, or physical network handover proof, as requested by the user on 2026-10-30.

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

### Resolved backend boundary (execution step TRN-1793272189489544)

The existing dependency is boringtun 0.7.1, registry archive SHA-256 `15dd6a8a89cbe8997f37ca0cf035e6ea4d64cd2ecea4aed83ffb9f99f7126939`, upstream revision `051c9d47dc9c5cb36e461b7d36dcd673820dc98b`. The inspected published source has no public timer-policy hook: `noise::timers` is private, `Tunn` owns private sessions and timers, and `update_timers` uses fixed constants. `format_handshake_initiation` cannot extend key lifetime or replace retry exhaustion. An outer timer cannot implement this contract.

Use the existing `native/rust/vendor` patch pattern for this same pinned dependency, with source provenance, BSD-3-Clause notices, and a short list of local changes. Do not add another WireGuard or cryptography library. Keep the original default constructor and ordinary WireGuard behavior. Add an explicit AWG policy at tunnel construction; the existing per-peer lock owns policy and timer state together with the keys. No new lock or background task is needed. The native owner also owns the patch and dependency manifests. An upstream update must reapply and review the local patch and pass the backend and interoperability gates; changing a version string is insufficient.

The patch needs access to these existing internal boundaries:

| Boundary | Required behavior and owner |
|---|---|
| `noise/timers.rs`, `Tunn::encapsulate`, `Tunn::decapsulate` | Advance one monotonic, suspend-aware clock at each operation. Apply configured expiry before using a session on send or receive; a late periodic tick must not extend key use. Retain the upstream clock on Android. |
| `Tunn::handle_handshake_init` and `handle_handshake_response` | Start the appropriate session-age record when keys are derived; retain initiator/responder role per session. Reset retry state on authenticated response or first authenticated data that confirms the responder session. |
| `Tunn::format_handshake_initiation` and timer polling | Keep explicit retry deadlines, sampled retry count, and minimum initiation spacing in the backend. Remove RIPDPI's competing early-rekey and keepalive schedulers when the policy is connected. |
| `RateLimiter::verify_packet` | Always check MAC1. With cookies enabled, preserve the source-bound MAC2/challenge path under load for both initiation and response. With DisableCookies, bypass that cookie challenge branch only, retaining bounded admission and Noise authentication. Do not emulate the flag by setting an unlimited rate threshold. |
| `Tunn::handle_data` and packet validation | Return an explicit authenticated empty result after AEAD and replay acceptance. A nonzero malformed IP payload is not a keepalive. Check declared IP length against its minimum header and decrypted data before returning it. |
| `Tunn::handle_handshake_response`, queued packets, explicit encapsulation and timer keepalives | Apply transport padding before AEAD on every send path, not after a complete transport packet is returned. Preserve the distinction between logical empty payload and zero padding for timer events and byte counters. |
| AWG codec candidate decode and backend receive | Bound candidate attempts to four types. Failed wire MAC or Noise/AEAD candidates do not commit AWG cookie-translation state. A cookie challenge is a provisional output, not proof of a peer; successful authentication selects the candidate. Preserve replay state on failed AEAD. |

#### Timer contract and independent expected values

All configured ranges remain validated decimal u16 ranges. Unset or scalar zero uses the upstream default for the five timer/retry settings; persistent keepalive zero is off. A 0-N range with N greater than zero remains valid for the five timer/retry settings and does not mean default. A selected zero timeout fires on the next poll, at most once per poll, without a synchronous retry loop. The rejection of 0-N applies only to PersistentKeepalive. Ranges draw inclusively with an unbiased bounded sampler. A range is sampled at its semantic event, not on every 250 ms polling tick. Duration arithmetic uses saturating subtraction where the Go reference clamps at zero. Polling may delay an event by one tick but never uses an expired key in the meantime.

| Setting/event | Contract from Go b5928ef | Independent test oracle |
|---|---|---|
| RekeyAfterTime | Default 120 seconds. The original initiator checks a fresh range sample after sending authenticated transport. A responder does not age-rekey on send. Do not change cryptographic primitives or replay-window behavior in the timer step. | Scalar 200: no age rekey at 120 seconds; initiator send after 200 requests one; responder send does not. Scalar 2: send after 2 requests rekey without a fixed 120-second floor. |
| RekeyTimeout | Default 5 seconds. Each initiation arms a fresh retry sample plus upstream jitter in [0,333) ms. Minimum initiation spacing uses the low endpoint. | Range 1-3 with controlled samples: no retry before the selected deadline; one retry at it; new deadline gets a new draw. A low endpoint of 1 must not retain the old five-second guard. |
| RejectAfterTime | Default 180 seconds. Actual data-key expiry uses the high endpoint, checked on send and receive. Go rejects send at age >= high and receive at age > high; age rekey uses > its threshold. Zero-key cleanup uses three times the high endpoint after key derivation or scheduled retry exhaustion. | Range 20-30: a key is not expired at 20; send at 30 is rejected; receive at exactly 30 is accepted if otherwise valid and after 30 is rejected, even without an intervening timer poll. No stale key survives cleanup at 90. |
| Receive-side key refresh | Original initiator uses max(0, sampled RejectAfterTime - low KeepaliveTimeout - low RekeyTimeout); suppress duplicate last-minute refresh for the session. | Scalars 30, 7, 3 give a 20-second threshold. Scalars 5, 7, 3 clamp to zero without unsigned underflow. |
| KeepaliveTimeout | Default 10 seconds. Authenticated nonempty received data arms the passive keepalive if none is pending. Another data event records the need for one more keepalive. Any authenticated send cancels the pending keepalive. | Scalar 7: no idle packet before 7; one at the deadline; an intervening authenticated send cancels it. Empty received transport does not start the nonempty-data timer. |
| No-response handshake | First data send arms high KeepaliveTimeout + sampled RekeyTimeout + jitter; authenticated receive cancels it. | Keepalive 4-7 and retry sample 3 give 10 seconds plus controlled jitter. Later data sends do not keep postponing that pending deadline. |
| MaxHandshakeAttempts | Default 18. Sample at peer start, authenticated handshake completion, and each non-retry initiation request. A non-retry request resets attempts and resamples before the minimum-spacing guard, even if that guard suppresses the packet. The pinned Go callback checks `attempts > max` before incrementing: M permits M+1 retransmissions plus the initial initiation. | Scalar 1: initial initiation, two retries, then exhaustion on the following expiry. Drop staged payload, stop retrying, schedule cleanup; a new explicit send starts a fresh attempt cycle. Test a spacing-suppressed non-retry reset and successful handshake reset separately. |
| PersistentKeepalive | Scalar or range; off at zero. Every authenticated send or receive replaces its deadline with a fresh inclusive sample. | Samples 25 then 35: traversal at t=10 moves the deadline to t=45, not t=25 or t=35. A forged packet or incoming cookie does not reschedule it. |

Use a deterministic clock and controlled random samples in backend behavior tests; run real encrypted peer exchanges for state transitions. The existing upstream `mock-instant` feature is available for backend-only tests. It must not be enabled in Android artifacts or ordinary runtime builds. Preserve monotonic production time and do not substitute wall-clock time or real sleeps for the boundary oracle.

#### Cookies, source identity, and authenticated outcomes

The current UDP carrier uses `UdpSocket::recv` on an unconnected bound socket. Therefore `Some(configured_endpoint.ip())` is not an acceptable replacement for `None`: it would attribute any sender to the configured peer. Use `recv_from` and retain the actual sender in a typed carrier result. This fixed-peer client drops datagrams from other endpoints before cookie handling; no endpoint roaming is added. Reply to the accepted actual source. WebSocket frames use an opaque identity unique to their established carrier session, retained for the lifetime of that connection and invalidated on reconnect. Extend the backend cookie input to accept this identity rather than invent a UDP IP. Bind UDP cookies to source address and port; preserve socket protection and avoid logging the identity.

The current `AwgWireCodec::rewrite_inbound_handshake_macs` also validates/translates nonzero MAC2 before boringtun sees it. Translate a valid MAC2 into the vanilla domain; represent an invalid or unavailable MAC2 as absent so the backend applies its load policy. DisableCookies and a below-threshold load state must not still fail on MAC2 in the envelope. MAC1 and Noise checks remain mandatory. Keep wire and vanilla cookie associated-data conversion inside the codec, and commit its `last_inbound` state only for the selected candidate. Do not allow a failed candidate to overwrite the state used for a later cookie reply.

Receive outcomes must distinguish: rejected candidate; cookie challenge; accepted cookie state; authenticated handshake; authenticated empty transport; authenticated IP payload; and authenticated malformed transport. Only successful handshake or accepted transport can establish peer readiness or advance authenticated-traffic timers. A `WriteToNetwork` result alone is insufficient: it can be a cookie challenge. A cookie reply's key is derived from a public key, so successful cookie decryption does not prove peer identity. Authenticated malformed transport consumes its replay counter but does not deliver IP, count as a keepalive, or grow the padding window. It terminates candidate search without replay rollback. Failed AEAD does not consume the replay counter. Defer a provisional cookie challenge until the other candidates have been tried, then commit only that challenge's conversion context if no authenticated candidate succeeds.

Required cookie oracles use real packets and deterministic low load thresholds: MAC1-valid forged response causes a cookie challenge but never readiness or session timestamps; invalid MAC1 fails with either flag; enabled cookies require valid source-bound MAC2 under load; disabled cookies reach Noise verification without MAC2 but still reject forged Noise; valid and invalid incoming cookie replies update only cookie state. Test a foreign UDP source and the WebSocket carrier policy independently. Retain a separate per-peer budget of ten expensive handshake attempts and ten emitted cookie challenges per second, matching the current backend's handshake threshold. Reset the budget with monotonic time; invalid MAC1 cannot consume it, and ordinary authenticated transport is outside it. This resource bound is an explicit local safety limit, not exact Go load-policy parity. Test boundary, reset, exhaustion, and recovery without disabling authentication.

### UDP-window lifetime

The initial window is 500 bytes. Outbound transport raises it to the unpadded message size; inbound valid empty/IP transport raises it to the authenticated datagram size minus the 16-byte AEAD tag. The bound is the configured inner MTU (1280..1500) plus S4 and 32 transport bytes. This cap is a deliberate local bound; it does not claim the unbounded Go heuristic is identical.

The codec belongs to one fixed-endpoint tunnel/carrier. Rekey within that tunnel retains its window. A new endpoint or carrier connection creates a new tunnel and codec with a fresh 500-byte window. Foreign UDP sources are rejected; endpoint roaming is outside this change. Go resets its peer window in `SetEndpointFromPacket` when the endpoint changes. RIPDPI uses tunnel replacement for that boundary.

### Implementation progress

Candidate decoding now prepares at most four layouts without changing codec state. The backend tries transport, initiation, response, then cookie. Transport takes priority because cookie encryption uses a public-key-derived key and does not prove peer identity. A cookie challenge remains provisional until all other candidates fail. Only the selected result commits its conversion context; authenticated malformed transport terminates the search after replay consumption.

The same receive boundary serves the tunnel and endpoint probe. Inspection found that the probe also treated a cookie response as success and used the first decoded layout. It now requires an accepted Noise response or valid transport, retains the full UDP source address, and uses backend retries. Explicit deadline checks bound ready socket operations as well as awaited operations; continuously ready input cannot extend the probe deadline.

Readiness follows the handshake role: the initiator becomes ready after an accepted Noise response; the responder becomes ready after the first authenticated transport confirms its issued session. An accepted INIT alone is not handshake completion. An invalid incoming cookie changes no cookie, timer, or readiness state.

The boundary study selects an in-repository patch of the existing dependency and records the timer/event, source identity, bounded admission, and cookie/authentication contracts above. No backend implementation or runtime acceptance is implied by this design entry.

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
5. Record the user-approved exclusion of physical-device and private-LAN acceptance. Obtain hosted CI and exact tested emulator artifact receipts. Preserve the shared deployment guard; no production-service, direct-LAN or physical handover acceptance is claimed.
6. Roll back implementation commits and binaries together if acceptance fails. Do not clear saved profiles or secrets. Document the newly rejected input grammar so rollback is not presented as restoring source text already lost by older parsing.

## Validation strategy

The execution tasks and `verification.md` are the authoritative gate mapping. Extend existing tests before adding new suites. Native unit coverage belongs in `awg_v3_tests.rs`, `awg_timers.rs` and the owning tunnel tests; interoperability belongs in `standalone_awg_interop.rs`. Kotlin coverage belongs in existing parser, editor, resolver, repository, and serialization suites.

Required implementation gates include `cargo test -p ripdpi-warp-core --locked` from `native/rust`, affected Kotlin module tests, full static analysis, locale lint for changed strings, independent review, and the host/emulator acceptance receipts and the documented physical/private-LAN scope exclusion. Review uses `pr-reviewer`; add async, unsafe, and JNI specialist review only when the actual diff touches those areas. Do not bless or weaken fixtures or baselines to obtain a pass.

Research baseline only: `cargo test -p ripdpi-warp-core --lib --locked` observed 151 passed, zero failed, zero ignored at the baseline SHA. The historical emulator record reports 96/96 at `ba228c17c7cfb1f90a09f6af03a1c5837e1a100c`. Neither result proves the future requirements or physical acceptance. Planning validation is separate from every implementation gate.

### Receive validation compatibility exception

Ordinary WireGuard keeps its default timer policy and wire format. The shared
receive boundary now rejects malformed authenticated IPv4 headers (invalid IHL,
total length below the header, or truncation) before peer-state updates. Malformed
IPv6 is also excluded from peer proof. Such packets still consume their replay
counter, but do not update readiness, session selection, receive timers, byte
metrics, or the UDP window. This is a deliberate input-validation exception to
legacy boringtun behavior, which could report an empty IP packet for IPv4 total
length zero. A regression covers valid ordinary WireGuard traffic after rejection.
All-zero nonempty padding is accepted only when the AWG policy is active.
