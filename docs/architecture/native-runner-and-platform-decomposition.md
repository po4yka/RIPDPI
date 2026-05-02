# Native Connectivity Runner Split, Diagnostics Facade, and TCP Desync Platform Decomposition

Status: Approved (POY-7).
Decision date: 2026-05-02.
Decision owner: Principal Android/Rust Architect.
Related Paperclip issues: POY-7 (this review), POY-3 (parent).

## Decision

Approve the cross-domain refactor landed in commits `c795e066`..`af66236c` as architecturally correct. The change is mechanical preservation of contract under a tighter module boundary; no behavior change is intended and none is observed in the diffed code paths. No implementation gating; minor follow-ups recorded below.

## Context

POY-3 triaged a working tree that touched four boundary-sensitive crates simultaneously:

- `ripdpi-monitor-engine`: `engine/runners/connectivity.rs` (269 lines) collapsing into a `connectivity/` module with eight per-stage runners and a shared `support.rs`.
- `ripdpi-diagnostics-probes`: aggregate `pub use` root facade narrowed to a `compat-facade`-gated `compat::*` namespace; in-workspace consumers migrated to depend on the narrower lane crates directly.
- `ripdpi-desync-runtime`: monolithic `TcpDesyncPlatform` trait split into five capability sub-traits (`TcpPlatformCapabilities`, `TcpSocketOptions`, `TcpFakeSender`, `TcpPayloadSender`, `TcpFragmentSender`) with a marker super-trait carrying a blanket impl.
- `ripdpi-proxy-runtime`: `RuntimeTcpDesyncPlatform` now provides five separate `impl` blocks rather than one; capability and emitter helpers split into `runtime/desync/platform/{capability,fake_tcp,flagged_payload,fragmentation,multi_disorder,ordered_segments,seq_overlap,socket_options,conversion}.rs`.
- `ripdpi-android` (`Cargo.toml` and `ffi.rs`): JNI bridge modules narrowed; the crate no longer transitively depends on the diagnostics-probes facade.

This review is the gate before any subsequent implementation review or feature work resumes on these subsystems.

## Options Considered

1. Approve as-is: contracts demonstrably preserved, follow-ups out-of-band.
2. Block on object-safety verification of the new `TcpDesyncPlatform` super-trait.
3. Block on a quantitative audit (`grep`) of every external consumer of the diagnostics-probes facade before allowing the in-workspace consumers to migrate.

Chose option 1 because (2) is satisfied by inspection (see Rationale §3) and (3) does not change the decision: the `compat-facade` default feature preserves the historic surface for any external consumer regardless of internal migration.

## Chosen Approach

- Treat the connectivity split as a contract-preserving extraction. The new `ConnectivityProbeFamily` trait centralises the per-target loop in `support.rs`. Family-shaped runners (`Dns`, `Tcp`, `Quic`, `Web`, `Throughput`, `Circumvention`, `Service`) implement only `targets`, `message`, `run_probe`, and the `PHASE`/`ARTIFACT_SOURCE` constants; non-family runners (`Environment`, `Telegram`) keep `ExecutionStageRunner::run` because they finalise reports or emit single-shot probes and do not fit the family loop.
- Keep `ripdpi-diagnostics-probes` alive as a versioned compat surface for any out-of-tree consumer that already imports the aggregate API. New in-tree code MUST depend on the lane crates directly — enforced by removing the workspace dependency from `ripdpi-monitor-engine` and `ripdpi-android` Cargo manifests.
- Keep the public `TcpDesyncPlatform` symbol stable as a marker super-trait. Downstream `&dyn TcpDesyncPlatform` users (the `with_tcp_desync_platform` thread-local registry) keep working because the super-trait carries no methods of its own, all five sub-traits are object-safe (`&self` receiver, no `Self` returns, no generics on methods), and the blanket impl makes any type that implements all five sub-traits automatically satisfy the marker trait.

## Rationale

1. Connectivity stage contract preserved. Each per-stage module declares the original `PHASE` and `ARTIFACT_SOURCE` constants verbatim:
   - `dns`/`dns_integrity`, `tcp`/`tcp_fat_header`, `quic`/`quic_reachability`, `reachability`/`domain_reachability` (Web — note: phase string is intentionally `reachability`, not `web`), `throughput`/`throughput_window`, `circumvention`/`circumvention_reachability`, `service`/`service_reachability`, plus single-shot `telegram`/`telegram` and `environment`/`network_environment`.
   - Cancellation: `support::collect_family_steps` honours `cancel.load(Ordering::Acquire)` and returns `None` to signal stage cancellation, matching the previous early-return contract used by the runtime driver.
   - Report finalisation: `EnvironmentRunner::run` retains the `runtime.finish_with_report(...)` short-circuit when `transport == "none" && !vpn_service_was_active`, including the captive-portal/unvalidated-network warn path.
