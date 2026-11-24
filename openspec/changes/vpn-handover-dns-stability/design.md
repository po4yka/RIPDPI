## Context

Use the selected physical network callback, current Android TUN ownership,
and the existing telemetry loop.

## Goals / Non-Goals

- Keep components whose inputs and physical route remain valid.
- Measure forwarding without adding an external probe or claiming same-flow
  reachability from aggregate counters.
- Preserve bounded, encrypted alternatives and existing privacy exclusions.

## Decisions

- Compare an ephemeral physical route signature that includes the network
  handle, addresses, routes and MTU. Preserve the Android metered policy.
- Reuse the configured native proxy only with matching inputs, ready evidence
  and no composed upstream. Use the network scope as a proxy input when host
  autolearn is active. Replace the TUN when its configuration requires it.
- Use distinct runtime-ready status. Promote current-generation native
  forwarding evidence from the existing passive collector. A subsequent network
  observation supersedes an in-flight sample. Guided acceptance can retry with
  fresh UDP payload evidence after revalidation.
- Count deltas of typed encrypted resolver failures. The bridge lease supplies
  the telemetry generation. On a shared proxy, count only the latest
  attributable failure. Mixed samples reset the failure streak and contribute
  at most one failure. After exhaustion, wait 30 seconds before another round.

## Contracts and ownership

The native tunnel and Android telemetry consumer share an optional DNS counter. The
new physical signature is transient and excluded from public evidence and scope
keys. No JNI signature, storage schema, or profile migration.

## Risks / Trade-offs

A physical network change may break the previous transport connections. The
VPN preserves the barrier during replacement. A route with insufficient
forwarding evidence stays ready and unverified.

## Migration Plan

No data migration. Revert the two fixes to roll back. Verify affected service,
data and app tests, static analysis and repository source gates. Keep physical
switch and forwarding acceptance separate.
