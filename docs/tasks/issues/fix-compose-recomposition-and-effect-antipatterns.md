# Fix Compose recomposition / effect-channel anti-patterns (app module sweep)

Status: in-progress
Branch: `worktree-compose-fixes`
Scope: `:app` Compose layer only. No schema, protobuf, native, or locale changes.

## Origin

A Compose Critical audit flagged three findings. Each was re-verified against
`main` HEAD (`1e5ecf57f`) before any edit, per AGENTS.md "Document code, not
plans / reproduce before fixing". Two of the three were **already resolved** in
git history; the audit snapshot predated those commits. This file records the
verified state so the discrepancy is not re-litigated later.

## The three original findings

### 1. `DiagnosticsScanSection.kt` — live-probe list (REAL, fixed here)

- **Key**: already stable at HEAD. The audit described an "index-prefixed lazy
  key", but `6705b4868 perf(diagnostics): stabilize live-probe key and drop
  index prefix` had already removed the index. Current key
  `"${probe.target}-${probe.outcome}"` is the most-stable identity the model
  (`CompletedProbeUiModel(target, outcome, tone)` — no id field) allows.
- **Unmemoized transform**: REAL and unaddressed. `progress.completedProbes
  .reversed().take(LiveProbePreviewCount)` was evaluated inline inside the
  `LazyListScope`, reallocating + re-sorting on every recomposition. During an
  active scan the progress model is re-emitted per completed probe, so this ran
  constantly.
- **Fix applied**: hoisted the derivation above the `LazyColumn` into
  `val livePreviewProbes = remember(scan.activeProgress) { ... }`. `remember`
  cannot live inside `LazyListScope`, so hoisting is required. Keyed on the
  `activeProgress` object reference (re-emitted per update) → O(1) key compare,
  recompute only on a new emission.

### 2. `StrategyConfigRoute.kt:48` — keyless `rememberSaveable` (ALREADY MITIGATED)

`var configText by rememberSaveable { mutableStateOf(uiState.desync.chainDsl) }`
is seeded keylessly, BUT a `LaunchedEffect(uiState.desync.chainDsl, source)` at
line 54 re-syncs `configText` to the upstream DSL **only while
`source == BuiltIn`**.

- The audit's suggested "key the saveable on `chainDsl`" fix would be a
  **regression**: this is a 3-source editor (BuiltIn / CustomYaml / LuaScript).
  Keying the saveable on `chainDsl` would reset the editor buffer whenever the
  underlying DSL changed even while the user is editing imported YAML / Lua,
  discarding their import. The guarded `LaunchedEffect` is the correct pattern
  and is already present.
- **Decision (product judgment): no change.** Keying would break Custom/Lua.

### 3. `BackupRestoreViewModel.kt` — one-shot effects (ALREADY RESOLVED)

Already migrated to `MutableSharedFlow(extraBufferCapacity = 1,
onBufferOverflow = BufferOverflow.DROP_OLDEST)` for all four effect channels
(`effects`, `restoreEffects`, `shareEffects`, `resetEffects`) by
`32e2b30f4 fix(backup): deliver one-shot effects via SharedFlow`. Consumers in
`BackupRestoreScreen.kt` already collect via `BackupRestoreEffectHandler(flow =
...)` / `BackupResetEffectHandler(flow = ...)`. Nothing to do.

## Repo-wide sweep (`:app`) for the same three anti-patterns

Nine candidate sites were adjudicated (parallel read-only audit + manual
verification of the two that the audit pass returned unreliable results for).

| Site | Anti-pattern | Verdict | Action |
|---|---|---|---|
| `DiagnosticsWidgets.kt:163` `MetricsRow` | A: index-in-key | **REAL** | Fixed — `key = { _, metric -> "${label}-$index" }` → `items(... key = { metric -> metric.label })`. `DiagnosticsMetricUiModel.label` is stable & unique per row. Matches the `6705b4868` precedent. |
| `DiagnosticsScanSection.kt:212` | A: (transform, not key) | **REAL** | Fixed (see #1). |
| `DiagnosticsUiCoreSupport.kt:143` `report-$index-...` | A | false-positive | The `id` is never used as a Lazy key. |
| `RoutesScreen.kt:123`, `LogsScreen.kt:490`, other diagnostics `itemsIndexed` | A | false-positive | Keys use stable intrinsic ids (`row.rule.id`, etc.); index ignored. |
| `ConfigScreen.kt:131` `selectedModeSectionKey` | B | false-positive | Seeded from a Composable param default (`ConfigModeSection.LocalBypass`), not a reactive source. |
| `AdvancedSettingsScreen.kt:26` host-pack mode | B | already-mitigated | Dialog-local; explicitly reset to `defaultHostPackTargetMode(uiState)` on every open. |
| `DomainBypassListScreen.kt:66` `text` | B | already-mitigated | Deliberate guarded load-once hydration (`loadedFromStore` flag, both saveable); preserves user edits, cannot go stale. |
| `StrategyConfigRoute.kt:48` `configText` | B | already-mitigated | See #2 — guarded `LaunchedEffect`; keying would regress. |
| `LogsViewModel.kt:33` `clearedAfterMs` | C | false-positive | Persistent filter threshold (`entry.createdAtMs >= clearedAfterMs`), not a fire-once event. |
| `MainLifecycleStateOwner.kt:23` `pendingCrashReport` | C | false-positive | Persistent pending state — a crash dialog that must survive recomposition until dismissed; `StateFlow` is correct. A `SharedFlow` would make the dialog vanish on recomposition. |

## Files changed

- `app/.../ui/screens/diagnostics/DiagnosticsScanSection.kt`
- `app/.../ui/screens/diagnostics/DiagnosticsWidgets.kt`

## Verification gate

- `:app` Kotlin compile (`-Pripdpi.skipNativeBuild=true`)
- `:app:detekt` (compose-rules) — must not exceed the pre-existing main baseline
  count (detekt has no baseline file on main; only avoid worsening it).
- `:app` unit + Roborazzi: no golden should change (pure perf/identity refactor,
  zero visual delta). Any golden diff is investigated, never blind-blessed
  (`.claude/rules/golden-bless-discipline.md`).
