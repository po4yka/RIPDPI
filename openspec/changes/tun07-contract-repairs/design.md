## Context

Task RLY-1794477185649013 follows the TUN-07 audit at 3e626d3e205f27c90dd84a9732f1f96074ed15f1. The external helper implements obfs4; it does not implement Tor streams.

## Goals / Non-Goals

- Goal: fix relay selection, identity preflight, managed startup, stop ownership and encoding with existing modules.
- Non-goal: add UDP, enable VPN without protection, add a Tor implementation, or change protocol dependencies.

## Decisions

- Reject the unsupported standalone relay and keep the existing Tor path. An automatic kind alias would change captured runtime identity and applied receipts.
- Extract only the endpoint for Tor permission checks; existing validation and Arti retain identity ownership.
- Use the existing subprocess output parser for managed startup state. Keep other subprocess contracts unchanged.
- Guard closed state and socket admission with one lock. Register upstream sockets before connecting.
- Add defensive regression coverage in owning suites; use real loopback sockets for lifecycle checks.
- Check the obfs4 unpadded Base64 certificate format and 52-byte length before Tor activation. Keep the helper's validation and authentication checks.

## Contracts and ownership

The portfolio record assigns distinct file ownership to three workers. The coordinator owns task records and combined validation. No serialized dependency, schema, locale, baseline or golden file is assigned for changes. The PT worker exclusively owns the manager and SOCKS adapter.

## Risks / Trade-offs

Existing standalone profiles become explicitly unsupported instead of silently sending application data to a Tor ORPort. UI tests must verify that Tor remains selectable. Affected goldens require separate approval; do not bless them. Managed transcript changes must accept current Lyrebird and Rust WebTunnel output. Real helper and ABI evidence are recorded separately from local test doubles.

## Migration Plan

Keep stored relay IDs and bridge lines. Show an actionable rejection for legacy standalone use. Validate each atomic fix, review the combined diff, run source gates, rebase on origin/main, and integrate fast-forward. Push is authorized by the user. Rollback uses a normal revert if later requested.
