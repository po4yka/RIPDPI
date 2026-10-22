## Context

Task VPN-1792657107995294 fixes the five defects in proposal.md. Source starts at origin/main 8f7706412; deployment compatibility uses the current local sibling checkout and records its SHA.

## Goals / Non-Goals

- Goal: remove credential disclosure, restore protected hostname resolution and runtime cleanup, schedule new subscriptions, and preserve recipient configuration correctness.
- Non-goal: implement research protocols, change live servers, alter schemas, weaken TLS checks, or claim physical-device acceptance from host tests.

## Decisions

- SHARE removes subscription URLs entirely because any URL component can be a bearer credential. FULL retains the original values.
- Relay JNI implements the existing resolveHost callback, preserving underlying-network generation checks and failure propagation. No fallback uses system DNS inside the VPN.
- Cleanup detaches only completed components and stops live siblings. A pending cleanup error retains the runtime reference and blocks replacement.
- New long-lived imports set autoUpdate and schedule using persisted state. Existing false values are preserved because storage has no explicit-opt-out marker. Bootstrap stays one-time.
- Import resolves nested selectors with bounded traversal and cycle detection where safe. Unsupported-only selections remain explicit failures. Refresh does not erase valid relay members through an AWG-only fallback after a selector failure.
- Compatibility tests use synthetic backend payloads and byte comparisons of existing contract fixtures. No real credentials enter tests or reports.

## Contracts and ownership

- Native writer owns relay-android JNI callback and its tests.
- Lifecycle writer owns service runtime stack, exit handlers, supervisors and tests.
- Privacy/subscription writer owns backup redactor, import confirmation, worker scheduling and tests; each defect has its own commit.
- Integrator owns selector parser, refresh fallback, compatibility tests, documentation and tasking artifacts.
- Cargo.lock, dependency catalog, protobuf, locales, golden fixtures and baselines have no planned edits.

## Risks / Trade-offs

- Callback exceptions or malformed addresses must fail closed; actual adapter tests use a host JVM.
- Cleanup must not join its own callback job; completion handlers dispatch shutdown separately.
- Nested selector flattening must preserve supported order and reject cycles, unknown tags and unsafe default substitution.
- Existing subscription opt-outs remain unchanged; this avoids an unsolicited network-policy migration.

## Migration Plan

No storage or wire migration is required. Deliver one Conventional Commit per fix, review before commit, rebase onto origin/main, run combined-tree gates, fast-forward main and push as authorized. Rollback is a normal revert. Device and live-server acceptance remain explicit separate evidence gates.
