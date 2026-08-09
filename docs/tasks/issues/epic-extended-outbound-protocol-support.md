---
id: EPC-1786264762917457
title: Epic - Extended outbound protocol support
kind: epic
status: todo
area: epic
priority: medium
risk: high
owner: Outbound protocol maintainer
parent: null
blocked_by:
  - OUT-1786264762917254
  - OUT-1786264762917513
  - OUT-1786264762917551
spec_mode: required
openspec_change: epc-1786264762917457-epic-extended-outbound-protocol-support
created: 2026-04-24
updated: 2026-08-09
---

> **2026-06-01 — scope reduced per [ADR 0004](../../adr/0004-protocol-support-policy.md).** VMess, Trojan-Go, and Hysteria v1 are **dropped from this epic and removed from the codebase** — they were never-completed stubs that carried no traffic, and RIPDPI maintains support only for current/actual protocols. The remaining open backlog is **SSH** and **Mieru** only (not-yet-implemented compatibility work, explicitly *not* legacy). Their child tasks are deleted.

## Goal

Cover the remaining outbound protocol types that realistic third-party subscriptions still ship. Current source already has first-class Shadowsocks, Trojan, and AnyTLS support in the native relay stack and import paths; the open backlog is now **SSH and Mieru** (and any future decision to add generic HTTP(S)/SOCKS5 outbound profiles). VMess, Trojan-Go, and Hysteria v1 were evaluated and removed — see [ADR 0004](../../adr/0004-protocol-support-policy.md).

## Why now

Subscription import is only useful if imported protocols can execute. SSH and Mieru are lower-volume compatibility work. Trojan itself has landed and should not be re-added through this epic.

## Key decisions

- **Native Rust crates, mirroring existing pattern** (`ripdpi-vless`, `ripdpi-hysteria2`, `ripdpi-tuic`, `ripdpi-shadowtls`). No external C/Go binaries in the outbound path for these.
- **Protocol inclusion bar: must be present in realistic outbound-profile subscriptions.** The remaining matrix is SSH, Mieru, and possibly generic HTTP(S)/SOCKS5 outbound profiles if subscription samples justify them. **Tor is deliberately excluded** from this outbound-compatibility epic because it is a separate anonymity backend decision.
- **SSH is included** because it remains a common relay for hobbyist network-path compatibility setups, despite low share-count; the existing `ripdpi-warp-core` noise primitives are unrelated — SSH needs its own crypto.
- **VMess, Trojan-Go, and Hysteria v1 are removed, not stubbed** — see [ADR 0004](../../adr/0004-protocol-support-policy.md). A protocol that becomes legacy and unused is deleted rather than left to rot; the inclusion bar is "current and maintained upstream."

## Scope

- **Already landed:** Shadowsocks, Trojan, and AnyTLS have native relay support and import paths in current source.
- **In scope:** Rust crates/profile support for SSH and Mieru, and any approved generic HTTP(S)/SOCKS5 outbound profiles; UI editor screens; URI codec extension where a real scheme exists; integration into the existing relay supervisor model; strategy-pack compatibility hints per protocol.
- **Removed (was in scope):** VMess, Trojan-Go, Hysteria v1 — deleted from code and docs per [ADR 0004](../../adr/0004-protocol-support-policy.md).
- **Out of scope:** Tor (see exclusion rationale above), Brook, SOCKS4/4a, other SagerNet-branded protocols; inbound server roles for any of these; Shadowsocks plugins (simple-obfs, v2ray-plugin) — a follow-up epic if real subscription samples demand them.

## Ship definition

- [~] Remaining protocol crates/profile support exist and are unit-tested against upstream reference test vectors. (`ripdpi-ssh` real with 438-line `client.rs` and tests; `ripdpi-mieru` TCP carrier now implemented — XChaCha20-Poly1305 time-rotated keys + open-session handshake + segment framing + in-tunnel SOCKS5, wired into the relay via `MieruSessionFactory`, self-consistency-tested via an in-crate loopback. Both still lack **upstream/live-server** reference vectors; `ripdpi-mieru` UDP carrier + multiplexing are deferred.)
- [x] Each protocol has a profile-edit screen with schema-backed validation. (`SshProfileScreen.kt`, `MieruProfileScreen.kt`, `AnyTlsProfileScreen.kt` under `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/`.)
- [x] Each protocol can be parsed from its standard URI scheme into a valid RIPDPI profile and round-tripped back to URI. (`anytls://` + `mieru://` pre-existing; `ssh://` added 2026-06-11 — first-class `ProxyProfile.Ssh` + `parseSsh`/`encodeSsh` round-trip incl. multi-line private keys, commit `b87e0a85`, +parse/round-trip tests.)
- [x] Strategy-pack metadata includes per-protocol compatibility hints (e.g. Trojan inside xHTTP, SSH direct vs SSH-over-TLS). (`StrategyPackProtocolHint` + bundled `catalog.json` `ssh`/`mieru`/`anytls` entries, load-bearing via `StrategyPackSnapshot.protocolHints` / `hintForProtocol`, commit `d9cb78a8`, +tests.)
- [~] Relay supervisor can start and stop each protocol cleanly; shutdown joins bounded handler work (same invariant as existing protocols). (SSH wired into relay-core: `transport_descriptor.rs:147 kind_id:"ssh"`, `backend.rs RelayBackend::Ssh`; Mieru TCP carrier wired via `MieruSessionFactory` with a `RelayBackend::Mieru` variant — UDP carrier + multiplexing deferred.)
- [x] Secrets (passwords, UUIDs, private keys) are redacted in logs, diagnostics, and crash reports, not only at export time. (SSH + Mieru redact in `Debug` (pre-existing); AnyTLS closed 2026-06-11 — Rust `Debug` for `AnyTlsClientConfig` masks password + root cert (commit `f3e77f2`) and `ProxyProfile.AnyTls.toString` masks the password (commit `b87e0a85`); the new `ProxyProfile.Ssh.toString` masks password/key/passphrase.)

