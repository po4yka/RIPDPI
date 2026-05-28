# Maintainability Migration Backlog

A standing record of the maintainability/architecture work done in the
documentation-and-guardrail refactoring sequence, the work that was
**deliberately deferred**, and the compatibility risks worth watching.

This document is **descriptive**. It changes no runtime behavior. Every
"deferred" item below is a *proposal* — none of them has been implemented.
Where this doc says a refactor was *done*, it names the commit; where it says a
refactor was *deferred*, it names why and where the deferral is recorded.

Companion docs: [`ARCHITECTURE.md`](ARCHITECTURE.md),
[`NATIVE_RUST.md`](NATIVE_RUST.md), [`RUNTIME_MODES.md`](RUNTIME_MODES.md),
[`hotspots.md`](hotspots.md).

---

## 1. Completed improvements

All items below shipped in this sequence as documentation, tests, and one
machine-checked guardrail. **No production runtime code was changed** — the
only executable additions are unit tests and a CI/pre-commit Python check.

| Commit | Area | What landed |
|--------|------|-------------|
| `d0a503d38` | Diagnostics + telemetry contracts | Expanded [`DIAGNOSTICS_ARCHITECTURE.md`](DIAGNOSTICS_ARCHITECTURE.md) (probe/candidate registration flow, the probe-descriptor seam, raw-path vs in-path requirements, lifecycle/policy-memory interaction); added the probe-registration seam to [`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md) §3; `///` docs on `LaneAdapter` / `LANE_ADAPTERS`; **new** [`TELEMETRY_CONTRACT.md`](TELEMETRY_CONTRACT.md); 3 forward-compat tests in `NativeRuntimeSnapshotTest.kt`. |
| `0800bae44` | Root-helper contract | Completed [`ROOT_HELPER_CONTRACT.md`](ROOT_HELPER_CONTRACT.md) (command table, session-nonce rules, fd passing, helper lifecycle, non-root fallback audit); `///` docs on the 14 `CMD_*` constants and the non-root fallback contract; KDoc on `RootHelperManager`. |
| `b23eb3e8d` | Root-helper protocol tests | Compatibility tests pinning `CMD_*` wire strings, params JSON round-trips, session-nonce bounds, request/response shapes, and unknown-command tolerance (`commands.rs`, `params.rs`, `wire.rs`). |
| `bf83b39e7` | `:core:service` ownership | **New** `core/service/README.md` ownership map (10 sub-areas, central coordinators); class-level KDoc on 11 service/runtime coordinators. |
| `d09ff768d` | Runtime mode state model | KDoc on `Mode` / `AppStatus` / `ServiceStatus` / `ServiceStateStore.status`; the "runtime mode state model" section in [`RUNTIME_MODES.md`](RUNTIME_MODES.md). |
| `71463df25` | Architecture guardrail + doc consistency | New JNI-containment check in `scripts/ci/check_native_architecture_contracts.py` (non-L8 crates may not pull `jni` / `android-support` / `android_logger` / `ndk-*`); doc consistency pass — stale `ripdpi-runtime` reference fixed, resolved proto-path verification marker, historical-crate-name banners on `docs/native/proxy-engine.md` and `docs/native/unsafe-audit.md`, enforcement cross-links. |

Net effect: the five canonical architecture docs
([`ARCHITECTURE.md`](ARCHITECTURE.md), [`NATIVE_RUST.md`](NATIVE_RUST.md),
[`JNI_CONTRACT.md`](JNI_CONTRACT.md), [`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md),
[`FEATURE_EXTENSION_GUIDE.md`](FEATURE_EXTENSION_GUIDE.md)) are authoritative,
two cross-boundary contracts (telemetry, root-helper) are now written down and
test-pinned, and one previously unenforced rule (JNI containment) is
machine-checked.

---

## 2. Remaining work (P1 / P2 / P3)

Priorities: **P1** = correctness/coherence gap an agent can hit today;
**P2** = real coverage or consistency gap, low blast radius; **P3** = a
genuine improvement that needs a coordinated or multi-file change.

### P1 — coherence gaps

_All P1 coherence gaps were closed in the documentation audit pass — see
§6 "Suggested next small tasks" for what was applied._

### P2 — coverage / consistency gaps

- **Root-helper `SCM_RIGHTS` fd passing has no integration coverage.** The
  `b23eb3e8d` tests verify the JSON wire form carries *no* file-descriptor
  field (fd passing is out-of-band ancillary data) and pin the command/params
  shapes — but the actual fd transfer needs a process-pair integration
  harness, which does not exist. Tracked in
  [`ROOT_HELPER_CONTRACT.md`](ROOT_HELPER_CONTRACT.md).
- **Historical runtime/monitor crate-name cleanup.** A 2026-05-28 docs sweep replaced the high-traffic `ripdpi-runtime` / `ripdpi-monitor` monolith references with the current `ripdpi-proxy-runtime`, `ripdpi-runtime-*`, `ripdpi-monitor-*`, and `ripdpi-diagnostics-*` crate families. Keep future native docs anchored to [`NATIVE_RUST.md`](NATIVE_RUST.md) instead of reintroducing historical banners.

### P3 — coordinated / multi-file improvements

- **Make `DirectPathLearningEvent` forward-tolerant.** Today it is the *one*
  non-tolerant Rust→Kotlin telemetry decode (see §5). Adding a sentinel
  variant ripples into three exhaustive `when` expressions in
  `DirectPathPolicyLearner.kt` and forces a policy decision — it must land as
  one coordinated commit (Rust emitter + Kotlin enum + `when` arms). Detailed
  in [`TELEMETRY_CONTRACT.md`](TELEMETRY_CONTRACT.md) § Forward compatibility.
- **Add a derived read-only `RuntimeMode` projection.** Not the full unified
  type (§4) — a *read-only* projection over `ServiceStateStore.status` plus
  the inferred relay/root/diagnostics layers, added without touching `Mode` or
  `AppStatus` or their consumers. This is the documented safe first step in
  [`RUNTIME_MODES.md`](RUNTIME_MODES.md).
- **Finish the `Probe`-trait migration, then add `ProbeDescriptor`.** The
  unified probe-descriptor table is blocked until every scheduled probe is a
  `Probe` impl (§4). The migration itself is the prerequisite task.

---

## 3. Files / crates still considered hotspots

`hotspots.md` records **no baseline-exempt files** — there is no formal
line-count hotspot today. The items below are *structural* hotspots: high
fan-in, high churn, or large surface, worth extra review when touched.

| Hotspot | Why it is a hotspot | Source |
|---------|--------------------|--------|
| `ripdpi-failure-classifier` | Highest native fan-in — 17 consumers; any change ripples widest. | [`NATIVE_RUST.md`](NATIVE_RUST.md) §3 |
| `ripdpi-config` (16), `ripdpi-diagnostics-contracts` (15), `ripdpi-packets` (14), `ripdpi-proxy-config` (14) | Next-widest fan-in hubs. | [`NATIVE_RUST.md`](NATIVE_RUST.md) §3 |
| `ripdpi-runtime-platform` (`platform/linux.rs`) | Largest remaining `unsafe` syscall surface — socket ABI, raw fd ownership, `mmap`, `ioctl`. Marked "active hotspot" in the unsafe audit. | [`docs/native/unsafe-audit.md`](../native/unsafe-audit.md) |
| `ripdpi-android-bridge-support` (`src/lib.rs`) | High edit churn across recent sessions; the JNI bridge surface. | session history |
| `:core:service` `services/` package | ~165 files; ownership is documented (`core/service/README.md`) but the package was **not** restructured (§4). | `core/service/README.md` |
| `RipDpiProxy.kt` / `RipDpiRelay.kt` JNI wrappers | Two-region telemetry-lock model is design-only, implementation queued. | [`jni-handle-lifetime-telemetry-lock.md`](jni-handle-lifetime-telemetry-lock.md) |

---

## 4. Deferred risky refactors — and why

Each of these was *considered* during the sequence and *not done*. None of the
canonical docs claims any of them happened.

| Deferred refactor | Why deferred | Where recorded |
|-------------------|--------------|----------------|
| **Unified sealed `RuntimeMode` type** (mode + status + relay + root + diagnostics in one type) | `Mode` is referenced in ~140 files and `AppStatus` in ~45; collapsing them is broad rewiring of every status consumer and a behavior risk to start/stop — explicitly out of scope ("no behavior changes to start/stop/restart"). | [`RUNTIME_MODES.md`](RUNTIME_MODES.md) § "Why there is no single `RuntimeMode` type" — labelled a *documented future refactor*. |
| **Unified `ProbeDescriptor` table** in `ripdpi-diagnostics-probes` | The scheduled units are stage runners while the `Probe`-trait probes are a separate, still-incomplete set (concrete probes are mid-migration into the trait). A descriptor table now would either drift from what runs or duplicate the stage list with no drift-guard. Prerequisite: finish the `Probe`-trait migration. | [`DIAGNOSTICS_ARCHITECTURE.md`](DIAGNOSTICS_ARCHITECTURE.md) § "The probe descriptor seam" — explicitly "**not built today**". |
| **`:core:service` facades / extracted interfaces** | New facades in a 165-file package are behavior-adjacent and risky; the constraint allowed only docs + KDoc + non-behavioral seams. Ownership was documented instead. | `core/service/README.md` — describes ownership, claims no extraction. |
| **`DirectPathLearningEvent` enum tolerance** | A sentinel variant forces a behavioral decision in `DirectPathPolicyLearner.kt`'s three exhaustive `when`s; it is *not* additive-safe. | [`TELEMETRY_CONTRACT.md`](TELEMETRY_CONTRACT.md) § Forward compatibility — marked a *coordinated change*. |

---

## 5. Compatibility risks to monitor

- **Runtime telemetry has no schema version.** Unlike the diagnostics scan
  contract (`DIAGNOSTICS_ENGINE_SCHEMA_VERSION`), the
  `NativeRuntimeSnapshot` JSON carries no version field. Forward/backward
  safety rests **entirely** on the additive-and-defaulted rule plus the four
  engine decoders' `ignoreUnknownKeys = true`. A non-omittable Rust field, or a
  Kotlin field added without a default, silently breaks an older/newer peer.
  Locked by `NativeRuntimeSnapshotTest.kt`. See
  [`TELEMETRY_CONTRACT.md`](TELEMETRY_CONTRACT.md) § Payload rules.
- **`DirectPathLearningEvent` is the one non-tolerant decode.** A new Rust-side
  event string fails to decode the *entire* enclosing snapshot. Treat any new
  direct-path learning event as a coordinated Rust+Kotlin change.
- **Root-helper `CMD_*` strings and params JSON are frozen wire contracts.**
  Now test-pinned (`b23eb3e8d`): renaming a command or a param key, or changing
  the session-nonce length bounds, breaks helper⇄client compatibility and
  fails the protocol tests. Add new commands/params; never rename.
- **Native config JSON crossing JNI** is governed by
  [`CONFIG_CONTRACTS.md`](CONFIG_CONTRACTS.md); golden-locked by
  `NativeTelemetryGoldenTest` / `ServiceTelemetryGoldenTest`. Any payload-shape
  change is a golden re-bless under
  [`golden-bless-discipline.md`](../../.claude/rules/golden-bless-discipline.md).
- **JNI containment is now enforced.** A non-L8 crate that adds a `jni` /
  `android-support` / `android_logger` / `ndk-*` dependency fails the
  `native-contracts` pre-commit hook. If the L8 crate set legitimately grows,
  update `L8_JNI_ALLOWED_CRATES` in
  `scripts/ci/check_native_architecture_contracts.py` **and** the §6 list in
  [`NATIVE_RUST.md`](NATIVE_RUST.md) together.

---

## 6. Suggested next small tasks for future agents

Right-sized, accurate, low-risk — each is a single focused change:

1. ~~**Fix the `AGENTS.md` proto path** to
   `core/data/model/src/main/proto/app_settings.proto`~~ — **done** in the
   doc audit pass.
2. ~~**Resolve the `ARCHITECTURE.md` §3 verification marker** for the
   `:core:data:settings` ↔ `:core:data:model` split~~ — **done** in the doc
   audit pass; the split (schema vs. persistence) is now stated inline.
3. ~~**Rewrite the historical crate names** in the bodies of
   `docs/native/proxy-engine.md` and `docs/native/unsafe-audit.md`~~ —
   **done** in the 2026-05-28 docs cleanup; both docs now use the current
   `ripdpi-proxy-runtime` / `ripdpi-root-helper-protocol` names.
4. **Sweep the remaining deep docs** under `docs/architecture/` /
   `docs/native/` for pre-decomposition crate names and add historical-note
   labels or corrections (P2, doc-only).
5. **File a tracking issue for the `Probe`-trait migration** — the named
   prerequisite for `ProbeDescriptor` — so the dependency is visible (P3).
6. **Prototype the derived read-only `RuntimeMode` projection** over
   `ServiceStateStore.status` without touching `Mode` / `AppStatus` (P3).

Avoid: starting the unified `RuntimeMode` type, the `ProbeDescriptor` table, or
`:core:service` facade extraction as "small tasks" — §4 explains why each needs
a scoped, reviewed effort.

---

## 7. Non-root invariant — confirmed unchanged

**The non-root baseline holds. No root-only path was made mandatory by this
sequence.**

- `root_mode_enabled` is `app_settings.proto` field **135**, a proto3 `bool`
  — it defaults to **`false`**. Root mode is **off by default**.
- This sequence changed **no** root-helper runtime behavior. The root-helper
  work was: documentation (`ROOT_HELPER_CONTRACT.md`, `///` / KDoc) and
  *additive* compatibility tests. The `su` invocation, the IPC transport, and
  the dispatch path are byte-for-byte unchanged.
- [`ROOT_HELPER_CONTRACT.md`](ROOT_HELPER_CONTRACT.md) § "Invariant — the
  non-root baseline" states the rule explicitly: every privileged operation is
  opt-in behind `root_mode_enabled` and must degrade gracefully (local
  non-privileged path, clean error, or inert behavior) when root is absent.
- The Goal-C fallback audit recorded the privileged dispatch path
  (`ripdpi-runtime-platform`'s `with_root_helper()`-gated dispatch) as already
  carrying a non-root fallback — the `Option`-return contract documented in
  `fake_send/root_helper_dispatch.rs`.

---

## 8. Tests and checks run

Across this sequence and this verification pass:

| Check | Scope | Result |
|-------|-------|--------|
| `python3 scripts/ci/check_native_architecture_contracts.py` | native dependency direction + JNI containment | **0 violations** (re-run for this pass) |
| `cargo test` (`--locked`) | `ripdpi-root-helper-protocol` — `commands.rs`, `params.rs`, `wire.rs` compatibility tests | pass |
| `cargo clippy -D warnings`, `cargo doc -D warnings`, `cargo fmt --check` | touched Rust crates | pass |
| `./gradlew :core:data:testDebugUnitTest` | `NativeRuntimeSnapshotTest` forward-compat tests | pass |
| `:core:service` `compileDebugKotlin` / `detekt` / `ktlint` | KDoc-only changes | pass |
| `:core:data:model` / `:core:data:runtime-state` `detekt` / `ktlint` | KDoc-only changes | pass |
| Pre-commit hooks (`no-detekt-baseline`, `no-secrets`, `conventional-commit`, …) | each commit in the sequence | pass |

The JNI-containment guardrail was verified non-blocking: 0 violations against
the current workspace, so it adds enforcement without flagging any existing
crate.

---

## Cross-references

| Topic | Source |
|-------|--------|
| Architecture entrypoint | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Native crate taxonomy + enforcement | [`NATIVE_RUST.md`](NATIVE_RUST.md) |
| Runtime mode state model | [`RUNTIME_MODES.md`](RUNTIME_MODES.md) |
| Telemetry ownership + forward-compat | [`TELEMETRY_CONTRACT.md`](TELEMETRY_CONTRACT.md) |
| Root-helper contract + non-root invariant | [`ROOT_HELPER_CONTRACT.md`](ROOT_HELPER_CONTRACT.md) |
| Diagnostics probe/candidate seams | [`DIAGNOSTICS_ARCHITECTURE.md`](DIAGNOSTICS_ARCHITECTURE.md) |
| Performance hotspot inventory | [`hotspots.md`](hotspots.md) |
