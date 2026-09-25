## Context

The four dedicated editor routes are registered in the app navigation graph, but only launch-request reachability is declared. `VpnConfigScreen` already hosts the Add profile area and paste/scan actions.

## Goals / Non-Goals

- Goal: Let users start a new profile in any dedicated editor from VPN configuration.
- Non-goal: Change saved profile editing, activation, imports, or editor ViewModels. A separate task owns complete editing for the six kinds absent from Mode Editor.

## Decisions

- Place one Add profile menu in the existing VPN action area. Use the existing context menu component and editor title strings to avoid extra UI and locale resources.
- Route menu selections through the existing `ConfigRoute`/`ConfigScreen` callback pattern to the registered destinations. Keep navigation outside leaf composables.

## Contracts and ownership

- Affected module: `:app` Compose UI and navigation only.
- Owned paths: `ConfigScreen.kt`, `VpnConfigScreen.kt`, `RipDpiNavHost.kt`, `RipDpiTestTags.kt`, focused Compose tests, this task, and this change.
- Rust crates, JNI/protobuf/wire, storage schemas, serialized shared files, and locale resources are unchanged.

## Risks / Trade-offs

- An extra menu action increases the VPN action area. Reusing one button and a four-item popup keeps the surface compact; Compose interaction tests cover the choices.
- The new links create profiles using current editor behavior. Saved profile editing remains in the separate Mode Editor task.

## Migration Plan

No migration or compatibility break. Revert the UI callbacks and menu to roll back. Verify with focused Compose tests, app unit tests and lint; remote CI and device evidence remain separate gates.
