## Context

Task ID: `SVC-1794477234440384`. See proposal.md. The AWG supervisor owns native instances but currently forwards every established carrier exit to fail-and-stop.

## Goals / Non-Goals

- Goal: recover established WS sessions with bounded ownership and cancellation.
- Non-goal: change UDP recovery, server wire format, keys, TUN routes, or persistence.

## Decisions

- Keep recovery in the AWG supervisor. Existing TUN and proxy remain installed while the local AWG SOCKS listener is replaced.
- Use three fresh native attempts after 1, 2, and 4 seconds. Each attempt must pass existing authenticated readiness.
- A session token rejects completion from an older start; Stop cancels and joins recovery before releasing runtime ownership.
- Keep startup cleanup separate from public Stop so cleanup does not cancel its own recovery coroutine.

## Contracts and ownership

- Recovery worker owns `:core:service` AWG supervisor and tests.
- Readiness worker owns the timeout helper and its awaitReady call-site edit before this branch rebases.
- No Rust, JNI, wire, persistence, locale, or serialized shared file change.
- VPN bootstrap calls the existing generation-checked underlay resolver on IO, with a 10 second deadline. Numeric results travel in nullable carrierWsAddresses; null permits deliberate non-VPN legacy lookup, empty fails closed. URL Host/SNI stay unchanged. Device underlay acceptance remains separate.

## Risks / Trade-offs

- Existing TCP streams can fail when a native instance is replaced; future connections use the same SOCKS endpoint.
- Stop racing readiness must suppress any further runtime creation or terminal callback; cover with virtual-time tests.
- Native readiness budgets cap each attempt. Recovery exhaustion retains existing failure policy.

## Migration Plan

No migration. Revert this change to restore fail-stop. Validate targeted supervisor tests, `:core:service:compileDebugKotlin`, and `just ci-gates` on the combined tree. Device recovery is a separate acceptance check.
