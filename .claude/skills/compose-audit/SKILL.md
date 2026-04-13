---
name: compose-audit
description: >
  Jetpack Compose codebase audit for RIPDPI. Produces scored COMPOSE-AUDIT-REPORT.md
  across Performance (35%), State Management (25%), Side Effects (20%), API Quality (20%).
  Use for periodic quality reviews, pre-release baselines, or evaluating refactor impact.
  For fixing issues: use jetpack-compose-expert-skill. For design scoring: use material-3.
  For screen-specific debugging: use compose-performance.
  Trigger phrases: "audit compose", "score compose quality", "compose audit report",
  "pre-release compose check", "compose codebase review", "rate compose quality".
user-invokable: true
argument-hint: "[scope: full|performance|state|side-effects|api] [module: :app|all]"
---

# Jetpack Compose Audit

This skill audits the RIPDPI Jetpack Compose codebase with a strict, evidence-based report.

**Rubric version:** v1 -- current as of 2026-04-13. Compose track: Kotlin 2.3.20 / Compose BOM 2026.03.01 (Strong Skipping Mode default).

It is intentionally focused on four categories:

- Performance
- State management
- Side effects
- Composable API quality

This skill does **not** score design or Material 3 compliance in v1. If the audit surfaces likely design-system problems, recommend a follow-up audit with the `material-3` skill.

## Out Of Scope In v1

Owned and deliberate scope choices -- call out the limitation in the report rather than silently producing thin coverage:

- Material 3 compliance, theming, color/typography tokens -- defer to the `material-3` skill.
- Accessibility scoring (`semantics`, content descriptions, touch-target sizing) -- flag obvious gaps as a note, do not score.
- UI test coverage and Compose test rule patterns -- note presence/absence, do not score.
- Compose Multiplatform-specific rules (`expect`/`actual`, target-specific code paths).
- Wear OS / TV / Auto / Glance surfaces.
- Build performance (incremental compilation, KSP/KAPT choice).

If the user explicitly asks for any of these, narrow the scope and state it in the report.

## When To Use

Use this skill when the user asks to:

- audit the Jetpack Compose codebase
- review Compose architecture or quality
- rate the codebase with scores
- inspect recomposition, state, or effects issues across the whole app
- identify Compose best-practice violations
- establish a pre-release quality baseline

## Expected Output

Produce both:

- a repository report file named `COMPOSE-AUDIT-REPORT.md`
- a short chat summary with the overall score, category scores, worst issues, and the top fixes

## Audit Principles

- Be strict, but evidence-based.
- Do not score from search hits alone. Read representative files before judging a category.
- Cite concrete file paths in the report for every important deduction.
- **Cite an official documentation URL for every deduction.** No "trust me" findings -- the rubric maps every rule to a canonical source in `references/canonical-sources.md`. The report template requires a `References:` line per finding.
- Prefer canonical Android guidance over folklore.
- Treat performance as important, but not as the only lens.
- Do not punish app code for failing public-library purity tests. Apply API-quality checks mainly to reusable internal components, design-system pieces, and shared UI building blocks.
- Reserve `0-3` scores for repeated or systemic problems, not isolated mistakes.
- Do not award `9-10` unless the repo is consistently strong across the category.

## Process

### Step 0: RIPDPI Project Orientation

Before starting the audit, orient yourself to the RIPDPI project:

1. **Read `.github/skills/compose-performance/SKILL.md`** to absorb the known quick-wins list, existing `TrackRecomposition` instrumentation, and annotation conventions. Issues already tracked there should appear in the report's "Known Open Items" section, not as new findings, unless they remain unfixed.

2. **Compiler reports**: use `./gradlew :app:assembleRelease -Pripdpi.composeReports=true` (NOT the init.gradle script). Output lands at `app/build/compose-reports/` and `app/build/compose-metrics/`. The convention plugin `ripdpi.android.compose.gradle.kts` handles this. The `scripts/compose-reports.init.gradle` is a fallback only for when the convention plugin is unavailable.

3. **Module scope**: `:app` is the only Compose module. Do not search `:core:data`, `:core:diagnostics`, `:core:engine`, `:core:service`, or `:core:detection` for `@Composable` definitions.

4. **Design system**: `RipDpiThemeTokens` is the project's custom wrapper over `MaterialTheme`. `RipDpiTheme` calls `MaterialTheme` internally -- this is correct, not a deviation. Score design token usage based on `RipDpiThemeTokens.*` calls, not raw `MaterialTheme` calls. For design scoring context, defer to the `material-3` skill.

5. **Existing stability setup**: The project has `app/compose-stability.conf` marking external types stable, and `@Immutable`/`@Stable` on all UI model classes (see `activities/DiagnosticsUiModels.kt`, `activities/SettingsUiModels.kt`, `activities/HistoryUiModels.kt`, `activities/MainViewModel.kt`, and all `ui/theme/` token classes). Run compiler reports to get the actual `skippable%` before applying measured ceilings -- do not assume a deficit.

