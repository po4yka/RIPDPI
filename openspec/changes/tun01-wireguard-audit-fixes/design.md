## Context

The audit uses WireGuard paper revision e2da747 and boringtun 0.7.1 at the repository vendor path. Plain WireGuard and AWG share the transport backend but have separate padding and timer policies.

## Goals / Non-Goals

- Goal: repair F1 through F8 and the verified cookie-clock discrepancies with independent regression oracles.
- Non-goal: multi-peer routing, arbitrary raw-IP forwarding, a new backend, external probes, or changes to WARP account provisioning.

## Decisions

- Preserve import field presence in the parser. Manual replacement differs from subscription refresh.
- Keep AWG padding overrides. Install an MTU-aware plain WireGuard padding policy in the wrapper.
- Learn UDP endpoints only from authenticated accepted events. Preserve source-bound cookies, replay rejection and protect-before-use.
- Add defaulted native AllowedIPs. Old native JSON retains full-range defaults; new app activation supplies the exact profile policy. Check SOCKS admission, outgoing IP and incoming source membership. Reject incompatible remote DNS before VPN establishment rather than adding permission silently. The built-in local DNS interceptor is not a peer address and keeps its local host route.
- Keep resolution bounded and accept either family, with IPv4 preference.
- Use synthetic counter boundaries and deterministic clock tests, not packet floods.

## Contracts and ownership

Ownership and serialization are recorded in the linked portfolio task. The native JSON change is additive; producer and consumer tests must agree. No lockfile, golden or architecture baseline extension is permitted. Endpoint writes to tunnel.rs finish before integration adds ACL and padding seams.

## Risks / Trade-offs

Strict DNS policy can reject earlier split-tunnel configurations; the user receives a configuration error. Roaming must not weaken cookie or peer authentication. MTU padding must not change AWG semantics. Every substantial behavior change receives read-only review before commit.

## Migration Plan

No persistent data migration is needed. Existing profiles retain their stored fields and undergo new validation on activation. Rollback uses a new revert commit, not rewritten history. Local tests, source gates and a pinned loopback reference peer precede main integration. Android/device, hosted CI and deployment evidence stay separate.

## Cookie source follow-up

Plain WireGuard currently reduces the observed SocketAddr to an IP before cookie admission. Paper section 5.4.7 requires the source IP and UDP port. Route the full CookieSource through the default limiter. Keep the IP-only API as a documented port-zero compatibility wrapper. An established non-UDP carrier uses its local opaque connection identity. Keep AWG admission budgets unchanged. Test wrong-port and wrong-connection proof rejection before a valid source is accepted.
