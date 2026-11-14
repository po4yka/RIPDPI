## Context

Task ENG-1794637907795927 corrects audit defects in an existing raw header-version mutation. It does not add an endpoint.

## Goals / Non-Goals

- Goal: bounded resources, correlated accounting, bounded probe receive, and correct names.
- Non-goal: change XOR values, packet bytes, TTL/CID policy, add evasion techniques, or build a VN/HTTP3 endpoint.

## Decisions

- Reuse the existing maximum of 64 for DPI-24 count and aggregate repeats only. Other tactics retain their admission rules.
- Normalized input is bounded by u16::MAX; limiting copies bounds retained packet payload to about 4 MiB.
- Retain the supported Initial until a correlated Initial/Retry is accepted. Follow-up packets do not bypass correlation. Release retained data after acceptance.
- Use existing packet validators through the runtime adapter. Forward unrelated UDP but do not count it as QUIC success or accepted session traffic.
- Reproduce the early-reply hypothesis first. A filtered receive API shares one deadline across direct, SOCKS, and route-experiment paths; generic callers keep current behavior.
- Preserve saved executable IDs and old human report aliases; change displayed names only.

## Contracts and ownership

The portfolio task records all module owners and serialized lanes. The integrator owns task files and generated API snapshots. No dependency, schema, baseline, or golden change is required.

## Risks / Trade-offs

- Unsafe formerly accepted counts now fail; test zero, 64, 65, i32::MAX, and repeated sums.
- Correlation must preserve generic UDP and post-handshake behavior; test both and outbound follow-up.
- Rejected replies must not refresh deadlines; test continuous unrelated traffic.
- Locale text can affect visual tests; inspect without unauthorized blessing.

## Migration Plan

No stored-data migration. Revert individual fix commits to roll back. Before integration run affected tests, locale lint, staticAnalysis, ci-gates, architecture health, locked Cargo metadata, and independent review. Device, external-network, and deployment proof are not required for this local correction.
