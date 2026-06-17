---
title: Harden TLS certificate-validation posture against observed RU active-MITM
type: task
status: done
area: transport
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-15
updated: 2026-06-17
source_wiki_pages:
  - "russian-tls-mitm-certificate-interception-2026"
linked_task: null
---

## Motivation

`russian-tls-mitm-certificate-interception-2026` (ntc.party t/25005, 2026-06; plus a 2025 government-cert-via-MTS incident) records occasional active TLS-MITM events in Russia: self-signed or empty-field certificates injected mid-connection, an operator redirect endpoint, and at least one case of a state-issued CA certificate delivered via a major mobile operator. These are observation-grade reports, not a controlled measurement study. Android's default distrust of user-installed CAs is a structural defense for app traffic, but it is meaningful only if RIPDPI's own channels (control-plane, subscription fetch, relay-backend TLS) do not expand that trust surface — e.g. by inheriting user-added CAs, accepting self-signed or empty-field server certificates, or skipping chain validation. This is a posture review, not incident response.

## Proposed change

Audit, and where gaps are found harden, the TLS trust configuration for RIPDPI's own outbound channels — not relay-proxied user traffic (intentionally pass-through), but the app's internal connections:

1. **Subscription / control-plane HTTP(S) clients** (Kotlin OkHttp or Ktor layer): confirm the trust manager used for subscription fetch and control-plane calls restricts to the Android system trust store (no user-added CAs), rejects self-signed certificates, and enforces hostname verification. Document the finding at the construction site referencing this task.
2. **rustls-backed relay connections** (crates that open a TLS session to a RIPDPI-operated relay endpoint): confirm the `rustls::ClientConfig` is constructed with a roots-of-trust set that excludes user-installed CAs, that empty-field/self-signed server certificates cause a handshake error rather than a silent downgrade, and that SNI is always sent. Document at the construction sites.
3. **Evaluate static public-key / certificate pinning** for RIPDPI-operated relay endpoints as defense-in-depth. Assess the operational cost (rotation, breakage on renewal) and record the decision (adopt with rotation plan, or explicit defer with rationale) before implementing.
4. Produce a short written inventory (one row per channel class) of the current validation posture, the gap found (if any), and the fix applied or rationale for deferral.

No changes to relay-proxied user-traffic trust decisions.

## Acceptance criteria

- [x] Written inventory of TLS trust configuration for each RIPDPI-owned outbound channel class (subscription/control-plane HTTP clients; rustls relay-backend connections) with file+line references and gap/fix summary. (See **Phase 1 — Audit findings** below.)
- [x] Any subscription/control-plane HTTP client confirmed to exclude user-installed CA trust and enforce hostname verification. **No gap found** — `OwnedTlsClientFactory` inherits OkHttp's default system trust store + hostname verifier and never overrides them; NSC excludes user CAs. No fix/test required (the "if a gap" clause does not trigger).
- [x] Any `rustls::ClientConfig` used for RIPDPI relay connections confirmed to use roots-of-trust excluding user CAs and to reject self-signed/empty-field certificates. **No gap found** — every PKI-validated channel trusts a *bundled Mozilla root set* (`webpki_roots::TLS_SERVER_ROOTS` for rustls; a pinned Mozilla CCADB PEM via `seed_default_trust` for BoringSSL), which never reads the OS/user trust store. No fix/test required.
- [x] Pinning decision recorded — **DEFER** (see **Pinning decision** below).
- [x] `cargo nextest run --locked` / Android unit tests / clippy — **N/A: no production code changed** (posture already correct). Existing coverage (`ripdpi-tls-profiles` `bundle_parses_at_least_one_hundred_roots` / `seed_default_trust_populates_cert_store`; `ripdpi-hysteria2` `with_insecure` tests) already locks the trust posture.

## Risks / open questions

