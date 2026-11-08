## Context

Task ID: `VPN-1794141873537976`. The audit baseline is 28eeb212e804f8adcee3daeba2b90a70976c2bed. The tunnel shares one SOCKS association across UDP destinations and keeps separate reply and DNS lease tables. Xray currently receives a native-only authority frame without parser support.

## Goals / Non-Goals

- Goal: correct the five reported faults with bounded state and observed regression tests.
- Non-goal: add SOCKS fragmentation, IPv6 jumbograms, a required backend, root requirements, or new dependencies.
- Non-goal: claim device or external compatibility from host results.

## Decisions

- F2: bound destination admission before queueing, sending, or leasing. Never replace an admitted live response identity to admit another destination.
- F3: give each destination an activity token. Retire inactive destinations independently, invalidate old queued responses, and release a synthetic lease only when no current destination needs it. Use a monotonic activity clock. This preserves active flows instead of periodically replacing the whole source transport.
- F4: separate receive frame size from TUN MTU. Use a lazy bounded receive pool with one spare truncation-detection byte. Build the complete UDP checksum before splitting return IP packets into valid IPv4 or IPv6 fragments. Preserve existing accepted MTU configuration.
- F5: validate IP/UDP input at the routing boundary. Keep IPv4 zero UDP checksum permitted. Repair malformed test fixtures instead of weakening negative checks.
- F1: retain the managed host plus pinned-IP wire contract. Extend only the managed pinned Xray SOCKS path, preserve the domain as route authority and the numeric endpoint as delivery target, and isolate dispatch/reply identity by authority. Standard numeric-only or domain-only conversion cannot preserve this contract. Prefer existing Xray dispatcher primitives; do not change the shared TUN source key if the managed dispatcher can isolate destinations.
- Reproduce starvation and source-lifetime hypotheses before making additional behavior changes. Do not alter Android app policy based only on a possible late UDP response.

## Contracts and ownership

- Coordinator owns task/OpenSpec, ingress validation, combined validation, and integration.
- Association worker owns UDP destination admission, activity tokens, and mapping lease cleanup. F2 and F3 use separate commits.
- Datagram worker owns session receive storage, return packet fragmentation, and their tests. Its receive branch change is integrated after the association commits.
- Provider worker owns the maintained Xray patch and its managed inbound integration/tests. The Xray patch manifest hash is serialized to this worker. The coordinator updates generated contract snapshots only through their owning commands.
- No Cargo.lock, dependency version, proto, locale, golden, or quality baseline change is planned. Each writer uses a separate worktree. Review agents remain read-only.

## Risks / Trade-offs

- Late responses and synthetic reuse can mislabel data. Invalidate destination tokens before releasing leases and check tokens at TUN delivery.
- Fragmentation can corrupt checksums or offsets. Use independent reassembly and checksum oracles for both IP families.
- Xray may cache one dispatch ray for several authorities. Test two domains at one IP and port, with distinct routes and reversed replies.
- A bounded receive pool does not alone bound decoded worker payloads. Retain association/event/TUN queue limits and describe all retained storage accurately.
- The baseline has two unrelated VPN-02 host test failures. Report them separately; do not disable them or change quality baselines.

## Migration Plan

No persisted data migration is needed. Managed Xray patch and inbound configuration must ship together. Revert the affected repair commit to roll back its behavior. Run regression tests, relevant lint/build gates, ci-gates, architecture health, locked metadata, and independent review before integration. Device and external evidence remain explicit pending checks unless authorized and observed.
