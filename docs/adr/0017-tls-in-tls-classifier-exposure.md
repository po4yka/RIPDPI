# ADR 0017: TLS-in-TLS Classifier Exposure of Proxy Transports

- Status: Accepted (research-only, conditional-go for a repo-local measurement; shaping spike externally gated)
- Date: 2026-11-27
- Task: `TRN-1795705033979683` (epic `EPC-1786264762917282`)
- Server counterpart: `po4yka/ripdpi-vpn-deploy` task `SEC-1795706124070316`. The two tasks are one study. This repository owns the measurement; the deploy repository consumes the conclusion for its XHTTP profile.

## Context

Most traffic that a user sends through a proxy transport is HTTPS. When the transport itself runs over TLS, the wire carries an inner TLS handshake inside an outer TLS session. The inner handshake has a stable shape: a client flight, a larger server flight, a short client finish, then application data. Encryption hides the bytes but not the record sizes, directions, and round-trip count. A passive classifier can match that nested shape at the start of the outer connection. Public research describes this class of detector, for example "Fingerprinting Obfuscated Proxy Traffic with Encapsulated TLS Handshakes" (USENIX Security 2024).

An academic preprint dated 2026-11-23 reports that shaping a proxy so that its traffic resembles one TLS server cut a classifier's detection rate from over 85% to about 3% at a 1% false-positive rate. This ADR uses only the abstract-level claim. The study has one source, it is not reproduced here, and its data came from a non-Russian ISP.

## Inventory

The table lists every relay kind in `native/rust/crates/ripdpi-relay-core/src/transport_descriptor.rs` and the Kotlin-only pluggable transports. "Inner TLS" means that a user HTTPS session appears as a nested TLS handshake inside the transport's own outer TLS or QUIC-TLS session. Exposure is a design-level estimate from the mechanisms present in code, not a measurement.

| Transport | Outer layer | Inner TLS inside outer TLS | Mitigations in code | Estimated exposure |
| --- | --- | --- | --- | --- |
| VLESS, raw TCP | TLS | Yes | XTLS Vision is the default flow (`default_vless_flow`): padded chunks over the inner handshake, then a splice to the raw layer for inner TLS 1.3. Optional VLESS mux with padding. | Medium. Padding covers only the handshake records. The splice removes double encryption, not the nested handshake shape. |
| VLESS Reality, raw TCP | Reality TLS (borrowed cover-server handshake) | Yes | Same as VLESS raw. The outer handshake looks like the cover server. | Medium. Reality hides the outer handshake, not the inner one. |
| VLESS / VLESS Reality over xHTTP | TLS with HTTP/2 or HTTP/3 framing | Yes | Vision is refused on xHTTP (the builders return `Unsupported`). Optional finalmask (noise, fragmentation, custom header, substitution masks), off unless configured. HTTP framing and request splitting change record boundaries. | Medium to high. No handshake padding by default. This is the profile that the deploy counterpart ships. |
| Cloudflare Tunnel | TLS to a CDN edge, xHTTP stream-up | Yes | Same xHTTP path. Shared CDN address space gives collateral-cost cover, not shape cover. | Medium to high. |
| Trojan | TLS | Yes | None at the protocol level. The relay-mux pool can merge streams. | High when mux is off. |
| AnyTLS | TLS | Yes | A padding scheme that exists to break this pattern: it pads and splits the first packets of each session by default. Relay-mux pool. | Low to medium. |
| ShadowTLS v3 | Real cover-server handshake, then authenticated application-data records | Yes, through the inner protocol | ShadowTLS adds no padding. The inner relay is VLESS with Vision by default, so it inherits Vision padding. | Medium. Equal to the inner protocol. |
| NaiveProxy | TLS, HTTP/2 CONNECT | Yes | Length padding on the first frames in each direction. HTTP/2 stream multiplexing. | Low to medium. |
| Chain relay | Outermost hop | Inherited | Inherited from the hops. | Equal to the outermost hop. |
| Tor via webtunnel | TLS, HTTP upgrade | Yes, two levels (Tor link TLS, then user TLS) | Tor cell framing gives fixed-size cells. | Medium. Fixed cells blur sizes but the nested round trips stay. |
| Tor via snowflake | DTLS (WebRTC) | Yes, inside DTLS | Tor cells, WebRTC data channel framing. | Not a TLS-over-TCP case. Out of the preprint's scope. |
| Tor via obfs4 | Randomized stream, no TLS | No outer TLS | obfs4 length and timing obfuscation. | Out of scope (no outer TLS). |
| Hysteria2, TUIC v5, MASQUE | QUIC (TLS 1.3 inside QUIC) | Yes, as TLS-in-QUIC | Hysteria2 offers optional Salamander obfuscation, which hides the QUIC layer but not the inner sizes. QUIC packet coalescing. | Unknown. Same nested signal on UDP. The preprint does not cover it. |
| Shadowsocks, Snell, SSH | Encrypted stream or SSH, no TLS | No outer TLS | Not assessed here. SSH has its own record framing. | Out of scope. The encapsulated-handshake signal still exists, but a different classifier class applies. |
| Mieru | Encrypted stream, no TLS | No outer TLS | Built-in padding and traffic-pattern shaping. | Out of scope. |
| WireGuard, AmneziaWG, WARP | UDP tunnel | No outer TLS | AmneziaWG junk packets and header changes. | Out of scope. |
| Telegram WebSocket tunnel | TLS, WebSocket | No (inner MTProto, not TLS) | Not applicable. | Not exposed to this signal. |