2. Diagnostics facade is a strict narrowing. `Cargo.toml` for `ripdpi-monitor-engine` and `ripdpi-android` no longer references `ripdpi-diagnostics-probes`. A `grep` of the workspace shows zero non-self references to the crate. The crate's `lib.rs` re-exports the historic root API only when the default `compat-facade` feature is active, so any external consumer continues to compile against the same paths.
3. `TcpDesyncPlatform` decomposition is non-breaking.
   - Object safety: each of the five sub-traits is object-safe; the marker super-trait has zero methods; thus `dyn TcpDesyncPlatform` remains valid (already used as `*const dyn TcpDesyncPlatform` in `platform/registry.rs`).
   - API churn: the only public bound on the symbol — `send_prepared_with_group<P: TcpDesyncPlatform + 'static>` in `tcp.rs` — is unchanged. The blanket impl `impl<T> TcpDesyncPlatform for T where T: …` ensures existing implementors that satisfied the monolithic trait by implementing every method continue to satisfy it after the split, provided they break their `impl` into the five sub-trait blocks. Both implementors (`TestTcpDesyncPlatform`, `RuntimeTcpDesyncPlatform`) have done so.
   - The split lets future emitters depend on a narrower capability slice (e.g., a fragmentation-only emitter does not pull in seq-overlap surface), which is the correct direction for the desync subsystem.

## Impacted Subsystems

- Native: `ripdpi-monitor-engine`, `ripdpi-diagnostics-probes`, `ripdpi-desync-runtime`, `ripdpi-proxy-runtime`, `ripdpi-android`.
- Build: workspace dependency graph (two crates lose a transitive edge through `ripdpi-diagnostics-probes`).
- Kotlin/JNI: no JNI symbol or ownership change. The `ripdpi-android` `cdylib` continues to expose the same Java entry points; only internal module organisation moved.
- Diagnostics catalog: unchanged. Phase strings, artifact source names, and stage IDs are byte-identical to the pre-split state.

## Risks

- **Phase-string fragility (low).** The `Web` stage continues to publish phase `"reachability"` rather than `"web"`. This was the prior contract; preserving it is the right call, but any future contract-fixture or telemetry consumer that confuses runner name with phase string will silently miscount. Documented here so the phase/runner divergence stays visible.
- **Compat-facade decay (medium-low).** Once no in-tree caller uses `ripdpi-diagnostics-probes`, the crate becomes load-bearing only for external consumers. If no such consumer exists, this is dead weight that drifts. See follow-up FU-1.
- **Blanket impl semantic surprise (low).** A type that implements four of the five sub-traits no longer satisfies `TcpDesyncPlatform`; the compiler error will name a specific sub-trait, which is an improvement over the previous monolithic mismatch. No action required.
- **`thread_local!` raw pointer (pre-existing).** `platform/registry.rs` stores `*const dyn TcpDesyncPlatform` and dereferences it inside `with_current`. This is unchanged from before the split and remains the correct shape for synchronous closure scoping; flagging as known-unsafe surface for any future async migration of the desync runtime.

## Required Reviews

- Senior Rust Native Engineer: confirms `cargo check -p ripdpi-desync-runtime`, `-p ripdpi-proxy-runtime`, `-p ripdpi-monitor-engine`, `-p ripdpi-android` clean before any follow-up implementation lands. (Smallest sufficient check; not run by this review.)
- Senior Build/Gradle Engineer: confirms workspace-wide `cargo check --workspace --all-features` plus `cargo check --workspace --no-default-features` to exercise the `compat-facade`-off path, and that Gradle configuration cache is not affected (no build-logic touch in this diff, so risk is nil).
- QA Lead: no signoff required for this review; existing `ripdpi-monitor-engine` integration tests are the regression net for stage-runner behaviour.
- Security/AppSec: not required for this diff; no telemetry, payload capture, permission, or unsafe surface change.

## Verification Requirements

Smallest checks required before downstream implementation work resumes:

1. `cargo check -p ripdpi-desync-runtime --no-default-features` and with default features.
2. `cargo check -p ripdpi-proxy-runtime`.
3. `cargo check -p ripdpi-monitor-engine`.
4. `cargo check -p ripdpi-android` (cdylib link surface).
5. `cargo nextest run -p ripdpi-monitor-engine -E 'test(connectivity)'` to exercise stage-runner phase/artifact preservation against existing fixtures.
6. `cargo check -p ripdpi-diagnostics-probes --no-default-features` to confirm the `compat-facade`-off path compiles (so external consumers can opt out cleanly).

This review does not run those commands; they are required of the Senior engineer who picks up the next change in either subsystem.

## Follow-Up Tasks

- **FU-1 (assign to PM/CTO triage).** Audit external consumers of `ripdpi-diagnostics-probes`. If none exist, schedule deprecation: keep `compat-facade` default for one release window, then remove the crate. If consumers exist, document them in `docs/architecture/README.md` so the facade's audience is not implicit.
- **FU-2 (assign to Senior Rust Native Engineer).** Add a `#[deny(unused_imports)]`/`cargo doc --no-deps` smoke for `ripdpi-diagnostics-probes` with `--no-default-features` in CI to prevent silent rot of the compat-facade-off path.
- **FU-3 (assign to Senior Rust Native Engineer).** Consider replacing the `r#trait` raw-identifier module name (`ripdpi-desync-runtime/src/platform/trait.rs`) with `traits.rs` or `contract.rs` when the next non-trivial change lands here. Style nit; not blocking.
- **FU-4 (no owner; track only).** When the next call site for a new TCP emitter family is added, prefer depending on the narrowest sub-trait (e.g., `TcpFragmentSender` alone) rather than `TcpDesyncPlatform` so the decomposition pays back its design cost.