## Child tasks

- [[Add SSH outbound client crate and profile editor]]
- [[Finish AnyTLS profile editor and compatibility gaps]]
- [[Add Mieru outbound client crate and profile editor]]

(Removed per [ADR 0004](../../adr/0004-protocol-support-policy.md): VMess, Trojan-Go, Hysteria v1 child tasks and crates.)

## Dependencies

- Unblocks: subscription-driven deployment in Epic - reference implementation subscription and profile import. Nodes naming a removed protocol (VMess/Trojan-Go/Hysteria v1) in an imported subscription are skipped, not connected.

## Risks / open questions

- SSH channel multiplexing adds complexity; consider single-channel v1 before committing to full multiplexing.
- Strategy-pack cross-product growth; keep per-protocol recommended arms tight.

## Links

- [[ripdpi-android]]
- Epic - Subscription and profile import
- [ADR 0004: Protocol Support Policy](../../adr/0004-protocol-support-policy.md)
- Child issues: 3

## Work log

- 2026-06-05: SSH crate fully implemented (ripdpi-ssh, SshProfileScreen.kt, relay adapter in ripdpi-relay-tls-transports/src/ssh.rs, secrets redacted in Debug); Mieru crate is stubbed (connect() returns MieruError::Unimplemented, no real handshake); SSH has no ssh:// case in ProxyUriCodec.parse() or ProxyProfileUriEncoder.encode(); strategy-pack catalog.json contains no SSH or Mieru compatibility hints; AnyTLS crate and profile screen are complete.
- 2026-06-05: Epic re-audited; status STAYS `doing`. Child rollup: all 3 child tasks (SSH, Mieru, AnyTLS) are `doing`, none done/dropped. Source-verified ship-definition: ripdpi-ssh client.rs is 438 real lines + tests and wired into relay-core (transport_descriptor.rs:147 kind_id:"ssh", backend.rs RelayBackend::Ssh); ripdpi-mieru/src/client.rs connect() returns MieruError::Unimplemented (stub, no relay backend). Profile screens exist for all three (ui/screens/ssh|mieru|anytls). No ssh:// URI codec case exists in app/src/main (only a doc comment in ImportHandlerActivity.kt). strategy-packs/catalog.json has no ssh/mieru hints (grep -c = 0). Marked criteria [x] screens, [~] crates/supervisor/secrets, [ ] URI + strategy-hints.
- 2026-06-10: Corrected stale Mieru ship-definition text. The Mieru TCP carrier is no longer a stub — it is implemented (XChaCha20-Poly1305 time-rotated keys, open-session handshake, segment framing, in-tunnel SOCKS5) and wired into the relay via `MieruSessionFactory` / `RelayBackend::Mieru` (see the Mieru child task's third 2026-06-05 log entry). Still open across the epic: `ssh://`/`mieru://` URI round-trip parity for SSH, per-protocol strategy-pack hints, upstream/live-server interop vectors, and Mieru UDP carrier + multiplexing.
- 2026-06-11: Epic advance pass — **3 ship-definition criteria closed**, merged to `main` (3 commits, full gate battery green). (1) URI round-trip parity: first-class `ProxyProfile.Ssh` + RIPDPI-invented `ssh://` codec (`b87e0a85`), completing round-trip for all three protocols. (2) Per-protocol strategy-pack hints: `StrategyPackProtocolHint` modeled + bundled `catalog.json` ssh/mieru/anytls entries, load-bearing via `hintForProtocol` (`d9cb78a8`). (3) Secrets redacted in all surfaces: AnyTLS Rust `Debug` + Kotlin `toString` redaction (`f3e77f2`, `b87e0a85`). Also: AnyTLS fallback-SNI now explicitly rejected at import. **Status STAYS `doing`** (decision: merge verified subset, keep epic open). Documented deferrals: AnyTLS Mode-Editor inline fields (separate end-to-end editable-relay-kind feature), SSH connect-time host-key TOFU dialog (no pattern / editor doesn't connect), SSH editor-side persistence (all editors are uniformly preview-only by design), Mieru network-time source (no canonical workspace provider; matches shadowsocks wall-clock), Mieru UDP carrier + multiplexing (sanctioned gate), and live cross-interop vs `anytls-go`/`mita` (offline-infeasible). See each child's 2026-06-11 work-log entry.
