# Protocol implementation status — code-derived truth table

**Derived from code only** (crates, native relay builder arms, tests, Kotlin
activation/resolver, UI) at `main` as of 2026-06-14. This is a reconciliation
snapshot, not a forward plan; when implementation changes, re-derive rather than
hand-edit. The authoritative enumerations are Rust
`ripdpi-relay-core/src/config/kind.rs` (`RelayKind`) and the Kotlin relay
descriptors — this doc summarizes their realized status.

## How to read

- **Crate** — a `native/rust/crates/ripdpi-<x>` carrier crate exists.
- **Loopback** — an executable test stands up the protocol session and round-trips
  bytes over `127.0.0.1`/`::1` and asserts success (config-parse/URI tests do **not** count).
- **Live** — a test exercises a real remote endpoint. **No relay protocol has live
  coverage** — every protocol is loopback-verified only. This is uniform, not a per-protocol gap.
- **Relay-wired** — end-to-end Kotlin path: `RelayProfileActivator` arm + resolver section + a
  real native builder arm (not the `Unsupported` catch-all). `partial` = some links missing.
- **UI** — a profile editor screen composable exists.
- **UI honest** — the UI's stated status matches reality.

## Truth table

| Protocol | Crate | Loopback | Live | Relay-wired | UI editor | UI honest | Verdict |
| --- | --- | --- | --- | --- | --- | --- | --- |
| VLESS Reality / xHTTP | yes | yes | no | yes | yes | yes | **implemented** |
| Hysteria2 | yes | yes | no | yes | yes | yes | **implemented** |
| TUIC v5 | yes | yes | no | yes (default resolver) | yes | yes | **implemented** |
| MASQUE | yes | yes | no | yes | yes | yes | **implemented** |
| ShadowTLS v3 | yes | yes | no | yes | yes | yes | **implemented** |
| AnyTLS | yes | yes | no | yes | yes | yes | **implemented** |
| SSH | yes | yes | no | yes | yes | yes | **implemented** |
| Shadowsocks | yes | yes | no | partial (import path) | no dedicated editor | yes | partial |
| Trojan | yes | yes | no | partial (import path) | no dedicated editor | yes | partial |
| Mieru | yes | yes | no | partial (no activator arm; UDP gated) | yes | yes (honest "experimental" banner) | partial |
| Tor (Arti) | yes | yes | no | partial (opt-in, bridge bootstrap) | yes | yes | partial |
| AmneziaWG | yes | no | no | no (separate VPN/tunnel surface, not a `relay_kind`) | yes | yes | partial |
| WireGuard / WARP | yes | no | no | no (separate VPN/tunnel surface) | yes | yes | partial/standalone |
| Xray | no (no crate/runtime) | no | no | no | yes (import screen) | yes | translate-only import |
| SOCKS5 / HTTP (local) | n/a | n/a | n/a | n/a | n/a | n/a | local listener, not an outbound relay |

## Key evidence

- **SSH** — `ripdpi-ssh` + `tests/loopback.rs` (russh echo server on `127.0.0.1`, password auth,
  `direct-tcpip` round-trip); builder arm `ripdpi-relay-core/.../builders/ssh.rs` → `RelayBackend::Ssh`;
  `RelayProfileActivator` `ProxyProfile.Ssh` → `RelayKindSsh`; `SshProfileScreen` editor.
- **Mieru** — `ripdpi-mieru` + `src/loopback.rs::client_round_trips_one_mib_through_spec_faithful_loopback`
  (1 MiB round-trip) and `udp_protocol_is_rejected`; `mieruSection()` resolver; `MieruProfileScreen`
  with honest "Experimental — unverified against live servers" banner. **Gap:** no
  `ProxyProfile.Mieru` arm in `RelayProfileActivator` (not activatable from a saved profile yet);
  UDP relay intentionally gated.
- **AmneziaWG / WARP** — separate VPN/tunnel profile surfaces (per README), not `relay_kind`
  values; AmneziaWG ships a standalone `ripdpi-amneziawg-android` runtime (JNI create/start/stop)
  + editor UI but is not relay-integrated and has no byte-round-trip loopback test on `main`.
- **Xray** — no `ripdpi-xray` crate or runtime; the Xray path is an **import/translate** surface
  (`vless://` REALITY/xHTTP links → native `ProxyProfile.VlessReality`); unsupported outbounds are
  skipped on import.

## Documentation reconciliation findings

| Doc | Claim | Reality | Action |
| --- | --- | --- | --- |
| ADR 0004 + `docs/adr/README.md` | "SSH and Mieru remain backlog / not-yet-implemented" | Both implemented at native-carrier level (SSH end-to-end; Mieru loopback-verified, activation pending) | **Fixed** — additive ADR Update (2026-06-14) + adr-README status/cross-link |
| `README.md` (+ 6 translations) | full relay-protocol table incl. `mieru`/`ssh`/`tor`; AWG/WARP scoped as separate tunnel surfaces; Xray as import-compat | Matches code | **No change** — the "understated / 6 native modules" premise does not hold at HEAD |
| `ROADMAP.md` | no protocol/implementation status (only Offline-Learner follow-ups) | n/a | **No change** — nothing to sync |

## Caveats / corrections made during this audit

- An automated derivation pass over-reported a **false** finding: that `SshProfileScreen` shows a
  "not yet implemented" warning banner. Independent verification found **no `WarningBanner` in
  `SshProfileScreen.kt`** — discarded. (Mieru's "experimental" banner is real and honest.)
- The prompt anticipated AmneziaWG and Xray being "functional after P0-AWG/P0-Xray land." Those
  implementation PRs are **not present on `main` at this HEAD**; this table reflects code as-is
  (AWG partial / standalone; Xray translate-only). Re-derive once those land.
- "Live verification" is absent for every relay protocol (loopback fixtures only) — a uniform
  property of the test suite, not a per-protocol deficiency.
