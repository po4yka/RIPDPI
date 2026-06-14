# ADR 0004: Maintain only current/actual protocols — remove VMess, Trojan-Go, Hysteria v1

| | |
| --- | --- |
| **Status** | Accepted (2026-06-01) |
| **Area** | Outbound relay protocols / config contract |
| **Supersedes** | The "Extended outbound protocol support" epic's inclusion of VMess, Trojan-Go, and Hysteria v1 |
| **Wire impact** | Relay native-config schema ceiling bumped `7 → 8`; proto field numbers `307–318`, `322–327` reserved (and their names); `relay_kind` values `"vmess"`, `"trojan_go"`, `"hysteria_v1"` removed |

## Context

The "Extended outbound protocol support" epic tracked a backlog of relay protocols
to add for subscription compatibility: VMess, Trojan-Go, Mieru, SSH, Hysteria v1.
Of these, **VMess, Trojan-Go, and Hysteria v1** were carried only as **stubbed wire
engines** — their config parse/validate, `RelayKind` registration, wire DTOs, URI
codecs, profile editors, and localized strings shipped, but session creation always
failed with `Unimplemented`. They never carried traffic.

The epic's own notes already flagged them as legacy/transitional:

- VMess: "legacy but common in older feeds … do not surface it in the new-profile UI."
- Hysteria v1: "included for transition, but once subscriptions have fully migrated to
  v2 the v1 crate should be removed, not left to rot."
- Trojan-Go: "sunset-flagged."

Carrying a never-completed wire engine plus its full config/UI/codec/locale surface is
ongoing maintenance cost (enum exhaustiveness, drift gates, golden fixtures, 8 locales)
for zero working capability. Upstream ecosystems have moved on: VMess to VLESS/REALITY,
Trojan-Go to plain Trojan, Hysteria v1 to Hysteria2.

## Decision

**RIPDPI maintains support only for current/actual protocols** — those in active use in
realistic bypass subscriptions and maintained upstream. VMess, Trojan-Go, and Hysteria v1
are removed entirely from code and documentation rather than left as dead stubs.

Supported outbound/relay protocols are: VLESS Reality/xHTTP, Hysteria2, TUIC v5, MASQUE,
ShadowTLS, Shadowsocks, Trojan, AnyTLS, Tor (per [ADR 0002](0002-tor-feasibility.md)), and
relay chaining. SSH and Mieru were in the backlog as not-yet-implemented **at the time of this
decision** (they are *not* "legacy" — they are newer compatibility work) and were explicitly
**out of scope of this removal**. _Status note: both have since been implemented at the
native-carrier level — see the **Update (2026-06-14)** section at the end of this ADR. The
2026-06-01 decision itself is unchanged._

A new protocol is added only when it clears the epic's inclusion bar (present in realistic
subscriptions, maintained upstream). A protocol that becomes legacy and unused is removed,
not stubbed indefinitely.

## Consequences

- The Rust crates `ripdpi-vmess`, `ripdpi-trojan-go`, `ripdpi-hysteria-v1` are deleted, along
  with their `RelayKind`/`RelayBackend`/transport-descriptor/builder/flat-config surface in
  `ripdpi-relay-core` and the wrappers in `ripdpi-relay-tls-transports`.
- A persisted config or share-link that still names a removed protocol is **rejected** — the
  native relay builder routes the now-unknown kind through the existing `Unsupported` catch-all
  (`relay backend <kind> is not implemented`); the proxy URI codec returns `null`; subscription
  import **skips** the unsupported node (the rest of the subscription still imports).
- Kotlin: the `ProxyProfile.Vmess`/`TrojanGo`/`HysteriaV1` sealed types, their profile editors,
  URI codec arms, DTO sections, mappers, descriptors, and backup-allowlist entries are removed.
- Wire contract: `RelayNativeConfigSchemaVersion` and the Rust schema ceiling are `8`; the proto
  field numbers and names are reserved so they are never reused.
- Localized strings for the three protocols are dropped from all 8 locales.

## Revisit trigger

Reopen only if a removed protocol re-appears at material volume in realistic subscription
samples *and* remains maintained upstream — in which case it re-enters the inclusion-bar
evaluation as new work, not as a restored stub.

## Update (2026-06-14) — SSH and Mieru implemented

This is an additive status amendment (per `docs/adr/README.md`: "update living reference docs
when implementation changes"). The **Decision** above is unchanged — only the "backlog /
not-yet-implemented" status aside for SSH and Mieru is now stale. Status derived from code at
this point, not from intent:

- **SSH — implemented and relay-wired.** Native crate `native/rust/crates/ripdpi-ssh` (real
  `russh` engine); loopback-verified by `native/rust/crates/ripdpi-ssh/tests/loopback.rs`
  (stands up a `russh` echo server on `127.0.0.1`, password auth, `direct-tcpip` channel,
  byte round-trip). Native relay builder has a real arm
  (`ripdpi-relay-core/src/backend/builder/builders/ssh.rs` → `RelayBackend::Ssh`, not the
  `Unsupported` catch-all). Kotlin is end-to-end wired: `RelayProfileActivator` handles
  `ProxyProfile.Ssh` (→ `RelayKindSsh` + credentials) and `SshProfileScreen` is a complete
  editor. No "not implemented" status is shown in the UI (correctly).

- **Mieru — native carrier implemented and loopback-verified; end-to-end profile activation
  still pending.** Native crate `native/rust/crates/ripdpi-mieru` (XChaCha20-Poly1305 wire
  engine); loopback-verified by `ripdpi-mieru/src/loopback.rs::client_round_trips_one_mib_through_spec_faithful_loopback`
  (1 MiB TCP round-trip) with `udp_protocol_is_rejected` enforcing the UDP gate. The resolver
  builds a `mieruSection()` and `MieruProfileScreen` exists with an **honest** "Experimental —
  unverified against live servers" banner. **Remaining gap:** `RelayProfileActivator` has no
  `ProxyProfile.Mieru` arm yet, so a saved Mieru profile is not yet activatable end-to-end, and
  UDP relay is intentionally gated off pending the custom wire engine. So Mieru is "carrier
  loopback-verified", not "fully usable from a profile".

Neither protocol is live-verified (no real-remote-endpoint test exists for any relay protocol —
loopback fixtures only); that is the same bar applied to every other implemented protocol here.

A full code-derived per-protocol truth table (every outbound protocol × crate / loopback / live
/ relay-wired / UI present / UI honest) lives at
[`docs/architecture/PROTOCOL_STATUS.md`](../architecture/PROTOCOL_STATUS.md).
