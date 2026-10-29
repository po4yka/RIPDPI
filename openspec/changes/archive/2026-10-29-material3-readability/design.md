## Context

Task ID: `UIX-1791793531264679`. The audit found small primary roles and split-width settings rows. See proposal.md. The user has requested implementation and commits on main.

## Goals / Non-Goals

- Goal: improve primary text, standalone icons, settings rows, and diagnostics navigation.
- Non-goal: change color policy, fonts, network behavior, storage, locales, or the expert information architecture.

## Decisions

- Promote body and emphasis roles to 16/24sp; retain secondaryBody at 14/20sp and caption at 12/16sp for metadata. Use secondaryBody for settings descriptions.
- Promote screen and app-bar titles to Material 3 sizes, and use 24dp standalone icons. Small inline glyphs retain their existing roles.
- Place settings values in the text column. Give switches and chevrons intrinsic width. Allow row height to grow.
- Use PrimaryScrollableTabRow and Tab for three diagnostics sections at all font scales. Preserve automation tags, selected semantics, callbacks, and pager behavior.
- Keep monochrome RDS. Consume all colors and interaction state through existing theme contracts.
- The RDS HTML previews type-body, type-display, icon-set, components-settings-row, and components-tabs remain read-only. DESIGN.md supersedes their older compact sizes for this change.

## Contracts and ownership

- Only :app, UI tests, design documentation, and planning artifacts change. No Rust, JNI, protobuf, storage, or dependency changes.
- Ownership is recorded in docs/tasks/issues/material3-readability.md. Workers use separate worktrees and do not edit shared fixtures or planning artifacts.
- Integration owner serializes DESIGN.md, shared theme integration, screenshot review, and board updates.

## Risks / Trade-offs

- Larger type can increase line wrapping. Test primary actions, long settings values, Russian, and RTL at 2x font scale.
- Shared type changes invalidate screenshot fixtures. Produce disposable visual artifacts first; do not bless fixtures without explicit user authorization.
- Scrollable tabs can require a swipe to reach a section at large font scale. Preserve accessible tab semantics and test reachability.

## Migration Plan

No persisted migration is required. Revert the UI commits to restore the old layout. Run theme and affected component tests, app/service lint, and combined-tree architecture and metadata gates. Inspect fresh light, dark, and 2x images before integration. Main integration is authorized; push is not requested.

## App-wide follow-up

The first stage is verified at 15b634db6. The next pass fixes the same classes across the app:

- Compact density changes padding, not standalone action recognition: use 24dp action glyphs and 48dp hit areas. Keep 16dp telemetry/status markers; chip inline glyphs use 18dp.
- Decorative leading feedback, DNS, import, and recovery symbols use 32dp inside 40dp. Shared feedback metrics are owned by one writer and land before dependent screen checks.
- Action labels and primary navigation labels remain readable at 320/420dp and 2x font, without shrinking fonts or losing selection/callback semantics.
- Choice groups and localized metadata use content-driven wrap or vertical layout; remove parent fixed heights and equal-width constraints that compress chip labels.
- Headers reserve width for controls. Important facts use supporting body text, not dense metadata roles.
- Throughput window choices and stat cells each contain their background and text in one surface. OOM actions use complete labels and sufficient width. Narrow/large-font import and stat grids adapt to one column where required.
- Match the existing RDS previews for buttons, chips, warning banners, menus, bottom navigation, DNS, graph, import, OOM, and history. Their HTML remains read-only; fresh Compose renders and tested implementation define the adaptive correction.

Source lanes and files are recorded in the portfolio ownership section. Planning and validation cover all reported findings; no palette, font family, protocol, storage, runtime, or production dependency changes are owned by this follow-up.

## Localized word wrapping

Sans roles use `Hyphens.Auto` with `LineBreak.Simple` when a word exceeds a complete line. Adaptive containers first give text the width that icons or controls would otherwise remove. The app resource configuration supplies explicit text locales for token and Material styles. Type metrics, resource text, and Java locale globals do not change. Native pixel rounding must match each actual padding edge in diagram width calculations.
