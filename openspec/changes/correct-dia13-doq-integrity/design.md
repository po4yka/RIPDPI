# Design

Task ID: `DGN-1794744781459086`

## Context

The Kotlin tool uses a native RFC 9250 exchange per provider and domain. The DoH binary and vendor JSON lanes each select a resolver. Their observations have different source and policy.

## Goals / Non-Goals

- Preserve response outcome, source and coverage through the dedicated tool and suite.
- Keep the IN/A diagnostic and Direct DoQ scope.

## Decisions

- Compare individual resolver observations. Treat a missing baseline or a different policy as inconclusive. Keep address overlap and conflicting baselines distinct from full agreement.
- Validate the local frame before JNI and enforce the DNS message bound in both directions. Keep the wire ID zero and restore the local correlation ID.
- Keep TLS, ALPN, QUIC transport, response format and deadline failures distinct. Allocate part of the remaining budget to alternate bootstrap addresses and the query.
- Reserve, cancel and release individual JNI requests with the existing JNI lifecycle pattern. Bound platform DNS calls that can finish after cancellation. Request cancellation and stream cleanup use the existing resolver behavior.
- The stub-address preflight runs only the work that produces its result.

## Contracts and ownership

- The task record assigns the Kotlin comparison and UI, Rust resolver and native adapter, and Kotlin bridge lanes.
- Integration owns generated API and JNI inventories and verifies the combined contracts.

## Risks / Trade-offs

- A conservative comparison can present fewer agreements when the survey selected resolvers from other providers. This reflects the available evidence.
- The platform lookup can finish after the exchange ends. Its permit remains held until it returns.

## Migration Plan

- Update the caller and native adapter in the same application release.
- Validate the Kotlin contract and local QUIC wire behavior on the owning branches, then run the combined gates.
