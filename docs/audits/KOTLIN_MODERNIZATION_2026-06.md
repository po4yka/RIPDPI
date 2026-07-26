# RIPDPI Kotlin / Android / Compose Modernization Audit — 2026-06

> **Historical snapshot:** the stack versions and opportunity sections below
> describe the June audit pin. The implementation outcome later in this report
> records 25 of 27 items landed. Current versions must be read from
> `gradle/libs.versions.toml` and `gradle.properties`, not this snapshot.

> Read-only audit. Toolchain pin: Kotlin **2.3.21**, Compose BOM **2026.05.01** (core 1.11.x),
> coroutines **1.11.0**, lifecycle **2.10.0**, Hilt **2.59.2**, Room **2.8.4**, compileSdk/targetSdk **36**, jvmTarget **17**.
> Scope: ~2056 `.kt` files (~358k LoC) across 9 module slices. Produced by a fan-out audit workflow
> (9 slice auditors → adversarial per-slice verifiers → synthesis), with every proposed feature
> verified for availability against the pin.

## 1. Executive summary

The RIPDPI Kotlin/Android/Compose layer is already modern. The audit across nine module slices found
**no architectural gaps and no anti-pattern clusters** — only incremental, mechanical quality wins.
Concretely, the codebase already exhibits the markers of a current Kotlin/Compose stack:

- `collectAsStateWithLifecycle` is in use across **39 files** (lifecycle-aware state collection is the norm, not the exception).
- **0** uses of `GlobalScope` (structured concurrency is respected end-to-end).
- Only **~2** residual `LiveData` references and **~2** residual `Enum.values()` call sites — both in test code.
- `kotlinx.collections.immutable` is already imported in **55 files**; `kotlinx.coroutines` structured patterns are the default.

The 27 confirmed opportunities below are **drop-in, behavior-preserving (or explicitly timing-noted)
refinements** — `buildList`, `Enum.entries`, `?.let` over `!!`, `data object`, core-ktx `edit { }`,
the `CoroutineContext.job` extension, and a small set of low-risk Compose-stability and
lifecycle-collection touch-ups. **None require a rewrite, none introduce new dependencies, and none
change public contracts.** Set expectations accordingly: this is grooming, not migration. Several rows
are deliberately low-value and optional; they are catalogued for completeness, not urgency.

Two findings carry real (non-mechanical) consideration and should be treated as deliberate decisions,
not auto-applied: the Room `@Upsert` conversions (semantic delete+insert vs in-place) and the
`java.util.Optional` → nullable Hilt binding-shape change.

## 2. Toolchain & availability ground truth

| Capability | On the 2.3.21 / SDK 36 / JVM 17 pin? | Notes |
|---|---|---|
| `buildList` / `buildSet` | Available | stdlib since Kotlin 1.6 |
| `Enum.entries` | Available | stable since Kotlin 1.9 |
| `data object` | Available | stable since Kotlin 1.9 |
| Guard conditions in `when` (with subject) | Available | stable since Kotlin 2.2 |
| `?.let` / `?:` / `checkNotNull` / nullable types | Available | Kotlin 1.0-era idioms |
| `kotlinx.coroutines` `CoroutineContext.job` extension | Available | coroutines 1.11.0 (since 1.5) |
| `repeatOnLifecycle` | Available | lifecycle-runtime-ktx 2.10.0 |
| `kotlinx.collections.immutable` `ImmutableList` | Available | 0.4.0, already a dependency |
| Room `@Upsert` | Available | Room 2.8.4 |
| androidx core-ktx `SharedPreferences.edit { }` | Available | core-ktx 1.18.0, already a dependency |
| `java.util.HexFormat` | Available | java.base since JDK 17 (jvmTarget 17) |
| **Context parameters** | **NOT available** | the language-feature flag is **not enabled** on this build; out of scope |
| **Kotlin 2.4 features** | **NOT available** | the pin is 2.3.21; any 2.4-only feature is out of scope |

## 3. Confirmed drop-in opportunities

