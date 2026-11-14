## Context

DPI-32 controls receive socket options. Standalone registry window strategies have no production socket executor. The user authorized repairs, separate commits, main integration, and push.

## Goals / Non-Goals

- Goal: Fix all confirmed findings and automatic-scale round trips.
- Non-goal: Add packet rewriting, exact WS negotiation, root activation, or a per-round controller.

## Decisions

- Keep field names and numbers; document the buffer shift hint.
- Share bounded buffer arithmetic in WsizeConfig; reject invalid active CLI/native JSON requests. UI zero remains disabled. Use checked signed conversion in setters.
- Preserve absent scale with the existing sentinel -1.
- Use the existing proxy event ring through runtime-platform wrappers. Keep io::Result and payload fallback. Effective values require readback. No new telemetry schema.
- Reject standalone window YAML at production admission; retain the library planner.
- Strengthen wire assertions and add pure decoder regressions. Privileged capture remains unrun without authorization.

## Contracts and ownership

- Integrator owns portfolio/OpenSpec, production admission, generated API snapshots, combined gates, and integration.
- window_values owns ripdpi-config model/CLI, proxy-config numeric conversion, proxy-runtime-adapter model/config/tcp_connect.rs, proto comments, Kotlin scale writer/tests.
- window_values owns privileged-ops/linux/socket_options.rs until its numeric commit. Then window_outcomes owns getters/facade exports there. This shared-file lane is serialized.
- window_outcomes owns runtime-platform socket wrappers/events, desync-runtime action completion and related tests. Diagnostic eligibility is not observed application.
- window_wire owns proxy-runtime/tests/wire_e2e.rs and tests/support/wire*. No production edits.
- Each writer uses an isolated worktree. Cargo.lock, dependency versions, locales, goldens and baselines are outside all lanes. Integrator regenerates API snapshots on the combined tree.
- Pins: Rust1.100.0, libc0.2.191, socket2 0.6.5, tracing0.1.44, tokio1.54.1. Socket code is synchronous per-connection OS-thread code.

## Risks / Trade-offs

- Strict admission refuses former no-op/oversized inputs. Boundary tests preserve valid settings.
- Events occur at option requests, not per-packet, and exclude sensitive data.
- Socket readback does not prove Android policy or wire behavior. Keep evidence categories separate.

## Migration Plan

No stored schema migration. Valid requests retain numeric behavior; absent scale remains automatic after save. Invalid requests and unsupported window YAML fail admission. Rollback uses ordinary revert commits.

## Validation strategy

Targeted Rust and Kotlin regression suites, independent review, just ci-gates, architecture health, locked cargo metadata, and affected-package checks. All heavy commands use build-gate --lane ripdpi and worktree-isolated targets with at most four workers. No physical, root, or external probes.