- `add-network-security-config-with-opportunistic-domainencryption` covers the Android NSC XML for ECH; any `<trust-anchors>` stanza added here must be coordinated with that task's XML to avoid conflicting overlays.
- rustls `ClientConfig` construction sites may be spread across multiple crates; audit scope should cover all crates opening TLS sessions to RIPDPI-operated endpoints.
- Static pinning carries operational risk on certificate rotation; the pinning evaluation must produce a written rationale either way before code is written.
- Source is observation-grade (forum reports, one MTS incident) — plausible threat, not a confirmed systematic deployment against RIPDPI traffic.

## References

- `russian-tls-mitm-certificate-interception-2026` — ntc.party t/25005 (2026-06) and 2025 MTS government-cert incident; self-signed/empty-field injection observations.
- `add-network-security-config-with-opportunistic-domainencryption` — adjacent NSC task (ECH, not trust-anchor hardening); coordinate on the network security config XML if a `<trust-anchors>` overlay is added.
- Android Network Security Configuration `<trust-anchors>` / `<certificates src="system"/>` user-CA exclusion; rustls `ClientConfig` / `RootCertStore` construction patterns.

## Phase 1 — Audit findings (2026-06-17)

All facts read from source at HEAD. **Outcome: no gap. RIPDPI's own PKI-validated
channels are already postured against the threat — and stronger than this task
assumed**, because the Rust data plane pins a *bundled Mozilla root set* rather
than reading the device trust store at all.

### Headline

The RU threat is an injected self-signed/empty-field cert mid-connection, or a
state CA delivered to the device (e.g. via MTS) and added to the **user** (or
even system) trust store. RIPDPI's own channels defeat this two ways:

- **Rust (relay backends, DNS, MASQUE, NaiveProxy):** every PKI-validated TLS
  client trusts a **bundled Mozilla root set**, not the OS store. An
  attacker-added device CA is not in that bundle, so the handshake fails;
  self-signed / empty-field certs fail chain validation. This is immune to the
  device-trust-store injection vector entirely.
- **Kotlin (GitHub asset/subscription fetch):** OkHttp's default system trust
  store + default hostname verifier, with the NSC excluding user-installed CAs
  (`targetSdk ≥ 24` default + no `<certificates src="user"/>`).

### Inventory — one row per own-channel class

| Channel class | Construction site (`file:line`) | Roots of trust | Hostname / cert verify | Posture |
|---|---|---|---|---|
| Subscription / asset fetch (GitHub) | `core/service/.../services/OwnedTlsClientFactory.kt:156` (`createForAuthority`) → only sets `connectionSpecs`; `assets/GeoAssetDownloadService.kt`, `hosts/HostPackCatalogNetwork.kt` | OkHttp default **system store** (NSC: no `src="user"`, cleartext off — `app/src/main/res/xml/network_security_config.xml`) | OkHttp default `OkHostnameVerifier` + platform `X509TrustManager` (never overridden) | **OK** |
| DNS resolver (DoH / DoT) | `native/rust/crates/ripdpi-dns-resolver/src/transport/client.rs` | `webpki_roots::TLS_SERVER_ROOTS` (+ optional caller pin verifier) | rustls full verification; `ServerName` set | **OK** |
| MASQUE (HTTP/3) | `native/rust/crates/ripdpi-masque/src/h3/transport.rs:23` | `RootCertStore::empty()` + `webpki_roots::TLS_SERVER_ROOTS` (no `insecure` path) | rustls full verification | **OK** |
| NaiveProxy | `native/rust/crates/ripdpi-naiveproxy/src/tls.rs` | `webpki_roots::TLS_SERVER_ROOTS` | rustls full verification | **OK** |
| Hysteria2 (QUIC) | `native/rust/crates/ripdpi-hysteria2/src/quic_transport/config.rs:115` via `tls_quic.rs:112` | `webpki_roots::TLS_SERVER_ROOTS` when `insecure=false` (the default) | rustls full verification | **OK** (see note) |
| ShadowTLS (outer cover handshake) | `native/rust/crates/ripdpi-shadowtls/src/handshake.rs:19` | `webpki_roots::TLS_SERVER_ROOTS` | rustls verification of the **real cover** site (correct) | **OK** |
| Trojan | `native/rust/crates/ripdpi-trojan/src/lib.rs:324` (`configure_builder`, verify left ON) | **bundled Mozilla CCADB PEM** via `ripdpi-tls-profiles/src/trust.rs::seed_default_trust` (+ optional PEM pin) | BoringSSL `SslConnector` peer verification (default ON) | **OK** |
| AnyTLS | `native/rust/crates/ripdpi-anytls/src/session.rs:367` (`configure_builder`) | bundled Mozilla CCADB PEM (`seed_default_trust`) (+ optional PEM pin) | BoringSSL peer verification (default ON) | **OK** |
| VLESS Reality | `native/rust/crates/ripdpi-vless/src/reality.rs:73` (`SslVerifyMode::NONE`) | n/a — x25519 key-share + sealed `session_id` auth | **by-design** (impersonates a real site; PKI verify would break the protocol) | **By-design, not a gap** |
| Tor bootstrap | `arti-client` | Tor directory-authority / consensus trust | Tor's own trust model (not CA PKI) | **By-design** |
| Diagnostics probes / debug local probe / test fixtures | `core/diagnostics/...`, `app/src/debug/...`, `*/tests/*`, `ripdpi-diagnostics-tls` | trust-all (intentional) | none (intentional) | **Out of scope** — non-data probe/debug/test paths, isolated by `DiagnosticsOwnedTlsSourceRulesTest`; not relay/control-plane traffic |