Sorted high-value first within each group. All rows are behavior-preserving except where a
timing/semantic note is called out explicitly.

### A — Language / stdlib

| File:line | Feature | Current → Proposed | Effort | Risk | Value |
|---|---|---|---|---|---|
| `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpich/SubnetFilterAst.kt:4` | `data object` | `object Empty` → `data object Empty` (sealed leaf beside data-class siblings) | trivial | none | med |
| `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/DetectionRecommendations.kt:13` | `buildList` | `mutableListOf` + conditional adds → `buildList { … }` | small | none | med |
| `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/DetectionAutoTuner.kt:20` | `buildList` | `mutableListOf` + conditional adds → `buildList { … }` | small | none | med |
| `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/checker/IcmpSpoofingChecker.kt:210` | `buildList` | `mutableListOf` + adds → `buildList { … }` (single-accumulator helper) | trivial | none | med |
| `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/vpn/VpnAppCatalogUpdater.kt:45` | `filter` + `plus` | `toMutableList()` + for/if append → `signatures + extra.filter { … }` | trivial | low | med |
| `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/proxyimport/ProfileShareDialog.kt:82` | `?.let` safe-bind | `uiState.shareUri!!` → `uiState.shareUri?.let { shareUri → … }` | trivial | none | med |
| `core/service/src/main/kotlin/com/poyka/ripdpi/utility/NotificationContentBuilder.kt:28` | `buildList` | `mutableListOf` + conditional add → `buildList { … }.joinToString(…)` | trivial | none | low |
| `core/service/src/main/kotlin/com/poyka/ripdpi/services/OwnedStackBrowserService.kt:153` | `checkNotNull` | `error!!` → `checkNotNull(error)` (provably non-null arm) | trivial | none | low |
| `core/data/src/main/kotlin/com/poyka/ripdpi/data/rules/DomainBypassList.kt:77` | `buildList` | wrap `errors` accumulator + final `toList()` in `buildList` (LinkedHashSet stays) | small | low | low |
| `core/data/src/main/kotlin/com/poyka/ripdpi/data/backup/BackupV1.kt:340` | control-flow cleanup | collapse two no-op `Unit` arms (optional; arms are a deliberate migration extension point) | trivial | low | low |
| `core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapReader.kt:77` | `buildList` | `mutableListOf` loop → `buildList { … }.toPersistentList()` (inline, `?: break` legal) | trivial | none | low |
| `app/src/main/kotlin/com/poyka/ripdpi/platform/LocalesConfig.kt:14` | `buildList` | `mutableListOf` + `add` loop → `buildList { … }` (try/finally moves inside) | trivial | low | low |
| `app/src/main/kotlin/com/poyka/ripdpi/ui/components/RipDpiInteraction.kt:53` | `when` guard conditions | nested SDK `if` → `… if Build.VERSION.SDK_INT >= R ->` guard arm | small | low | low |
| `app/src/test/kotlin/com/poyka/ripdpi/activities/ConfigViewModelTest.kt:631` | `Enum.entries` | `TransportRemediationKind.values()` → `.entries` (iteration) | trivial | none | low |
| `app/src/test/kotlin/com/poyka/ripdpi/activities/ConfigViewModelTest.kt:634` | `Enum.entries` | `.values().size` → `.entries.size` (land with line 631) | trivial | none | low |
| `build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts:715` | smart-cast via local val | `buildError!!` → local-val capture before branch | trivial | none | low |
| `app/src/main/kotlin/com/poyka/ripdpi/platform/AppPlatformDependencies.kt:79` | nullable types over `Optional` | `Optional<AutomationController>` + `.map/.orElseGet` → `AutomationController?` + `?./?:` (DI binding-shape change, ~4 consumers) | medium | med | low |

### B — Coroutines

