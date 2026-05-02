# Post-POY-7 Decomposition Gradient: Android Adapter Crates, Runtime-Adaptive Policy Sink, and Kotlin Sub-Service Splits

Status: Approved.
Decision date: 2026-05-02.
Decision owner: Principal Android/Rust Architect.
Related Paperclip issues: POY-4 (regression gate, baseline `e530ed0a`), POY-7 (prior decomposition ADR).

## Decision

Approve the four cross-domain refactor commits that landed after POY-7 (`9884feef..d6f5f59f`, inclusive) as a single architecturally coherent decomposition pass. The commits are mechanical extractions; no behavior change is intended and JNI export surface is byte-identical to the POY-4 baseline. No implementation gating; follow-ups recorded below.

This ADR also pins the dependency-direction policy that makes the new shape load-bearing, so future changes do not silently regress it.

## Context

The POY-7 ADR (`native-runner-and-platform-decomposition.md`) covered the connectivity-runner split, the diagnostics-probes compat facade, and the `TcpDesyncPlatform` capability decomposition through commit `af66236c`. Since then four further refactor commits have landed on `main`:

| Commit | Subject | Domain |
|---|---|---|
| `9884feef` | refactor(service): split WARP enrollment orchestration | Kotlin app |
| `101f4adc` | refactor(app): split diagnostics UI builders | Kotlin app |
| `423bd90b` | refactor(proxy-runtime): extract policy decisions | Rust workspace |
| `d6f5f59f` | refactor(android): split native bridge adapters | Rust workspace + JNI cdylib |

Each commit is a same-shape change applied in a different layer: a god-module (single Kotlin file or single Rust crate) is broken into small per-responsibility units, with the call surface preserved. Together they extend the POY-7 direction across the application stack and make a workspace-level pattern explicit that previously existed only inside `ripdpi-android` and `ripdpi-monitor-engine`. Recording it here keeps the dependency-direction expectation visible, since the POY-4 regression gate's child issues (e.g. `JNI symbol diff guard for libripdpi.so`) were scoped to baseline `e530ed0a` and the diff has expanded since.

## Options Considered

1. Approve in one ADR covering all four commits as a coherent pattern.
2. File four separate ADRs, one per commit.
3. Defer the ADR until a feature change forces a contract decision.

Chose option 1: the four commits implement a single architectural shape (decomposition gradient), share the same risk profile (contract-preserving extraction), and need a single dependency-direction statement to be enforceable. Splitting into four ADRs would obscure that they are a unified pattern; deferring would let the pattern calcify undocumented.

## Chosen Approach

**1. Android adapter crate decomposition (Rust).**
`ripdpi-android` is the JNI cdylib only. Feature-specific glue lives in seven sibling adapter crates:

- `ripdpi-android-bridge-support` — JNI plumbing primitives (jstring marshaling, panic-safe wrappers).
- `ripdpi-android-diagnostics-adapter`
- `ripdpi-android-fetch-adapter`
- `ripdpi-android-platform-adapter`
- `ripdpi-android-proxy-adapter`
- `ripdpi-android-telemetry-adapter`
- `ripdpi-android-vpn-protect-adapter`

`ripdpi-android/Cargo.toml` declares only `android-support`, `jni`, `once_cell`, and the seven adapter crates. No domain crate is a direct dependency of the cdylib. Adapter crates depend downward on domain crates (`ripdpi-proxy-runtime`, `ripdpi-monitor`, `ripdpi-runtime-adaptive`, etc.).

**2. Runtime-adaptive policy sink (Rust).**
Policy decisions previously embedded in `ripdpi-proxy-runtime/src/runtime/{adaptive,morph,reprobe,routing,retry}` are extracted into `ripdpi-runtime-adaptive` (`morph_policy.rs`, `strategy_context/{payload_classification,preferred_targets}.rs`). `ripdpi-proxy-runtime` depends on `ripdpi-runtime-adaptive`; the reverse edge is forbidden. This is the same direction the workspace already used for `ripdpi-runtime-adaptive`'s prior surface; the refactor moves more logic to it without flipping the edge.

**3. Kotlin sub-service splits (`app/`).**
`WarpEnrollmentOrchestrator` (1× god-orchestrator) is split into eight focused services + a Hilt bindings module. `DiagnosticsUiSectionBuilders.kt` (1× 1228-line god-builder) is split into sixteen per-section builders. The split files live in the same Kotlin package; downstream call sites are unchanged.

