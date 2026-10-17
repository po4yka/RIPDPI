## Context

RDS owns component shells, state resolvers, surfaces, and motion. Material text and controls do not consume Foundation styles. The current stack is BOM 2026.09.00, Compose 1.12.1, Material3 1.4.0, and compile SDK 37.0.

## Goals / Non-Goals

- Goal: Apply CommonStyle to eligible RDS shells and retain current visual and interaction contracts.
- Non-goal: Redesign screens, replace Material text, change services or native code, or update golden fixtures.

## Decisions

- Use published compose-bom-beta 2026.10.00 with Compose 1.13.0-beta01 and Material3 1.5.0-beta01. Do not add a snapshot repository.
- Add ripdpi.compileSdkMinor=1 and wire all SDK consumers and bootstrap paths to SDK 37.1. Keep targetSdk 37 and minSdk 27.
- Use RDS-owned stylePropertyOf keys, CommonStyle DSL extensions, and StyleResolver application helpers. Do not use transitional Style, styleable, styleAppearance, or stylePlacement.
- Expose visualStyle with an empty CommonStyle default. Preserve all current variant, density, action, state, and interaction-source parameters.
- Apply base, variant, then caller overrides. Enforce behavior and minimum touch targets outside visual styles.
- Defaults evaluate existing state resolvers directly on layer zero. Apply caller overrides in a raised style layer so active default states cannot defeat explicit overrides.
- Read theme locals at resolution time. Share the actual interaction source with style state. Preserve current state priority and motion specs.
- Apply outer surfaces separately from inner content transforms. Preserve numeric corner-radius interpolation and modifier order.
- Keep explicit Material typography, colors, icons, text animations, custom Canvas/progress drawing, and navigation transitions where required. Document each exception.
- Use file-level experimental opt-in. Do not enable global inherited text styles.

## Contracts and ownership

- Affected: app, build-logic conventions, baselineprofile, SDK bootstrap, and design documentation.
- Serialized shared files: version catalog, Gradle properties, and SDK bootstrap. styles-integration is the single writer until a path allocation is recorded in the portfolio task.
- No Rust crates, JNI, protobuf, storage, network, or service contracts change. Glance retains current tokens.
- Use a dedicated job worktree and scoped Conventional Commits. Main integration, publication, and golden blessing require separate authorization.

## Risks / Trade-offs

- Experimental upstream churn: pin the full published BOM and use published sources as API truth.
- SDK or dependency incompatibility: stop dependent waves at the compile gate; record evidence.
- Geometry, semantics, or motion regression: compare screenshots and state/interaction tests per family.
- Performance regression: compare original, dependency-only, and styled profileable runs on the same device. Reject regressions above five percent in two repeated series.

## Migration Plan

1. Verify published artifacts and SDK availability, update dependencies and build configuration, then compile both app variants.
2. Add and test the adapter and migrate buttons.
3. Migrate input shells, shared surfaces, and eligible screen recipes in separate validated units.
4. Run Full/Simple compilation, unit tests, verifyScreenshots, staticAnalysis, and both debug assemblies. Run build convention tests.
5. Run device acceptance using the emulator pool and comparable profileable benchmarks. Keep unavailable evidence explicit.
6. Revert only task-owned commits if the stack is rejected. There is no persisted migration or remote rollback action.
