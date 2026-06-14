# Epic: Roborazzi screenshot coverage across the screen surface

Status: in-progress (this PR closes the prominent + complex gap)
Branch: `worktree-roborazzi-coverage`
Scope: `:app` screenshot tests + recorded goldens only. No production code, schema, native, or locale changes.

## Goal

`verify-roborazzi` is a working CI gate, but full-screen coverage had gaps:
regressions on uncovered screens were invisible. This epic raises coverage of
the **prominent + complex** set — every primary user-facing screen and **every
profile/relay editor** — with deterministic light+dark goldens.

## Coverage delta

| Metric | Before | After |
| --- | --- | --- |
| Screenshot test classes | 14 | 27 (+13) |
| Golden PNGs (`app/src/test/screenshots/`) | 172 | 244 (+72) |
| Full-screen nav destinations with coverage | ~31 / 45 | ~44 / 45 |

The "~2 files / 57 screens" figure in the original ask was stale: a prior epic
had already landed `RipDpiScreenCatalogScreenshotTest` (~48 captures) plus 13
other screenshot classes (~130 methods). This PR closes the remaining real gaps.

## Newly covered screens (13 classes, light+dark, deterministic)

P0 — profile / relay editors (the priority set):
- `AmneziaWgProfileScreenshotTest` — empty-new, populated locked-cohort, invalid-field
- `SshProfileScreenshotTest` — new-empty, populated-valid, invalid-port
- `AnyTlsProfileScreenshotTest` — empty, populated-valid, invalid
- `MieruProfileScreenshotTest` — empty, populated-valid, invalid

P0 — relay import flows:
- `ProfileImportConfirmScreenshotTest` — empty, populated, imported
- `SubscriptionImportConfirmScreenshotTest` — populated, bootstrap, imported

P2 — settings editors / browsers:
- `BackupRestoreScreenshotTest` — idle, busy, policy-blocked
- `DomainBypassListScreenshotTest` — empty, populated, validation-errors
- `RuleEditorScreenshotTest` — new, populated
- `AppCustomizationScreenshotTest` — default, customized
- `AssetProviderScreenScreenshotTest` — fresh, stale (fixed clock)
- `OwnedStackBrowserScreenshotTest` — populated, empty

P3:
- `SharedResultRenderScreenshotTest` — populated, empty, invalid

## Deliberately NOT covered

- **QR Scanner** (`QrScannerRoute`) — a live `CameraX` preview surface; there is
  no deterministic still to capture. Excluded by design, not an oversight.

## Determinism

- New goldens render through `captureScreenBothThemes(...)` (added to
  `RipDpiScreenshotTestSupport.kt`), which sets `ripdpi.staticMotion=true`
  (disables all animation/infinite-transition specs) and wraps each screen in
  `RipDpiTheme(themePreference = "light" | "dark")`.
- Clock-based screens use fixed inputs: `AssetProvider` staleness is driven by a
  fixed `lastUpdated` epoch; `SharedResultRender` uses a fixed 24-bit-valid
  `timestampMinutes`; `BackupRestore` is captured via the stateless
  `BackupRestoreScreen` (the `Date()`-based filename lives in the Route, not the
  screen).
- Each editor renders its **stateless** screen composable with fabricated
  `UiState` (no `hiltViewModel`), so no live ViewModel / IO / random enters the
  render path.

## Verification

- `:app:compileGithubDebugUnitTestKotlin` ✅
- Record (scoped `--tests` to the 13 new classes only, `-Proborazzi.test.record=true`,
  `-Pripdpi.includeRoborazziUnitTests=true`) — 72 new goldens written; existing
  goldens never re-recorded.
- `:app:verifyRoborazziGithubDebug` (full) ✅ — all 244 goldens pass; **0 existing
  goldens modified** (`git status` shows 72 new, 0 changed). This is new coverage,
  not a re-bless of changed contracts (`.claude/rules/golden-bless-discipline.md`).
- `:app:ktlintTestSourceSetCheck` ✅ · `:app:detekt` ✅
