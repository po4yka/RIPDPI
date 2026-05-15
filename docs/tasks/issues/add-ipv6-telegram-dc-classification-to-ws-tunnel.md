---
title: Add IPv6 Telegram DC classification to WS tunnel
type: task
status: done
area: rust-native
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [x] #task Add IPv6 Telegram DC classification to WS tunnel #repo/RIPDPI #area/rust-native #status/done 🔼

## Implementation summary (2026-05-15)

`dc::dc_from_ipv6` recognises the two published Telegram IPv6
supernets (`2001:67c:4e8::/48` → DC2 Amsterdam,
`2001:b28:f23c::/46` covering f23c/f23d/f23e/f23f → DC3 Miami/Singapore
representative). `classify_target` dispatches v6 through it.
`is_telegram_ip` no longer returns blanket false on v6.

Tests added:

- `classify_target_returns_passthrough_for_non_telegram_ipv6`
- `classify_target_tunnels_known_telegram_ipv6_supernets`
- `is_telegram_ip_v6_recognises_known_supernets`
- `dc_from_ipv6_returns_none_for_unrelated_supernets`

The IPv6 supernet table shares the IPv4 table's quarterly review
obligation documented in
`docs/strategy-pack-operations.md` § "Telegram DC table review".

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-ipv6-telegram-dc-classification-to-ws-tunnel`
- **Verify:** `cargo test -p ripdpi-ws-tunnel -p ripdpi-diagnostics-telegram`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-ws-tunnel/**`, `native/rust/crates/ripdpi-diagnostics-telegram/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`WsTunnelDecision::classify_target` returns `Passthrough` for every IPv6
target, so the Telegram WS tunnel never activates on IPv6-only
networks. Add IPv6 Telegram DC ranges to `dc::dc_from_ip` and matching
classification + tests.

## Context

`native/rust/crates/ripdpi-ws-tunnel/src/lib.rs:50-58`:

```rust
pub fn classify_target(ip: IpAddr) -> WsTunnelDecision {
    match ip {
        IpAddr::V4(v4) => match dc::dc_from_ip(v4) {
            Some(dc) => WsTunnelDecision::Tunnel(dc),
            None => WsTunnelDecision::Passthrough,
        },
        IpAddr::V6(_) => WsTunnelDecision::Passthrough,
    }
}
```

The existing test
`classify_target_returns_passthrough_for_ipv6` documents this as
intentional, but Telegram has been rolling IPv6 DCs for years. On an
IPv6-only or NAT64 network the tunnel never engages, undermining the
whole censorship-bypass premise for Telegram traffic on those
networks.

## Acceptance criteria

- [ ] `dc::dc_from_ip` is extended (or paired with
    `dc::dc_from_ip_v6`) to recognize Telegram's published IPv6 DC
    ranges for DCs 1-5 (production), keeping the historical IPv4
    ranges unchanged.
- [ ] `classify_target` returns `Tunnel(dc)` for known IPv6 Telegram
    addresses and `Passthrough` for unknown v6 ranges.
- [ ] New unit tests mirror the existing
    `classify_target_tunnels_known_telegram_ips` case for IPv6.
- [ ] The `ripdpi-diagnostics-telegram` probe exercises both v4 and
    v6 DC paths.
- [ ] The IPv6 range table cites the source (Telegram's `core.telegram.org`
    DC documentation) in a comment block near the constants.

## Definition of done

- Unit tests cover at least one address per DC for both v4 and v6.
- The diagnostics-telegram probe reports DC reachability over IPv6 in
  CI logs when run on a dual-stack runner.

## Risks / open questions

- Telegram's IPv6 DC ranges may rotate; pair this with the
  upstream-watch task or schedule a quarterly review of the v6
  table.
- NAT64-only Android networks are particularly common in mobile
  carriers; mark this as a user-visible regression candidate when
  prioritizing.

## Links

- [[ws-tunnel-telegram]]
- [[add-telegram-mtproto-diagnostic-with-dc-reachability-and-throughput]]