6. **Related skills**: For fix implementation patterns, defer to `jetpack-compose-expert-skill`. For design scoring, defer to `material-3`. For screen-specific recomposition debugging, defer to `compose-performance`.

### Step 1: Confirm Scope

Identify the target path:

- If the user passed an explicit path or module, use it.
- If no path was passed, default to `:app` (`app/src/main/kotlin/com/poyka/ripdpi/`).
- If the user requests a specific category (e.g., `performance`), audit only that category.

Before mapping modules, confirm Compose is present (fast-fail):

- grep for `androidx.compose` in any `build.gradle*` or `libs.versions.toml`
- grep for `setContent {` or `@Composable` under `src/`

### Step 2: Map The Repository

Before scoring, identify:

- Gradle modules (`:app` is the primary Compose module)
- Compose source roots (`app/src/main/kotlin/com/poyka/ripdpi/ui/`)
- shared UI/component packages (`ui/components/`)
- theme or design-system packages (`ui/theme/`)
- screen packages (`ui/screens/`)
- state holder or ViewModel areas (`activities/`)
- test and preview locations
- baseline-profile related modules (`:baselineprofile`)

### Step 3: Build A Compose Surface Map

Look for:

- `@Composable` functions
- reusable UI components in `ui/components/`
- screens and routes in `ui/screens/` and `ui/navigation/`
- `ViewModel` usage in `activities/`
- `remember`, `rememberSaveable`, `mutableStateOf`
- `collectAsStateWithLifecycle`, `collectAsState`
- `LaunchedEffect`, `DisposableEffect`, `SideEffect`, `rememberUpdatedState`, `produceState`
- `LazyColumn`, `LazyRow`, `items`, `itemsIndexed`

If subagents are available, parallelize category scans by spawning `Explore`-type subagents (no write tools) and merge the findings.

### Step 4: Generate Compose Compiler Reports

Do **not** ask the user to edit `build.gradle` or run commands themselves. The skill runs the build automatically.

1. **Primary path -- RIPDPI convention plugin.**

   The `ripdpi.android.compose` convention plugin enables compiler reports when the Gradle property is set:

   ```bash
   cd /Users/po4yka/GitRep/RIPDPI && ./gradlew :app:assembleRelease -Pripdpi.composeReports=true --no-daemon
   ```

   Use a 600-second timeout. Inform the user the build is starting (it may take several minutes).

2. **Collect the reports.**

   ```bash
   find app/build/compose-reports/ app/build/compose-metrics/ \
       \( -name '*-classes.txt' -o -name '*-composables.txt' -o -name '*-composables.csv' -o -name '*-module.json' \)
   ```

   From these files, extract:
   - **unstable classes** (lines starting with `unstable class` in `*-classes.txt`) used as composable parameters
   - **non-skippable but restartable named composables** (ignore zero-argument lambdas; focus on actual named functions in `*-composables.txt` or `*-composables.csv` where `isLambda == "0"`)
   - **module-wide skippability counts** from `*-module.json`, AND compute the **named-only skippability** from `*-composables.csv` (by filtering out rows where `isLambda == "1"` and calculating `sum(skippable) / sum(restartable)`). Cite both in the Performance section, noting that zero-argument lambdas can artificially anchor the module-wide metric.

3. **Fallback if the convention plugin build fails.** Try the init.gradle script:

   ```bash
   ./gradlew :app:assembleRelease \
       --init-script .claude/skills/compose-audit/scripts/compose-reports.init.gradle \
       --no-daemon --quiet
   ```

   Output will go to `app/build/compose_audit/` instead.

4. **If both fail.** Proceed with source-inferred stability findings, but:
   - set `Compiler diagnostics used: no` in the report's Notes And Limits
   - reduce overall confidence by one level
   - state each stability-related deduction as "inferred from source -- not verified against compiler reports"

### Step 5: Audit The Four Categories

Use the scoring rubric in `references/scoring.md` and the heuristics in `references/search-playbook.md`.

#### Performance

Focus on:

- expensive work in composition
- avoidable recomposition
- lazy list keys
- bad state-read timing
- unstable or overly broad reads
- backwards writes
- obvious release-performance hygiene where visible

#### State Management

Focus on:

- hoisting correctness
- single source of truth
- reusable stateless seams
- correct use of `remember` vs `rememberSaveable`
- lifecycle-aware observable collection
- observable vs non-observable mutable state

#### Side Effects

Focus on:

- side effects incorrectly done in composition
- correct effect API choice
- effect keys
- stale lambda capture
- cleanup correctness
- lifecycle-aware effect behavior

#### Composable API Quality

Focus on reusable internal components in `ui/components/`, not every leaf screen.

Check:

