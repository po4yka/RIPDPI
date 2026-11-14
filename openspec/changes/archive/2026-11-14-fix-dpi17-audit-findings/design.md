## Context

Task ENG-1794637889757963 covers the three confirmed audit findings. The current planner allocates an unbounded prelude before the original.

## Goals / Non-Goals

- Goal: bound local resources and align validation, UI text and the wire regression.
- Non-goal: change adjacent strategies, implement a QUIC endpoint or add device, root or external probes.

## Decisions

- Limit DummyPrepend to 64 per step and per UDP chain, following the finite FakeBurst precedent.
- Give direct-model planning a shared 64-attempt budget. Keep count zero as a no-op.
- Check effective fake-host length before mutation and skip lengths above 4096 bytes. Emit only packets at most 4096 bytes. Total accepted bytes are therefore at most 64 times 4096. This is a resource ceiling, not path MTU tuning.
- Reject excessive counts at user boundaries and bound internal models defensively. Preserve compact representation.
- Verify production UDP through a loopback recording peer and independent oracle observations. Add no production dependency or golden fixture.

## Contracts and ownership

- resource_budget: isolated worktree; Kotlin core/data/model validation/tests and native ripdpi-config constants, ripdpi-proxy-config validation/tests and ripdpi-desync planner/tests.
- ui_contract: isolated worktree; all ten locale sets, as the only locale writer.
- Integrator: portfolio/OpenSpec, wire_e2e, generated API snapshots, combined validation and integration.
- No runtime/wire schema field, Cargo.lock, baseline or golden changes. Build metadata gains sourcePatchSha256 to identify the reviewed OBFS4 correction; preserve compiler checks and record the exact source pin.

## Risks / Trade-offs

- Counts above the limit are no longer accepted. Report a specific validation error.
- Oversized internal hosts skip the prelude and retain the original.
- The current UDP adapter skips local decoy send errors (EMSGSIZE, ENOBUFS and WouldBlock). It restores changed TTL and sends the original once when the plan has a known real write. Flow errors and a failed original send still propagate; UDP delivery is not guaranteed.

## Migration Plan

No stored-data migration. Run affected Rust and Kotlin regressions, locale lint, enabled wire test, independent review and just ci-gates. Rebase onto origin/main, rerun affected gates if inputs change, fast-forward main and push as authorized. A normal revert of the specific fix commit provides rollback.

## Completion acceptance

The owner requested successful Kotlin/JVM tests, app/service Android lint, CI and CodeQL before task closure. Check exact commit SHAs. A successful local source gate does not replace hosted checks. A required validation build failure must be repaired at its source; do not suppress compiler or security checks.

## OBFS4 asset build compatibility

Pinned Lyrebird imports unused Snowflake and legacy transports into the binary published as ripdpi-obfs4. That pulls anet private Go linknames rejected by the pinned toolchain. No fixed upstream release is available. App catalog and managed-transport tests select only obfs4 for this asset; Snowflake and WebTunnel have separate binaries.

Apply one checksum-pinned source patch to the exact pinned registry, main and proxy helper source before building. Register only obfs4, retain copyright/license, keep Go linkname checks enabled, verify commit/preimage/patch digest, include patch files in task inputs and record prebuilt patch provenance. Reuse the libXray pattern without a generic patch framework or new production dependency.

The generic native binary_path/protocols interface remains. Custom callers that used this packaged OBFS4 binary for an undeclared extra upstream method must select its separate asset or their own binary. No app-supported transport is removed.