### Notes

- **Hysteria2 `insecure`** (`config.rs:42`) is parsed from the user's own profile
  URI (`insecure=1`), defaults `false`, and `client.rs:30` already emits
  `tracing::warn!("...certificate verification DISABLED (insecure=true profile)")`.
  It is a deliberate, user-local opt-in for self-hosted self-signed servers — a
  network MITM cannot set it (it is not negotiated on the wire). Not a gap. The
  fragile "name-gate on cover domains" idea floated during the audit was rejected
  (Reality covers are arbitrary user-chosen domains).
- **BoringSSL paths do not honour the Android NSC** (native code reads no platform
  trust path), so `seed_default_trust` shipping a bundled Mozilla CCADB snapshot
  (`ripdpi-tls-profiles/ca-bundle/mozilla-ca.pem`, ≥100 roots, CI-refreshed) is
  exactly the right design: it is independent of, and stricter than, the device
  store for the injected-CA vector.
- Empty-field / self-signed certs are rejected by default by both rustls (webpki)
  and BoringSSL chain validation — no special handling needed.

### Pinning decision

**DEFER static public-key / certificate pinning.** Rationale:

1. **No RIPDPI-operated relay endpoints exist to pin.** Per AGENTS.md there is no
   backend server; relay endpoints are entirely user-configured (the user enters
   their own VLESS/Trojan/Hysteria2/etc. server), so there is no stable
   RIPDPI-controlled identity to pin against.
2. **The bundled-Mozilla-roots posture already defeats the threat** (injected
   user/system CA, self-signed) without pinning's operational fragility.
3. **Several protocols are non-PKI** (Reality x25519 seal) — pinning is N/A there.
4. For the one fixed endpoint class (GitHub `raw.githubusercontent.com` /
   `github.com`), CA/leaf rotation would risk bricking asset fetch for marginal
   gain over the system store + bundled roots.
5. An *opt-in* per-resolver pin hook already exists where it makes sense — the DoH
   path accepts an optional custom verifier (`ripdpi-dns-resolver`); that is the
   right place to revisit if user-driven pinning is ever wanted.

### Scope limitation (per acceptance criterion)

The motivating source (`russian-tls-mitm-certificate-interception-2026`,
ntc.party t/25005 + the 2025 MTS government-cert incident) is **observation-grade
forum reporting, not a controlled measurement** of a systematic deployment
against RIPDPI traffic. The findings above are a structural posture review, not
incident response, and make no claim that these channels are currently being
attacked.