- `modifier` presence and placement
- parameter order
- explicit over implicit configuration
- meaningful defaults
- avoiding `MutableState<T>` or `State<T>` parameters in reusable APIs where a better shape exists
- component purpose and layering

### Step 6: Verify Findings

Before deducting points:

- read the file where the smell appears
- make sure the pattern is real, not a false positive
- check whether the repo already has a compensating pattern elsewhere
- distinguish one-off mistakes from systemic patterns
- for stability findings (skippable / restartable / unstable params), use the compiler reports generated in Step 4. Cite the specific report line as evidence. Only fall back to source-inferred stability claims if Step 4 failed, and label them as such.

### Step 7: Score

Assign each category a `0-10` score and a status:

- `0-3`: fail
- `4-6`: needs work
- `7-8`: solid
- `9-10`: excellent

Use the weights in `references/scoring.md` to compute the overall score.

**Measured ceilings are mandatory, not suggestive.** When Step 4 produced compiler reports, the Performance rubric in `references/scoring.md` defines a ceiling based on `skippable%` and unstable-param count. After arriving at a qualitative Performance score, you MUST apply the ceiling and lower the score if it exceeds the cap. Show the math in the report:

```
Performance ceiling check:
  skippable% = 186/273 = 68.1% -> falls in 50-70% band -> cap at 4
  qualitative score: 7
  applied score: 4 (ceiling lowered from 7)
```

Do not round `skippable%` up into a higher band. `68.1%` is not `>=70%`.

If a category genuinely has too little auditable surface area, mark it `N/A`, explain why, and renormalize the remaining weights.

### Step 8: Write The Report

Use `references/report-template.md`.

The report must include:

- overall score
- category score table
- top critical findings
- category-by-category reasoning
- evidence file paths
- prioritized remediation list
- **Known Open Items** section cross-referencing the quick-wins checklist from `.github/skills/compose-performance/SKILL.md` to distinguish new findings from tracked debt
- optional follow-up note to run `material-3` if design issues are suspected

Write the report to `COMPOSE-AUDIT-REPORT.md` at the project root.

If `COMPOSE-AUDIT-REPORT.md` already exists, do not overwrite silently. Either confirm overwrite with the user, or write to `COMPOSE-AUDIT-REPORT-<YYYY-MM-DD>.md` alongside it.

### Step 9: Return A Short Summary

In chat, produce a summary that mirrors the report's `Prioritized Fixes` section -- not a generic recap.

Include:

- overall score (and the delta vs. any prior `COMPOSE-AUDIT-REPORT*.md` if present)
- one-line judgment for each category, with the applied ceiling if any
- compiler-report highlights when Step 4 succeeded: Strong Skipping on/off, `skippable%`, count of unstable shared types, any module that failed to build
- **top three actionable fixes**, each with:
  - the concrete change
  - file path(s) and line numbers
  - one official doc URL from `references/canonical-sources.md`
  - expected impact
- whether a `material-3` audit is worth running next

The top-three fixes in the chat summary MUST be the same items as the report's `Prioritized Fixes` list. Do not add generic advice in chat that isn't in the written report.

## Evidence Rules

- Prefer multiple examples over one dramatic example.
- Use positive evidence too, not just failures.
- Do not infer runtime problems you cannot justify from the code.
- When a rule is based on official guidance but app-level tradeoffs may justify deviation, call it out as a tradeoff instead of pretending it is always wrong.

## Large Repo Strategy

For RIPDPI:

1. `:app` is the only Compose module -- map its structure.
2. Pick representative files per screen/component area.
3. Parallelize category scans when possible.
4. Merge repeated findings into systemic issues instead of listing the same smell twenty times.

## What To Avoid

- Do not produce a generic checklist with no repository evidence.
- Do not turn the report into a public-library API lecture -- RIPDPI is an app.
- Do not inflate the performance score just because the app uses Compose.
- Do not over-penalize isolated experiments or sample files unless they are part of production paths.
- Do not score design in v1.
- Do not flag `LaunchedEffect(Unit)` or `LaunchedEffect(true)` on its own -- the "run once" pattern is idiomatic. Only flag it when the body captures a value that may change without `rememberUpdatedState`.
- Do not deduct for `RipDpiThemeTokens` usage -- it is the correct design system access pattern for this project.
- Do not double-count the same root cause across categories. A stability problem typically surfaces in both Performance and State -- pick the dominant category and cross-reference.

## References

- `references/scoring.md` -- per-rule rubric with inline citations
- `references/search-playbook.md` -- search patterns and red-flag heuristics
- `references/report-template.md` -- required structure for `COMPOSE-AUDIT-REPORT.md`
- `references/canonical-sources.md` -- the official URLs every deduction must cite
- `references/diagnostics.md` -- Gradle/code snippets for Compose Compiler reports, stability config, baseline profiles, and R8 checks
- `scripts/compose-reports.init.gradle` -- Gradle init script fallback for when the convention plugin is unavailable
