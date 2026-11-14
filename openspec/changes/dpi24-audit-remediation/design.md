## Context

Task ENG-1794637907795927 corrects audit defects in an existing raw header-version mutation. It does not add an endpoint.

## Goals / Non-Goals

- Goal: bounded resources, correlated accounting, bounded probe receive, and correct names.
- Non-goal: change XOR values, packet bytes, TTL/CID policy, add evasion techniques, or build a VN/HTTP3 endpoint.

## Decisions

- Reuse the existing maximum of 64 for DPI-24 count and aggregate repeats only. Other tactics retain their admission rules.
- Normalized input is bounded by u16::MAX; limiting copies bounds retained packet payload to about 4 MiB.
- Retain the supported Initial until a correlated Initial/Retry is accepted. Follow-up packets do not bypass correlation. Release retained data after acceptance.
- Keep the single-version active-probe validator strict. Add a request-aware runtime validator through the existing adapter boundary. For compatible v1/v2 Initials, check the announced client version and use the response crypto profile with the DCID carried by the retained request. Retry retains the original version.
- Preserve RFC 9287 Fixed Bit greasing for Initial and original-version Retry in the runtime gate. Verify integrity with the original header bytes and require one empty client grease_quic_bit parameter when the complete offer is available. An unavailable offer uses the same unverified, neutral-expiry policy as compatible versions.
- If a complete ClientHello offer is unavailable, a CID- and integrity-valid compatible Initial is unverified. Forward it without success/session/migration acceptance and use neutral idle expiry without failure learning or route fallback. This avoids both a false success and a false failure without adding cross-datagram reassembly.
- Forward unrelated UDP but do not count it as QUIC success or accepted session traffic. Unverified compatibility can arise only after valid CID and AEAD, never from VN or invalid integrity. Reset neutral state on a new socket, target, or connection context, and keep hard socket errors actionable. Parse a complete offer independently of SNI.
- Reproduce the early-reply hypothesis first. A filtered receive API shares one deadline across direct, SOCKS, and route-experiment paths; generic callers keep current behavior.
- Preserve saved executable IDs and old human report aliases; change displayed names only.
- Upstream `90728fab83` added multiplexed QUIC connections during this work. Preserve its 64-context limit, deduplication, no-eviction policy, and capacity-neutral activity/expiry. Store compact packet-owned CID/version/offer contexts and one latest raw request. A delayed unknown offer for A must not suppress the pending B timeout. Replace upstream header-only/VN success assertions with independently protected replies; retain their multiplexing and forwarding intent.

## Contracts and ownership

The portfolio task records all module owners and serialized lanes. The integrator owns task files and generated API snapshots. No dependency, schema, baseline, or golden change is required.

## Risks / Trade-offs

- Unsafe formerly accepted counts now fail; test zero, 64, 65, i32::MAX, and repeated sums.
- Correlation must preserve generic UDP and post-handshake behavior; test both and outbound follow-up.
- Rejected replies must not refresh deadlines; test continuous unrelated traffic.
- Locale text can affect visual tests; inspect without unauthorized blessing.

## Migration Plan

No stored-data migration. Revert individual fix commits to roll back. Before integration run affected tests, locale lint, staticAnalysis, ci-gates, architecture health, locked Cargo metadata, and independent review. Device, external-network, and deployment proof are not required for this local correction.
