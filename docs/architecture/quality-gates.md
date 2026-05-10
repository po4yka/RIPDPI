# Architecture Quality Gates

RIPDPI uses static architecture checks as regression indicators. Existing debt is
captured in checked-in baselines so refactors can reduce it incrementally, while
new or worsened debt fails CI.

## Local Commands

```bash
python3 scripts/ci/check_architecture_health.py --check
python3 scripts/ci/check_file_loc_limits.py
python3 scripts/ci/check_native_hotspot_budgets.py
python3 scripts/ci/check_native_architecture_contracts.py
python3 -m unittest scripts.tests.test_architecture_health
```

The architecture checker writes:

- `build/reports/architecture/architecture-health.json`
- `build/reports/architecture/architecture-health.md`
- `build/reports/architecture/summary.txt`

## What The Gate Measures

- Dependency hubs: Android/native bridge crates, proxy runtime, monitor engine,
  and other orchestrators with broad internal crate fan-out.
- Native composition fan-out: `ripdpi-proxy-runtime-adapter`,
  `ripdpi-diagnostics-runner`, `ripdpi-runtime-services`, and
  `ripdpi-relay-core` are capped at their reduced production dependency counts.
  Dev fixtures do not count toward this metric.
- Discouraged dependency edges: concrete diagnostics lanes, policy engines, and
  platform/runtime implementation crates pulled directly into orchestration
  crates.
- Broad crate roots: `lib.rs` and `main.rs` files with large public facade or
  dispatch surfaces.
- Oversized source files and long functions/composables.
- Kotlin feature spread: service, settings, diagnostics, home, theme, and screen
  files that reference too many unrelated feature families.
- Complexity suppressions used as refactor markers.

## Baseline Policy

Baselines are debt ledgers, not targets. A baseline entry means the current
indicator is known and must not get worse.

- Do not update a baseline as part of ordinary feature work.
- Do not increase a baseline unless the change intentionally accepts debt.
- If a refactor resolves an entry, update the baseline in the same change.
- CI fails for new indicators, worsened baseline metrics, and stale full-scan
  baseline entries.
- Pre-commit checks are staged-file scoped and do not fail on unrelated stale
  entries.

## Preferred Fix Shape

- Split coordinator services by lifecycle or policy family before adding another
  dependency to the coordinator.
- Move protocol or transport implementation details behind lane-specific
  adapters instead of importing concrete lanes into orchestration crates.
- Keep composition crates as wiring points only; do not add public facade
  shortcuts to absorb a new dependency edge.
- Keep JNI, binary, and crate roots as loader/facade layers; place feature logic
  in focused modules or adapter crates.
- Split UI field bags into feature-owned models and keep screen state as a small
  aggregate.
- Replace long composables with section-level components and separate mappers
  from rendering.