## Rationale

1. **JNI surface preserved (verified).** The set of `pub extern "system" fn Java_com_poyka_ripdpi_core_*` symbols is byte-identical between baseline `e530ed0a` and `HEAD` (8 explicit exports plus the macro-generated `ffi.rs` set, `JNI_OnLoad` from `lib.rs`). No symbol added, no symbol dropped. The `jni.rs → entry.rs` rename inside `ripdpi-android-fetch-adapter` and `ripdpi-android-vpn-protect-adapter` is module-internal and does not change exported names. Confirmed by:

       git -c core.fsmonitor=false grep -n 'pub extern "system" fn Java_' e530ed0a -- 'native/rust/crates/ripdpi-android*'

   diffed against the same expression at `HEAD`.

2. **Dependency direction is correct and acyclic.**
   - `ripdpi-android` → seven adapter crates → domain crates. The cdylib never reaches around the adapter layer to import a domain crate directly.
   - `ripdpi-proxy-runtime` → `ripdpi-runtime-adaptive`. `ripdpi-runtime-adaptive`'s manifest does not list `ripdpi-proxy-runtime` as a dependency, so the new policy extraction does not introduce a cycle.
   - `ripdpi-android-proxy-adapter` → `ripdpi-proxy-runtime` (downward). `ripdpi-android-bridge-support` is a leaf with only `jni`/`once_cell`.

3. **Each split preserves call-surface contract.** WARP enrollment public APIs in `WarpEnrollmentOrchestrator` are unchanged; the orchestrator becomes a coordinator over the new services rather than the implementer. Diagnostics UI builders split along section boundaries with no `Composable` signature change. Runtime policy extraction keeps the `&self` and `&mut self` signatures used by callers in proxy-runtime; the destination crate publishes the policy types under its existing namespace.

4. **Decomposition gradient is intentional, not coincidental.** The POY-7 ADR introduced the same shape inside three crates (`ripdpi-monitor-engine`, `ripdpi-diagnostics-probes`, `ripdpi-desync-runtime`). Extending it to (a) the JNI host crate and (b) two Kotlin god-modules is the correct continuation. The pattern is: "extract responsibility-bounded units, keep the prior call surface as a thin coordinator." Recording it here makes that the project default rather than ad-hoc engineering choice.

## Impacted Subsystems

- **Native (Rust):** `ripdpi-android` cdylib, seven new `ripdpi-android-*` adapter crates, `ripdpi-proxy-runtime`, `ripdpi-runtime-adaptive`.
- **Build:** Workspace gains seven crate manifests; Cargo workspace dependency graph deepens but stays acyclic. No build-logic (Gradle convention plugin, NDK toolchain) change in this diff — Gradle configuration cache impact is nil.
- **Kotlin/JNI:** No JNI symbol or ownership change. Kotlin call sites for `WarpEnrollmentOrchestrator` and diagnostics UI builders are unchanged because public surfaces preserved.
- **Diagnostics catalog:** Unchanged. No fixture update required for these commits.
- **ABI/packaging:** Unchanged. The cdylib still produces a single `libripdpi.so`; adapter crates compile into it.

## Risks

- **JNI-export drift in future adapter splits (medium).** The current pattern keeps every `extern "system" fn Java_…` inside `ripdpi-android/src/ffi/*` modules, but the adapter-crate split makes it tempting to move a JNI export down into an adapter crate. This is a one-way door: a cdylib export must be defined in the cdylib crate (or re-exported via `pub use` from there) for the linker to keep it. **Policy: every `pub extern "system" fn Java_…` MUST live in a module compiled directly by `ripdpi-android`. Adapter crates expose Rust functions only.** The pending JNI symbol-diff guard issue (`162d0f3a-406a-4ffd-bb8b-5dd60d575573`) is the enforcement mechanism — it is now in scope for the broader `e530ed0a..HEAD` range, not just `e530ed0a..af66236c`.
- **Adapter-crate proliferation (low).** Seven adapter crates is a lot of `Cargo.toml` plumbing. Future feature additions should land in the closest existing adapter unless they introduce a genuinely new responsibility class. Adding an eighth adapter crate requires a one-paragraph justification in the change description.
- **Compat-facade decay (carried from POY-7, unchanged).** `ripdpi-diagnostics-probes` is still default-on for compat. No new in-tree consumer was added by this diff. POY-7 FU-1 still applies.
- **Kotlin Hilt scope rebinding (low).** The WARP orchestrator split adds `WarpEnrollmentBindingsModule.kt`. Hilt scope changes are a well-known regression class (singleton vs view-model vs activity). The orchestrator service themselves remain singleton-scoped (no scope flip in the diff), so this is a clean extraction; flagged for awareness, not action.
- **Diagnostics builders fan-out (low).** Sixteen builders in the same package raises the chance of two builders disagreeing on shared formatting helpers. The split pulled shared logic into `DiagnosticsLiveLabels.kt` and `DiagnosticsTelemetryMetrics.kt`; new builders MUST consume those rather than re-implement.

