---
title: Adopt tls_spoof pre-handshake ClientHello SNI desync for whitelist bypass
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-30
updated: 2026-06-05
---

- [ ] #task Adopt tls_spoof pre-handshake ClientHello SNI desync for whitelist bypass #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective

Evaluate, then (if feasible on Android) implement, a `tls_spoof`-style countermeasure: inject a forged TLS ClientHello carrying a permitted (whitelisted) SNI immediately before the real handshake, so SNI-filtering DPI (TSPU) burns its filter decision on the decoy and the real handshake passes. sing-box shipped this as a first-class, per-route primitive in v1.14.0-alpha.21; this task brings the technique into RIPDPI's native transport stack or proves it is not viable on Android and records why.

## Context

This is **distinct** from the completed WS-tunnel fake-SNI cover task (`gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry`). That work put a cover SNI on the WS-tunnel path and disabled cert verification for Telegram-WSS impersonation. `tls_spoof` is a different mechanism: a **pre-handshake decoy packet** that the real server rejects, used purely to defeat the middlebox's SNI filter — the real connection still does full, verified TLS.

Mechanism (from sing-box v1.14.0-alpha.21 + upstream docs):

- `tls_spoof` (route rule action / outbound option): inject a forged ClientHello carrying a permitted SNI before the real one. The forged ClientHello is a byte-copy of the real one with **only the SNI replaced**, so JA3/JA4 fingerprinting cannot distinguish it. The middlebox locks onto the permitted-SNI session and whitelists the 5-tuple; the real server discards the forged segment; the real ClientHello (true SNI) then passes uninspected.
- `tls_spoof_method` / `spoof_method` — how the forged segment is made unacceptable to the real server while still being accepted by DPI:
  - `wrong-ack` — invalid TCP ACK number (server drops on sequence validation).
  - `wrong-md5` — bogus TCP-MD5 signature option, RFC 2385 (server drops on auth-option mismatch).
  - `wrong-timestamp` — out-of-window TCP timestamp option (server discards as stale).
- Route-level selectivity: `tls_spoof`/`tls_spoof_method` are route-rule actions, so spoofing applies per-destination (e.g. only toward foreign-AS endpoints under whitelist enforcement) without enabling it globally.

This is the sing-box-native equivalent of the patterniha "SNI-Spoofing" technique and the ntc.party `rzd.ru` fake-SNI trick — exactly the whitelist-bypass primitive RIPDPI targets against TSPU SNI-filter / whitelist enforcement.

> **Central constraint (Android):** the spoof needs raw-socket access. On Linux it requires `CAP_NET_RAW` **plus** `CAP_NET_ADMIN` — the latter because the client must read the connection's send sequence number via the kernel `TCP_REPAIR` socket option to craft a segment the real server rejects at the right point in the stream. macOS needs root; Windows needs Administrator (WinDivert). Windows-on-ARM64 is unsupported. **Unrooted Android cannot grant these capabilities to an app process.** This makes on-device `tls_spoof` likely infeasible for the standard RIPDPI client; the viable pattern is to perform the spoof on a RIPDPI-controlled relay/server hop the client routes through. This task must resolve that question first.

## Acceptance criteria

- [ ] **Spike (gating):** Determine whether pre-handshake ClientHello injection is achievable inside RIPDPI's Android client process given the VpnService/TUN model and the `CAP_NET_RAW`/`CAP_NET_ADMIN` requirement. Record the verdict (feasible / infeasible / relay-only) with the specific blocker if infeasible.
- [ ] If on-device is infeasible, specify the **relay-side** design: which relay hop runs the spoof, how the client signals intent, and how it composes with VLESS+Reality / xHTTP on that hop.
- [ ] Define the config surface: a per-profile or per-route `tls_spoof` toggle + `spoof_method` enum (`wrong-ack` | `wrong-md5` | `wrong-timestamp`) + the decoy SNI hostname (must be a DNS name, not an IP literal — IP-literal server names produce no SNI to spoof).
- [ ] Native implementation (or relay integration) lands behind a default-off flag, with the decoy SNI validated as a hostname and the method enum validated.
- [ ] A unit/integration test exercises the desync path: forged ClientHello emitted with replaced SNI, real handshake succeeds, and the forged segment is rejected by the peer per the selected method.
- [ ] Telemetry counter increments per spoofed connection (mirror the `wsTunnelFakeSniActive` pattern from the completed fake-SNI task) so the technique's use is visible at runtime.
- [ ] Decoy-SNI and spoof-method are redacted/safe in all diagnostic surfaces; no decoy hostname leaks operator intent inappropriately.
- [ ] `docs/native/proxy-engine.md` documents the mechanism, the Android privilege constraint, and the relay-vs-on-device decision.

## Definition of done

- [ ] A documented verdict on Android feasibility exists, and either (a) a working default-off `tls_spoof` path (on-device or relay-side) with tests and telemetry, or (b) a recorded decision that the technique is relay-only / deferred, with the rationale captured in the epic.

## Risks / open questions

- Two ClientHellos in close succession are themselves a fingerprint; stateful DPI tracking the full TLS exchange may detect the decoy pattern. Confirm against current TSPU behavior before broad rollout.
- Spoofing SNI does nothing where the censor blocks by destination IP rather than SNI — scope this to SNI-filter / whitelist-SNI networks only.
- Running with raw-socket capabilities expands the blast radius on any host that holds them; keep the feature off by default and out of standard subscription profiles.
- sing-box ships this on the v1.14 **alpha** line; the API may shift before stable. Re-verify the field names and privilege table on each upstream bump.

## Work log

- 2026-06-05: No tls_spoof implementation exists anywhere in the codebase — no Rust crate, no Kotlin surface, no config fields, no tests, no telemetry counters, no docs/native/proxy-engine.md section. The spike (gating AC) has not been completed. Parent epic-control-plane-hardening was dangling (not in allowed epic list); nulled out.
- 2026-06-05: Re-verified: `ripdpi-desync` and `ripdpi-desync-runtime` crates exist (native/rust/crates/) with real desync/fake-packet code, but none of it is tls_spoof — no `SpoofMethod`, `wrong_ack`, `wrong_md5`, `wrong_timestamp`, or pre-handshake decoy-SNI path; `docs/adr/0006-singbox-rule-action-parity.md` confirms tls_spoof is tracked as open. Status remains `backlog`; all checkboxes remain `[ ]`.

## Source references

- sing-box v1.14.0-alpha.21 release; docs: `https://sing-box.sagernet.org/configuration/shared/tls/#spoof` and `https://sing-box.sagernet.org/configuration/route/rule_action/`.
- Corroborating technique: `github.com/therealaleph/sni-spoofing-rust` (Rust fake-ClientHello-with-wrong-TCP-seq port of patterniha's SNI-Spoofing).
- Vault wiki: `censorship-bypass` → `wikis/transport-protocols/wiki/concepts/sing-box-tls-spoofing-alpha-13-2026.md` (full mechanism + privilege table, incl. the `TCP_REPAIR`/`CAP_NET_ADMIN` note).

## Links

- [[Epic - Control-plane hardening]]
- gate-fake-sni-cert-bypass-behind-allow-insecure-flag-with-telemetry (closed — distinct WS-tunnel fake-SNI cover; reuse its telemetry-counter pattern)
