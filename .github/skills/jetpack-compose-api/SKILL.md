---
name: jetpack-compose-api
description: >
  Jetpack Compose API reference and internals. Use when the question is about how a Compose
  API works — remember, mutableStateOf, derivedStateOf, snapshotFlow, LaunchedEffect,
  DisposableEffect, SideEffect, rememberCoroutineScope, Modifier.Node, Modifier ordering,
  LazyColumn/LazyRow keys and content types, NavHost type-safe routes (Navigation 2.8+),
  AnimatedVisibility, Crossfade, animate*AsState, updateTransition, MaterialTheme internals,
  ColorScheme, CompositionLocal, recomposition skipping, @Stable/@Immutable, baseline profiles,
  accessibility semantics, deprecated Compose APIs, or the experimental Styles API
  (Style {}, MutableStyleState, Modifier.styleable()). Backed by actual androidx source code.
  For RIPDPI-specific Compose conventions (ViewModel pattern, sealed Route navigation,
  DataStore -> StateFlow -> collectAsStateWithLifecycle, RipDpiThemeTokens), use the
  android-compose-patterns skill instead.
---

# Jetpack Compose Expert Skill

Non-opinionated, practical guidance for writing correct, performant Jetpack Compose code.
Focuses on real pitfalls developers encounter daily, backed by analysis of the actual
`androidx/androidx` source code (branch: `androidx-main`).

## Workflow

When helping with Compose code, follow this checklist:

### 1. Understand the request
- What Compose layer is involved? (Runtime, UI, Foundation, Material3, Navigation)
- Is this a state problem, layout problem, performance problem, or architecture question?

### 2. Consult the right reference
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

### 3. Apply and verify
- Write code that follows the patterns in the reference
- Flag any anti-patterns you see in the user's existing code
- Suggest the minimal correct solution — don't over-engineer

### 4. Cite the source
When referencing Compose internals, point to the exact source file:
```
// See: compose/runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/Composer.kt
```

## Key Principles

1. **Compose thinks in three phases**: Composition → Layout → Drawing. State reads in each
   phase only trigger work for that phase and later ones.

2. **Recomposition is frequent and cheap** — but only if you help the compiler skip unchanged
   scopes. Use stable types, avoid allocations in composable bodies.

3. **Modifier order matters**. `Modifier.padding(16.dp).background(Color.Red)` is visually
   different from `Modifier.background(Color.Red).padding(16.dp)`.

4. **State should live as low as possible** and be hoisted only as high as needed. Don't put
   everything in a ViewModel just because you can.

5. **Side effects exist to bridge Compose's declarative world with imperative APIs**. Use the
   right one for the job — misusing them causes bugs that are hard to trace.

## Source Code Receipts

Beyond the guidance docs, this skill bundles the **actual source code** from
`androidx/androidx` (branch: `androidx-main`). When you need to verify how something
works internally, or the user asks "show me the actual implementation", read
the raw source from `references/source-code/`:

| Module | Source Reference | Key Files Inside |
|--------|-----------------|------------------|
| Runtime | `references/source-code/runtime-source.md` | Composer.kt, Recomposer.kt, State.kt, Effects.kt, CompositionLocal.kt, Remember.kt, SlotTable.kt, Snapshot.kt |
| UI | `references/source-code/ui-source.md` | AndroidCompositionLocals.android.kt, Modifier.kt, Layout.kt, LayoutNode.kt, ModifierNodeElement.kt, DrawModifier.kt |
| Foundation | `references/source-code/foundation-source.md` | LazyList.kt, LazyGrid.kt, BasicTextField.kt, Clickable.kt, Scrollable.kt, Pager.kt |
| Material3 | `references/source-code/material3-source.md` | MaterialTheme.kt, ColorScheme.kt, Button.kt, Scaffold.kt, TextField.kt, NavigationBar.kt |
| Navigation | `references/source-code/navigation-source.md` | NavHost.kt, ComposeNavigator.kt, NavGraphBuilder.kt, DialogNavigator.kt |

### Two-layer approach
1. **Start with guidance** — read the topic-specific reference (e.g., `references/state-management.md`)
2. **Go deeper with source** — if the user wants receipts or you need to verify, read from `references/source-code/`

### Source tree map
```
compose/
├── runtime/runtime/src/commonMain/kotlin/androidx/compose/runtime/
├── ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/
├── ui/ui/src/commonMain/kotlin/androidx/compose/ui/
├── foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/
├── material3/material3/src/commonMain/kotlin/androidx/compose/material3/
└── navigation/navigation-compose/src/commonMain/kotlin/androidx/navigation/compose/
```

## Related Skills

- **`android-compose-patterns`** (`.github/skills/android-compose-patterns/SKILL.md`):
  RIPDPI-specific Compose conventions — ViewModel pattern, sealed Route navigation,
  DataStore -> StateFlow -> collectAsStateWithLifecycle data flow, RipDpiThemeTokens.
  Use that skill for "how does this project do X" questions; use this skill for
  "how does the Compose API work" questions.