## Required Reviews

- **Senior Rust Native Engineer (informational, not blocking).** Confirm `cargo check -p ripdpi-android`, `-p ripdpi-android-proxy-adapter`, `-p ripdpi-runtime-adaptive`, `-p ripdpi-proxy-runtime` clean before any feature work resumes on these crates. Smallest sufficient check; this ADR does not run the commands.
- **Senior Build/Gradle Engineer (informational).** Confirm Gradle configuration cache is unaffected (no `build-logic/` touch in this diff range, so risk is nil) and that release `cargo build --release -p ripdpi-android` still emits a single `libripdpi.so` with the expected ABI set.
- **Senior Android Engineer (informational).** Confirm WARP orchestrator and Diagnostics UI tests still pass (`./gradlew :app:testDebugUnitTest`). Existing `WarpEnrollmentOrchestratorTest` was updated in 9884feef and is the regression net.
- **QA Lead.** No signoff required for this ADR; existing unit and connectivity fixture tests are the regression net.
- **Security/AppSec.** Not required. No telemetry, payload-capture, permission, or unsafe-surface change in this diff range. The JNI surface is byte-identical (verified above).

## Verification Requirements

Smallest checks required before downstream implementation work resumes on the affected subsystems:

1. `cargo check -p ripdpi-android` — confirms the cdylib still links the seven adapter crates.
2. `cargo check -p ripdpi-runtime-adaptive` and `cargo check -p ripdpi-proxy-runtime` — confirms the policy-extraction edge.
3. `cargo nextest run -p ripdpi-proxy-runtime` and `-p ripdpi-runtime-adaptive` — exercises preserved policy contract.
4. `./gradlew :app:testDebugUnitTest --tests "*WarpEnrollmentOrchestratorTest*"` — confirms the orchestrator split keeps existing assertions passing.
5. (Pending issue `162d0f3a`.) JNI symbol-diff guard CI step against `libripdpi.so`. The guard's expected-symbol list MUST be regenerated from the post-`d6f5f59f` build, not the `e530ed0a` build, when the issue is finally implemented.

This ADR does not run those commands; they are required of the Senior engineer who picks up the next change in either subsystem.

## Follow-Up Tasks

- **FU-1 (Senior Rust Native Engineer + Senior Android Engineer review).** Land the JNI symbol-diff guard from existing issue `162d0f3a-406a-4ffd-bb8b-5dd60d575573`. The guard's baseline list MUST be captured from the `d6f5f59f` (or later) build so it covers the adapter-split surface, not the pre-split surface. Issue is currently `in_progress` with `assigneeId=null`; needs reassignment once the board ratification (`1eb7ccb3`) unblocks specialist staffing.
- **FU-2 (Principal Architect, on next adapter-crate proposal).** Enforce the "no JNI exports in adapter crates" policy at review time. Any PR that adds a `pub extern "system" fn Java_…` outside `ripdpi-android/` MUST be blocked.
- **FU-3 (track only).** When the next non-trivial change lands in `ripdpi-runtime-adaptive`, consider whether `ripdpi-proxy-runtime`'s policy-consuming surface can be narrowed to the new module paths (`morph_policy`, `strategy_context::*`) rather than the full crate. This would let other consumers depend on policy without pulling proxy-runtime's runtime types.
- **FU-4 (no owner; track only).** Re-scope POY-4's regression-gate child issues (`a6a9dc71`, `b73d2284`, `0399a721`, `162d0f3a`, `f1339dff`) to baseline `HEAD` rather than `e530ed0a`. The board-ratification block (`1eb7ccb3`) currently prevents reassigning to specialist owners, but the scope expansion should be reflected in each child issue's description so the work is correctly framed when ratification clears.