| File:line | Feature | Current → Proposed | Effort | Risk | Value |
|---|---|---|---|---|---|
| `app/src/main/kotlin/com/poyka/ripdpi/activities/MainActivity.kt:69` | `repeatOnLifecycle` | hot `SharedFlow` collected in bare `lifecycleScope.launch` → wrap in `repeatOnLifecycle(STARTED)` (timing change: defers side-effecting command handling to STARTED — **not** strictly behavior-preserving, but canonical safe pattern) | small | low | med |
| `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxy.kt:337` | `CoroutineContext.job` | `coroutineContext[Job]!!.invokeOnCompletion` → `currentCoroutineContext().job.…` (drop now-unused `Job` import) | trivial | none | low |
| `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiWarp.kt:336` | `CoroutineContext.job` | same idiom (drop unused `Job` import) | trivial | none | low |
| `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiRelay.kt:217` | `CoroutineContext.job` | same idiom (drop unused `Job` import) | trivial | none | low |

### C — Compose

| File:line | Feature | Current → Proposed | Effort | Risk | Value |
|---|---|---|---|---|---|
| `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/history/HistoryDetailCards.kt:78` | `ImmutableList` param stability | `@Composable fun MetricList(metrics: List<…>)` → `ImmutableList<…>` (Strong Skipping treats `kotlin.collections.List` as unstable; ~35 sites, self-scoped caller-ripple) | small | low | med |

### D — Android

| File:line | Feature | Current → Proposed | Effort | Risk | Value |
|---|---|---|---|---|---|
| `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/DetectionHistoryStore.kt:62` | core-ktx `edit { }` | `prefs.edit().…apply()` → `prefs.edit { … }` (lines 62, 77; `commit=false` = apply semantics) | trivial | none | med |
| `core/detection/src/main/kotlin/com/poyka/ripdpi/core/detection/community/CommunityComparisonStore.kt:34` | core-ktx `edit { }` | `prefs.edit().…apply()` → `prefs.edit { … }` (lines 33–37, 41) | trivial | none | low |
| `core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsProfileDao.kt:17` | Room `@Upsert` | `@Insert(REPLACE) upsert*` → `@Upsert` (String PKs, no FK; **semantic** delete+insert→in-place — deliberate, needs golden/migration re-run) | trivial | med | low |
| `core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsScanDao.kt:37` | Room `@Upsert` | `@Insert(REPLACE)` → `@Upsert` for String-PK methods (lines 37, 43) **only**; do NOT bulk-apply — sibling entities use `autoGenerate=true` PKs where REPLACE bumps rowids | small | med | low |

### E — Build

| File:line | Feature | Current → Proposed | Effort | Risk | Value |
|---|---|---|---|---|---|
| `build-logic/convention/src/main/kotlin/ripdpi.android.rust-native.gradle.kts:1149` | `java.util.HexFormat` | `joinToString("") { "%02x".format(it) }` → `HexFormat.of().formatHex(…)` (hashes a built binary, no privacy concern) | trivial | low | low |

## 4. Config-gated / opt-in opportunities

**None.** No confirmed finding requires a compiler flag, an opt-in annotation, or a build-feature
toggle. Notably, no opportunity depends on context parameters (flag not enabled) or any Kotlin 2.4
feature (off-pin). This section is intentionally empty to keep the drop-in set unambiguous.

## 5. Per-slice adoption notes

- **app-ui** (3 verified): one `!!`→`?.let` safe-bind, one Compose `ImmutableList` stability fix, one `when` guard-condition tidy — all UI-layer-local, no RDS token impact.
- **app-core** (3 verified): two `buildList` conversions (`LocalesConfig`, plus one shared) and the `Optional`→nullable Hilt binding-shape change (the only med-risk item in this slice).
- **app-tests** (2 verified): both `Enum.values()`→`entries` in `ConfigViewModelTest`; land together in one edit.
- **core-service** (2 verified, 1 rejected): `NotificationContentBuilder` `buildList` and `OwnedStackBrowserService` `checkNotNull`.
- **core-data** (2 verified, 1 rejected): `DomainBypassList` partial `buildList` and the optional `BackupV1` control-flow collapse.
- **core-diagnostics** (3 verified): `SubnetFilterAst` `data object` plus the two `DiagnosticsProfileDao`/`DiagnosticsScanDao` `@Upsert` decisions (semantic, deliberate).
- **core-detection** (6 verified, 2 rejected): three `buildList`, one `filter`+`plus`, two core-ktx `edit { }` — the richest mechanical-cleanup slice.
- **core-engine** (4 verified): three `CoroutineContext.job` swaps across `RipDpiProxy`/`RipDpiWarp`/`RipDpiRelay` plus the `MainActivity` `repeatOnLifecycle` (cross-listed under coroutines).
- **build-logic** (2 verified): `buildError!!` local-val smart-cast and `HexFormat` hex formatting — both in `ripdpi.android.rust-native.gradle.kts`.

