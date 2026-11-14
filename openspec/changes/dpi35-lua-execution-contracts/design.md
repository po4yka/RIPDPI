## Context

The audit reproduced five functional failures and found one text-only sandbox contract failure. The in-process sandbox is separate from trusted upstream nfqws2. Task RST-1794684393149595 owns this repair.

## Goals / Non-Goals

- Goal: repair F1-F6 with one code commit per finding and behavior regressions.
- Non-goal: add full upstream C ABI, change JNI/protobuf schemas, run root activation or claim Android raw transport acceptance.

## Decisions

- Set explicit text mode at all three sandbox byte entry points; file entry points delegate to them. Use only a valid harmless compiled chunk in rejection regressions.
- Materialize payload MODIFY for the packet planner. Preserve PASS and existing explicit actions. Do not silently claim support for mutated packet headers or arbitrary TCP sequence translation.
- Keep the first 1024 admitted flow states without eviction. Once capacity overflows, latch new-flow admission closed until engine replacement. This preserves bounded memory and prevents later activation of a bypassed flow. Do not add a trait hierarchy or unbounded ID history.
- Save bundled dependencies in order and use the same order during UI load. Preserve the existing JNI signature. Test fresh production registry behavior; do not claim the process-global editor VM is fresh validation.
- Return errors from TUN rule construction and propagate them through tunnel setup before readiness. Keep runtime on_fail behavior. Extract assets for either owner.
- Return clock seconds and subsecond nanoseconds as two integers.

## Contracts and ownership

- Rust strategy-lua owns input format, per-step VM, plan results, admission and time API. strategy-registry and proxy callers keep their current interfaces unless propagation requires a local change.
- tunnel-intercept constructors become fallible; tunnel-core and test callers propagate or explicitly assert success.
- app editor owns selected script dependencies; core/service owns asset extraction before runtime setup.
- Each writer uses a dedicated worktree. Exact ownership is in the portfolio. Primary serializes integration of lib.rs and egress tests. No Cargo.lock, locale, schema, baseline or generated JNI write is planned.

## Risks / Trade-offs

- Overload keeps new Lua flows bypassed for the session, even after a slot closes. This is explicit safe fallback; existing admitted flows remain functional.
- Packet output can be byte-correct while real TUN TCP transport is not verified. Limit unsupported mutations and keep Android/root acceptance separate.
- Cross-module constructor errors can miss callers. Compiler checks and owning tests must cover all changed call sites.
- Existing scripts that relied on binary import are rejected by the intended sandbox policy. Trusted root nfqws2 is unchanged.

## Migration Plan

No storage migration or wire schema change is needed. Build and test the affected crates, run service/app unit tests and lint for changed Kotlin, and run just ci-gates before integration. Run architecture health and locked Cargo metadata on the combined tree. Independent review precedes integration. Rebase the job branch on origin/main, rerun affected gates if new inputs arrive, fast-forward main and push only the tested commits. Rollback is a normal revert of a finding commit; no data migration is required.
