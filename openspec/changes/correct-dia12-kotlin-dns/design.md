## Context

The Kotlin tools run UDP A, binary DoH A, vendor JSON A, and optional HTTPS readiness lookups. The native connectivity scan and runtime encrypted DNS resolvers are separate paths.

## Decisions

- Use typed HTTPS outcomes with fallback on the original origin and a bounded alias chain. Pass dial endpoint fields through the internal ECH request when the selected binding is supported. Keep DNS, connection setup, and TLS results separate.
- Use shared bounded DNS HTTP readers and cancellation tied to the active exchange. Enforce provider JSON types only for fields the A adapter uses.
- Keep dnsjava as the wire owner. Apply strict HTTPS and OPT checks to the current packet, use effective TTL for resource records, and add EDNS separately from UDP sizing.
- Base comparison confidence on measured legs and exact addresses. Mark same-subnet agreement as partial. Require observed response evidence before classifying a page.
- Use the DNS transaction ID policy of each transport. Document private sizing without adding encrypted padding to the scope.

## Contracts and ownership

The portfolio assigns three writer worktrees. The integrator owns locale strings and generated snapshots. The ECH JSON request is internal to the app and native bridge. DNS evidence fields are optional in recorded data so prior reports can still show unknown coverage.

## Validation and rollback

Run the Kotlin diagnostics-data, diagnostics, and app JVM tests. Run the locked Rust tests for the ECH adapter and local protocol fixtures. Run `just ci-gates` before integration. Keep hardware and public-provider acceptance separate. Revert the paired Kotlin and native ECH contract together if necessary.
