---
name: compose
description: Jetpack Compose guidance and audit for RIPDPI — state, recomposition, modifiers, navigation, theming, performance, and scored quality reports.
user-invokable: true
argument-hint: "[audit [scope: full|performance|state|side-effects|api] [module: :app|all]]"
---

# Compose -- RIPDPI

Two modes in one skill:

- **Expert Guidance** (default) — practical, evidence-backed answers for Compose questions
- **Audit** — scored quality report across performance, state, side effects, and API quality

---

## Expert Guidance

Non-opinionated, practical guidance for writing correct, performant Compose code —
across Android, Desktop, iOS, and Web. Covers Jetpack Compose and Compose Multiplatform.
Backed by analysis of actual source code from `androidx/androidx` and
`JetBrains/compose-multiplatform-core`.

### Workflow

When helping with Compose code, follow this checklist:

#### 1. Understand the request
- What Compose layer is involved? (Runtime, UI, Foundation, Material3, Navigation)
- Is this a state problem, layout problem, performance problem, or architecture question?
- Is this Android-only or Compose Multiplatform (CMP)?

#### 2. Analyze the design (if visual reference provided)
- If the user shares a Figma frame, screenshot, or design spec, consult `references/design-to-compose.md`
- Decompose the design into a composable tree using the 5-step methodology
- Map design tokens to MaterialTheme, spacing to CompositionLocals
- Identify animation needs and consult `references/animation.md` for recipes

#### 3. Consult the right reference
Read the relevant reference file(s) from `references/` before answering:

| Topic | Reference File |
|-------|---------------|
| `@State`, `remember`, `mutableStateOf`, state hoisting, `derivedStateOf`, `snapshotFlow` | `references/state-management.md` |
| Structuring composables, slots, extraction, preview | `references/view-composition.md` |
| Modifier ordering, custom modifiers, `Modifier.Node` | `references/modifiers.md` |
| `LaunchedEffect`, `DisposableEffect`, `SideEffect`, `rememberCoroutineScope` | `references/side-effects.md` |
| `CompositionLocal`, `LocalContext`, `LocalDensity`, custom locals | `references/composition-locals.md` |
| `LazyColumn`, `LazyRow`, `LazyGrid`, `Pager`, keys, content types | `references/lists-scrolling.md` |
| `NavHost`, type-safe routes, deep links, shared element transitions | `references/navigation.md` |
| `animate*AsState`, `AnimatedVisibility`, `Crossfade`, transitions | `references/animation.md` |
| `MaterialTheme`, `ColorScheme`, dynamic color, `Typography`, shapes | `references/theming-material3.md` |
| Recomposition skipping, stability, baseline profiles, benchmarking | `references/performance.md` |
| Semantics, content descriptions, traversal order, testing | `references/accessibility.md` |
| Removed/replaced APIs, migration paths from older Compose versions | `references/deprecated-patterns.md` |
| **Styles API** (experimental): `Style {}`, `MutableStyleState`, `Modifier.styleable()` | `references/styles-experimental.md` |
| Figma/screenshot decomposition, design tokens, spacing, modifier ordering | `references/design-to-compose.md` |
| Production crash patterns, defensive coding, state/performance rules | `references/production-crash-playbook.md` |
| Compose Multiplatform, `expect`/`actual`, resources (`Res.*`), migration | `references/multiplatform.md` |
| Desktop (Window, Tray, MenuBar), iOS (UIKitView), Web (ComposeViewport) | `references/platform-specifics.md` |

#### 4. Apply and verify
- Write code that follows the patterns in the reference
- Flag any anti-patterns you see in the user's existing code
- Suggest the minimal correct solution — don't over-engineer

#### 5. Cite the source
When referencing Compose internals, point to the exact source file:
```
// See: compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composer.kt
```

### Key Principles

1. **Compose thinks in three phases**: Composition → Layout → Drawing. State reads in each phase only trigger work for that phase and later ones.

2. **Recomposition is frequent and cheap** — but only if you help the compiler skip unchanged scopes. Use stable types, avoid allocations in composable bodies.

3. **Modifier order matters**. `Modifier.padding(16.dp).background(Color.Red)` is visually different from `Modifier.background(Color.Red).padding(16.dp)`.

4. **State should live as low as possible** and be hoisted only as high as needed. Don't put everything in a ViewModel just because you can.

5. **Side effects exist to bridge Compose's declarative world with imperative APIs**. Use the right one for the job — misusing them causes bugs that are hard to trace.

