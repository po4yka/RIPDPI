## Context

The service recovery owner supplies fresh underlay DNS results for each attempt. The native carrier currently resolves the URL host again.

## Goals / Non-Goals

- Goal: use numeric candidates without changing authentication or URL identity.
- Non-goal: implement a resolver in the carrier or change encapsulation.

## Decisions

- Use nullable carrierWsAddresses: missing preserves legacy bootstrap, explicit empty fails closed.
- Validate all candidates before connection. Connect protected sockets to those IPs and URL port, retaining the endpoint for TLS and Upgrade.
- Keep the public host-resolving connector for intentional host callers.

## Contracts and ownership

- Native lifecycle agent owns Rust AWG DTO, connector, and tests. Recovery agent owns Kotlin DTO and underlay provision.
- No dependency, lockfile, baseline or locale changes.

## Risks / Trade-offs

- Missing versus empty must remain distinct across serializers; test explicit rejection.
- Device acceptance is separate from host tests.

## Migration Plan

No migration. Additive DTO default. Revert client provision and native connector together. Run carrier/core tests and source gates before integration.
