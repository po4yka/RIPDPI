# Diagnostics

Copy-pasteable Gradle and code snippets the auditor can recommend (or run themselves) to back findings with measured evidence rather than source inference. Every snippet is anchored to an official source -- cite the same URL in the report.

## 1. Compose Compiler Reports & Metrics

The single highest-leverage diagnostic. Generates per-composable skippability and per-class stability reports, plus aggregate metrics.

**Reference:** <https://developer.android.com/develop/ui/compose/performance/tooling>, <https://developer.android.com/develop/ui/compose/performance/stability/diagnose>

### Primary path -- RIPDPI convention plugin

The `ripdpi.android.compose` convention plugin enables Compose Compiler reports when the Gradle property is set. No edits to `build.gradle.kts` are required:

```bash
cd /Users/po4yka/GitRep/RIPDPI && ./gradlew :app:assembleRelease -Pripdpi.composeReports=true --no-daemon
```

Alternatively, the convention plugin also enables reports when `CI=true`:

```bash
CI=true ./gradlew :app:assembleRelease --no-daemon
```

Output lands at:
- `app/build/compose-reports/` -- per-composable and per-class reports
- `app/build/compose-metrics/` -- aggregate metrics

### Fallback path -- init.gradle script

If the convention plugin is unavailable or fails, use the bundled Gradle init script that injects `reportsDestination` / `metricsDestination` into every Compose module without modifying any file in the target repo:

```bash
./gradlew :app:assembleRelease \
    --init-script .claude/skills/compose-audit/scripts/compose-reports.init.gradle \
    --no-daemon --quiet
```

Output goes to each module's `build/compose_audit/` directory instead.

### Manual edit path (last resort)

If neither approach works, ask the user to add this block to the module's `build.gradle.kts`:

```kotlin
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    metricsDestination = layout.buildDirectory.dir("compose_compiler")
}
```

(Requires the Compose Compiler Gradle plugin, default since Kotlin 2.0. On older toolchains use `kotlinOptions.freeCompilerArgs += ["-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=..."]`.)

### Reading the output

Run a release-variant build, then inspect:

- `*-classes.txt` -- stability inference per class (`stable` / `unstable` / `runtime`)
- `*-composables.txt` -- per-composable `skippable` / `restartable` / `readonly` flags
- `*-composables.csv` -- same data, machine-readable
- `*-module.json` -- aggregate counts

**Use in the audit:** when a Performance or State finding alleges an unstable param or non-skippable composable, cite the relevant line of `*-classes.txt` or `*-composables.txt`. Without these reports, stability claims are *inferred* -- say so explicitly in the report's Notes And Limits.

## 2. `compose_compiler_config.conf` -- Marking Third-Party Types Stable

When unstable types come from modules without the Compose compiler (e.g. third-party data classes), mark them stable from outside.

**Reference:** <https://developer.android.com/develop/ui/compose/performance/stability/fix>

RIPDPI already maintains a stability config at `app/compose-stability.conf` that marks proto types, `java.time.*`, `kotlin.time.Duration`, `kotlinx.collections.immutable.*`, and core data models as stable. Check this file for coverage before recommending new entries.

The config is wired in via the `ripdpi.android.compose` convention plugin. To add new types, edit `app/compose-stability.conf` directly (one fully-qualified class per line, glob patterns allowed):

```conf
# Example additions
com.example.thirdparty.Money
com.example.thirdparty.events.*
```

**Use in the audit:** if unstable types appear in compiler reports, first check whether they are already in `app/compose-stability.conf`. If not, recommend adding them there before recommending wrapper UI models.

## 3. Baseline Profile Module Skeleton

Improves cold start and frame timing by precompiling hot paths. The presence of a baseline profile module + `ProfileInstaller` in the consumer is a positive Performance signal.

**Reference:** <https://developer.android.com/develop/ui/compose/performance/baseline-profiles>, <https://developer.android.com/topic/performance/baselineprofiles/overview>

RIPDPI already has a `:baselineprofile` module. Verify it is up to date:

```bash
ls baselineprofile/src/
rg -l 'BaselineProfileRule' -g '*.kt'
rg -l 'ProfileInstaller|profileinstaller' -g '*.gradle*' -g '*.kt'
```

**Use in the audit:** check for a `baseline-prof.txt` artifact and a `ProfileInstaller` initializer. Their absence on a mature app is worth flagging; their presence is positive evidence.

## 4. R8 / Minify Hygiene

Compose performance assumes release-mode R8. Debug builds run unoptimized -- never benchmark them.

**Reference:** <https://developer.android.com/develop/ui/compose/performance> ("Run in Release Mode with R8")

Quick check:

```bash
rg -n 'isMinifyEnabled' -g '*.gradle*'
```

If the release block has `isMinifyEnabled = false`, that's a release-hygiene deduction on its own.

## 5. Strong Skipping Mode Confirmation

Strong Skipping is on by default at Kotlin 2.0.20+. RIPDPI uses Kotlin 2.3.20, so Strong Skipping is active by default.

**Reference:** <https://developer.android.com/develop/ui/compose/performance/stability/strongskipping>

If the project explicitly opts a module *out* of Strong Skipping, look for `enableStrongSkippingMode = false` in any `composeCompiler { ... }` block -- flag and require justification.

## 6. Quick Triage Recipe

When arriving at the RIPDPI repo, run these in order before scoring:

1. `rg -n 'androidx\.compose' -g '*.gradle*' -g '*.toml'` -- confirm Compose presence (fast-fail).
2. `rg -n 'kotlin\s*=\s*"' -g '*.toml'` -- record Kotlin version (Strong Skipping baseline).
3. `rg -n 'isMinifyEnabled' -g '*.gradle*'` -- release hygiene.
4. Run the convention plugin build: `./gradlew :app:assembleRelease -Pripdpi.composeReports=true --no-daemon`
5. `rg -l 'baselineProfile|ProfileInstaller' -g '*.gradle*' -g '*.kt'` -- baseline-profile presence.

These five checks tell you what kind of evidence is available before any rubric-level reading.