6. **Compose Multiplatform shares the runtime but not the platform**. UI code in `commonMain` is portable. Platform-specific APIs (`LocalContext`, `BackHandler`, `Window`) require `expect`/`actual` or conditional source sets.

### Source Code Receipts

When you need to verify how something works internally, read `references/source-code/index.md`. It maps each Compose runtime/UI/Foundation/Material3/Navigation/CMP source file to its upstream URL on `androidx/androidx@androidx-main` or `JetBrains/compose-multiplatform-core@jb-main`. Fetch the file directly with WebFetch or `gh api`.

Two-layer approach:
1. **Start with guidance** — read the topic-specific reference (e.g., `references/state-management.md`)
2. **Go deeper with source** — if the user wants receipts or you need to verify, follow `references/source-code/index.md` to the upstream file

---

## Audit Mode

Invoke when the user asks to audit the Jetpack Compose codebase, review Compose architecture or quality, rate the codebase with scores, or establish a pre-release quality baseline.

**Rubric version:** v1 — current as of 2026-04-13. Compose track: Kotlin 2.3.20 / Compose BOM 2026.03.01 (Strong Skipping Mode default).

Four scored categories: Performance, State Management, Side Effects, Composable API Quality.

**Out of scope in v1:** Material 3 compliance (defer to `material-3` skill), accessibility scoring, UI test coverage, CMP-specific rules, Wear OS / TV / Auto / Glance, build performance.

### Expected Output

Produce both:
- a repository report file named `COMPOSE-AUDIT-REPORT.md`
- a short chat summary with the overall score, category scores, worst issues, and top fixes

### Audit Principles

- Be strict, but evidence-based. Do not score from search hits alone. Read representative files before judging a category.
- Cite concrete file paths in the report for every important deduction.
- **Cite an official documentation URL for every deduction.** The rubric maps every rule to a canonical source in `references/canonical-sources.md`. The report template requires a `References:` line per finding.
- Reserve `0-3` scores for repeated or systemic problems, not isolated mistakes.
- Do not award `9-10` unless the repo is consistently strong across the category.

### Step 0: RIPDPI Project Orientation

Before starting the audit:

1. **Read `.github/skills/compose-performance/SKILL.md`** to absorb the known quick-wins list, existing `TrackRecomposition` instrumentation, and annotation conventions. Issues already tracked there should appear in "Known Open Items", not as new findings, unless they remain unfixed.

2. **Compiler reports**: use `./gradlew :app:assembleRelease -Pripdpi.composeReports=true` (NOT the init.gradle script). Output lands at `app/build/compose-reports/` and `app/build/compose-metrics/`. The convention plugin `ripdpi.android.compose.gradle.kts` handles this.

3. **Module scope**: `:app` is the only Compose module. Do not search `:core:data`, `:core:diagnostics`, `:core:engine`, `:core:service`, or `:core:detection` for `@Composable` definitions.

4. **Design system**: `RipDpiThemeTokens` is the project's custom wrapper over `MaterialTheme`. `RipDpiTheme` calls `MaterialTheme` internally — this is correct, not a deviation.

5. **Existing stability setup**: The project has `app/compose-stability.conf` marking external types stable, and `@Immutable`/`@Stable` on all UI model classes. Run compiler reports to get the actual `skippable%` before applying measured ceilings.

### Step 1: Confirm Scope

- If the user passed an explicit path or module, use it.
- If no path was passed, default to `:app` (`app/src/main/kotlin/com/poyka/ripdpi/`).
- If the user requests a specific category, audit only that category.

Fast-fail check: grep for `androidx.compose` in `build.gradle*` or `libs.versions.toml`, and for `setContent {` or `@Composable` under `src/`.

### Step 2: Map The Repository

Identify: Gradle modules, Compose source roots, shared UI/component packages, theme/design-system packages, screen packages, state holder/ViewModel areas, test and preview locations, baseline-profile modules.

### Step 3: Build A Compose Surface Map

Look for: `@Composable` functions, reusable UI components, screens and routes, `ViewModel` usage, `remember`/`rememberSaveable`/`mutableStateOf`, `collectAsStateWithLifecycle`, `LaunchedEffect`/`DisposableEffect`/`SideEffect`, `LazyColumn`/`LazyRow`/`items`.

If subagents are available, parallelize category scans and merge findings.

### Step 4: Generate Compose Compiler Reports

**Primary path:**
```bash
cd /Users/po4yka/GitRep/RIPDPI && ./gradlew :app:assembleRelease -Pripdpi.composeReports=true --no-daemon
```
Use a 600-second timeout.