The external Xray provider path runs the same protocol families through xray-core and has the same exposure per family.

## What transfers from the preprint

- Transfers: the mechanism. Nested-handshake shape is a real signal for every row marked "Yes", and shaping the outer session toward one fixed TLS server profile is a plausible counter, because it attacks the size and timing features that the detector uses.
- Transfers with doubt: the size of the effect. A drop from over 85% to about 3% comes from one abstract, one classifier, and one ISP. The numbers depend on the classifier's features and on the base rate of the cover traffic in that network.
- Does not transfer: the operating point. Russian TSPU equipment is not known to run this classifier class at scale, its false-positive budget is unknown, and blocking there is mostly keyed on addresses, SNI, and protocol class. A 1% false-positive rate is an academic setting, not a known operator setting.
- Does not transfer: QUIC rows. The preprint covers TLS over TCP. TLS-in-QUIC needs its own measurement.

## Measurement the client can run

The repository can measure the nested-handshake shape without field data or a backend. The measurement is design-level here; no implementation is in scope for this ADR.

1. Run each transport against its own loopback server in the existing protocol loopback harness, with a fixed inner HTTPS request.
2. Capture the outer connection with the existing pcap writer.
3. Extract a per-connection vector: size, direction, and inter-arrival time for the first records after the outer handshake.
4. Capture a direct TLS session to a cover server profile as the baseline.
5. Report, per transport, how far the nested vector is from the baseline and from the unmitigated case (Trojan without mux). A simple distance or threshold score is enough to rank transports; a trained classifier is not needed for the first pass.

This gives a repo-local exposure ranking. It does not show whether a real operator acts on the signal; that needs RU vantage points and stays gated with the operator-signature task in the same epic.

## Decision

- Conditional-go for the measurement above, as a repo-local first slice. It is the gate for any shaping work.
- Externally gated for a "shape toward one TLS server" spike. It needs (a) the measurement to show that xHTTP or Trojan sessions separate clearly from the baseline, and (b) evidence from RU vantages that operators use a flow-shape classifier.
- No change to defaults now. Vision stays the default VLESS flow. AnyTLS and NaiveProxy padding stay on.
- Drop condition: if the measurement shows that the default-configured transports (Vision, AnyTLS, NaiveProxy, xHTTP) do not separate from the baseline, record a drop and close the spike.

## Shared findings for the deploy counterpart

This section is written so that `SEC-1795706124070316` can cite it directly.

- The client ships xHTTP without handshake padding. Vision is refused on xHTTP, and finalmask is off unless the profile sets it. The XHTTP profile is the most exposed TLS-over-TCP profile that the client ships with default settings, together with Trojan.
- Mitigations that need server cooperation: Vision padding, AnyTLS padding, NaiveProxy padding, and mux padding all need a server that understands them. Any server-side profile change that removes them raises exposure on the client.
- Shaping toward one TLS server profile is a two-sided change: the server must send its downlink in the same shape. The client cannot do it alone, so a spike needs the deploy repository as a co-owner.
- The measurement owner is this repository. The deploy repository should not repeat the measurement; it should state its XHTTP profile's server-side mitigations so that the client measurement covers the same configuration.
- Evidence limits apply to both sides: single-source, abstract-level, non-Russian ISP data.

## Revisit triggers

- The preprint's full text or an independent replication becomes available.
- Field evidence shows RU operators blocking on flow shape rather than on address, SNI, or protocol class.
- A new transport or a default change removes padding from a TLS-over-TCP profile.
