---
title: Harden TLS certificate-validation posture against observed RU active-MITM
type: task
status: backlog
area: transport
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-15
updated: 2026-06-15
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

- [ ] Written inventory of TLS trust configuration for each RIPDPI-owned outbound channel class (subscription/control-plane HTTP clients; rustls relay-backend connections) with file+line references and gap/fix summary.
- [ ] Any subscription/control-plane HTTP client confirmed to exclude user-installed CA trust and enforce hostname verification; if a gap was found, a fix is landed with a unit test where a mock self-signed certificate must be rejected.
- [ ] Any `rustls::ClientConfig` used for RIPDPI-operated relay connections confirmed to use roots-of-trust excluding user CAs and to reject self-signed/empty-field certificates; if a gap was found, a fix is landed with a negative-path test.
- [ ] Pinning decision recorded (adopt with rotation plan, or explicit defer with rationale).
- [ ] `cargo nextest run --locked` green on touched crates; Android unit tests green; clippy clean.

## Risks / open questions

- `add-network-security-config-with-opportunistic-domainencryption` covers the Android NSC XML for ECH; any `<trust-anchors>` stanza added here must be coordinated with that task's XML to avoid conflicting overlays.
- rustls `ClientConfig` construction sites may be spread across multiple crates; audit scope should cover all crates opening TLS sessions to RIPDPI-operated endpoints.
- Static pinning carries operational risk on certificate rotation; the pinning evaluation must produce a written rationale either way before code is written.
- Source is observation-grade (forum reports, one MTS incident) — plausible threat, not a confirmed systematic deployment against RIPDPI traffic.

## References

- `russian-tls-mitm-certificate-interception-2026` — ntc.party t/25005 (2026-06) and 2025 MTS government-cert incident; self-signed/empty-field injection observations.
- `add-network-security-config-with-opportunistic-domainencryption` — adjacent NSC task (ECH, not trust-anchor hardening); coordinate on the network security config XML if a `<trust-anchors>` overlay is added.
- Android Network Security Configuration `<trust-anchors>` / `<certificates src="system"/>` user-CA exclusion; rustls `ClientConfig` / `RootCertStore` construction patterns.