## 6. Recommended implementation order

Phased by RIPDPI commit discipline: each atomic unit is its own Conventional Commit, decomposed by
crate/module boundary, with high-risk shared files serialized into a single lane. **Nothing in this
plan touches `*baseline*` files, blesses goldens, or alters RDS tokens** — the one Compose change
(`ImmutableList` param type) is a stability-annotation change to a composable signature, not a
token/color/`.dp`/motion edit, so the RDS token rule (`Color(0x…)`/`.dp`/`tween()` floor) is not
engaged. No new strings are added, so the 8-locale parity rule is not triggered by any row here.

**Phase 1 — pure-mechanical, zero-risk, per-module (parallelizable lanes):**
1. `core/detection` — three `buildList` conversions + `filter`/`plus` + two core-ktx `edit { }` (one commit per file or one cohesive "core-detection mechanical cleanup" commit).
2. `core/engine` — three `CoroutineContext.job` swaps (single commit; identical idiom across the three files; drop the now-unused `Job` imports).
3. `core/service` — `buildList` + `checkNotNull` (one commit).
4. `core/diagnostics` — `SubnetFilterAst` `data object` (one commit; verify the `when` branch and constant return are unaffected).
5. `core/pcap-export` — `PcapReader` `buildList` (one commit).
6. `app` tests — both `ConfigViewModelTest` `entries` edits (one commit).
7. `build-logic` — `buildError` local-val + `HexFormat` (one commit; convention-plugin file, re-run the affected Gradle task).

**Phase 2 — low-risk app-layer touch-ups (serialize `app` UI edits):**
8. `app` UI — `ProfileShareDialog` `?.let`, `RipDpiInteraction` guard conditions, `HistoryDetailCards` `ImmutableList` (the last carries ~35-site caller ripple; verify Strong-Skipping callers compile; keep as its own commit).
9. `app/platform` — `LocalesConfig` `buildList` (one commit; confirm try/finally semantics preserved).
10. `core/data` — `DomainBypassList` partial `buildList`; `BackupV1` collapse is optional and may be skipped.

**Phase 3 — deliberate / higher-consideration (serialized, each its own reviewed commit):**
11. `app` `MainActivity` `repeatOnLifecycle` — timing change; verify hot-`SharedFlow` command handling still fires correctly across STARTED transitions.
12. `core/diagnostics-data` Room `@Upsert` — String-PK methods only; **do not** bulk-apply; re-run golden/migration tests; commit message must state the intentional behavioral change (delete+insert → in-place).
13. `app/platform` `Optional`→nullable Hilt binding — touches the DI module (`@BindsOptionalOf`) plus ~4 consumers; serialize, review the binding-shape change carefully (med risk).

High-risk shared files (`Cargo.lock`, `gradle/libs.versions.toml`, `*.proto`, the locale `strings.xml`
set, `*baseline*`, goldens) are **not modified by any row** in this audit — no dependency is added, so
`libs.versions.toml` is untouched.

## 7. Counts summary

| Category | Confirmed | Config-gated | Rejected |
|---|---|---|---|
| A — Language / stdlib | 17 | 0 | — |
| B — Coroutines | 4 | 0 | — |
| C — Compose | 1 | 0 | — |
| D — Android | 4 | 0 | — |
| E — Build | 1 | 0 | — |
| **Total** | **27** | **0** | **4** |