**Collect the reports:**
```bash
find app/build/compose-reports/ app/build/compose-metrics/ \
    \( -name '*-classes.txt' -o -name '*-composables.txt' -o -name '*-composables.csv' -o -name '*-module.json' \)
```

Extract: unstable classes, non-skippable named composables, module-wide skippability counts. Compute named-only skippability from `*-composables.csv` (filter `isLambda == "0"`).

**Fallback if convention plugin fails:**
```bash
./gradlew :app:assembleRelease \
    --init-script .claude/skills/compose/scripts/compose-reports.init.gradle \
    --no-daemon --quiet
```
Output goes to `app/build/compose_audit/`.

**If both fail:** proceed with source-inferred findings, set `Compiler diagnostics used: no` in the report, reduce confidence by one level, and label all stability claims as inferred.

### Step 5: Audit The Four Categories

Use the rubric in `references/scoring.md` and heuristics in `references/search-playbook.md`.

**Performance:** expensive work in composition, avoidable recomposition, lazy list keys, bad state-read timing, unstable or overly broad reads, backwards writes.

**State Management:** hoisting correctness, single source of truth, reusable stateless seams, correct `remember` vs `rememberSaveable`, lifecycle-aware observable collection.

**Side Effects:** side effects incorrectly done in composition, correct effect API choice, effect keys, stale lambda capture, cleanup correctness, lifecycle-aware effect behavior.

**Composable API Quality** (focus on `ui/components/`, not every leaf screen): `modifier` presence and placement, parameter order, explicit over implicit configuration, meaningful defaults, avoiding `MutableState<T>` parameters in reusable APIs.

### Step 6: Verify Findings

Before deducting points: read the file where the smell appears; confirm it's not a false positive; check for compensating patterns elsewhere; distinguish one-off mistakes from systemic patterns; for stability findings, cite the compiler report line as evidence.

### Step 7: Score

| Score | Status |
|-------|--------|
| 0-3 | fail |
| 4-6 | needs work |
| 7-8 | solid |
| 9-10 | excellent |

Use weights in `references/scoring.md` to compute the overall score.

**Measured ceilings are mandatory.** When compiler reports are available, apply the Performance ceiling from `references/scoring.md` and show the math:

```
Performance ceiling check:
  skippable% = 186/273 = 68.1% -> falls in 50-70% band -> cap at 4
  qualitative score: 7
  applied score: 4 (ceiling lowered from 7)
```

### Step 8: Write The Report

Use `references/report-template.md`. The report must include: overall score, category score table, top critical findings, category-by-category reasoning, evidence file paths, prioritized remediation list, **Known Open Items** cross-referencing the quick-wins checklist from `.github/skills/compose-performance/SKILL.md`, and an optional note to run `material-3` if design issues are suspected.

Write to `COMPOSE-AUDIT-REPORT.md` at the project root. If it already exists, confirm overwrite or write to `COMPOSE-AUDIT-REPORT-<YYYY-MM-DD>.md`.

### Step 9: Return A Short Summary

Include: overall score (and delta vs. prior reports), one-line judgment per category with applied ceiling, compiler-report highlights (Strong Skipping on/off, `skippable%`, unstable shared types), **top three actionable fixes** (concrete change, file path + line numbers, official doc URL, expected impact), and whether a `material-3` audit is worth running next.

The top-three fixes in the chat summary MUST be the same items as the report's `Prioritized Fixes` list.

### What To Avoid

- Do not produce a generic checklist with no repository evidence.
- Do not inflate the performance score just because the app uses Compose.
- Do not flag `LaunchedEffect(Unit)` on its own — the "run once" pattern is idiomatic. Only flag it when the body captures a value that may change without `rememberUpdatedState`.
- Do not deduct for `RipDpiThemeTokens` usage — it is the correct design system access pattern.
- Do not double-count the same root cause across categories. Pick the dominant category and cross-reference.
- Do not score design in v1.

### References

- `references/scoring.md` — per-rule rubric with inline citations
- `references/search-playbook.md` — search patterns and red-flag heuristics
- `references/report-template.md` — required structure for `COMPOSE-AUDIT-REPORT.md`
- `references/canonical-sources.md` — the official URLs every deduction must cite
- `references/diagnostics.md` — Gradle/code snippets for compiler reports, stability config, baseline profiles, R8 checks
- `scripts/compose-reports.init.gradle` — Gradle init script fallback