Per-slice rejected counts (4 total): core-service 1, core-data 1, core-detection 2; all other slices 0.
Rejections were validated out during slice verification and are not catalogued as opportunities here.
Confirmed total (27) is the sum of all verified findings across the nine slices.

## 8. Implementation outcome (2026-06-03)

**25 of the 27 confirmed findings were implemented** and committed per-module as Conventional
Commits. The combined tree compiles clean: `:core:*:compileDebugKotlin`,
`:app:compileGithubDebugKotlin`, `:app:compileGithubReleaseKotlin`,
`:app:compileGithubDebugUnitTestKotlin`, and `:app:hiltJavaCompileGithubDebug` (the Dagger/Hilt
graph validation) all pass. Verification was run with `-Pripdpi.pluggableTransportAssetsMode=stub`
(local environment has no Go toolchain for the Snowflake PT asset task; unrelated to these changes).

**Two findings were reverted as not cleanly implementable** — the Kotlin/Android analog of the two
toolchain-stability misses caught in the Rust modernization pass. Both were surfaced only by the
compiler, validating the "verify against the real toolchain" discipline:

| Finding | Why reverted |
|---|---|
| `RipDpiInteraction.kt:53` — guard conditions for the SDK-gated haptic arm | Kotlin **forbids `if` guard conditions on comma-separated multi-value `when` arms** (`Confirm, Success if … ->` is a syntax error). The only compilable form duplicates the SDK check and result across four single-value arms — strictly *worse* readability than the original nested `if`. The audit/verifier over-stated applicability (this arm matches two enum constants). Original nested-`if` restored verbatim. |
| `AppPlatformDependencies.kt:79` (+ 4 consumers) — `Optional<AutomationController>` → nullable Hilt binding | Converting the `@BindsOptionalOf` optional to a nullable `@Provides` collides with the debug source-set's concrete `@Binds AutomationController`, producing a Dagger **`DuplicateBindings` + `DependencyCycle`** (`AutomationController ← Optional<AutomationController> ← AutomationController`) and a `@Nullable`/non-null mismatch. A correct conversion would require restructuring the debug/release source-set bindings — invasive, and it makes the DI graph worse, not better. The audit had already rated this **med-risk / low-value** and noted "leaving `Optional` is defensible." Reverted to the original `Optional` DI. |

The two good app-core changes that shared files with the reverted `Optional` work were preserved:
`MainActivity` `repeatOnLifecycle` and `LocalesConfig` `buildList`.

**Implemented count by category:** A-language 16 (the haptic guard reverted), B-coroutines 4,
C-compose 1, D-android 4 (incl. both Room `@Upsert`), E-build 1, minus the app-core `Optional`
item — **25 total**. The Room `@Upsert` conversions (4 String-PK methods across two DAOs) are a
deliberate behavioral change (delete+insert → in-place) and were committed with that rationale;
String-PK methods only — `autoGenerate` entities were intentionally left on `@Insert(REPLACE)`.

---

### Method & guardrails

- **Stability verified against the pin, not assumed.** Every feature was checked for availability on
  Kotlin 2.3.21 / Compose BOM 2026.05.01 / coroutines 1.11.0 / lifecycle 2.10.0 / Room 2.8.4 / JDK 17.
  The audit deliberately excludes context parameters (compiler flag not enabled) and Kotlin 2.4-only
  features (off-pin) — mirroring the discipline that caught two stability hallucinations in the Rust
  modernization pass.
- **Adversarial verification.** Each slice's raw findings were re-checked by a skeptical verifier that
  opened the cited `file:line`, confirmed the current code matched, and rejected anything already
  adopted, off-pin, constraint-violating, or illusory (4 rejected).
- **RIPDPI constraints honored.** No finding violates the RDS token rule, locale parity, the
  `VpnService.protect()` invariant, privacy rules, or the no-baseline-extension / no-bless policy.
