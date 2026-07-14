# Compose UI/UX Audit — Resolution Ledger (2026-06-15)

Disposition of all **148** findings from [compose-uiux-audit-2026-06-15.md](compose-uiux-audit-2026-06-15.md),
implemented on branch `fix/compose-uiux-audit`. Every fixable finding is its own
atomic commit (`Closes audit finding idx N` in the body). At that branch's final commit, these gates were green:
`:app:compileGithubDebugKotlin`, `:app:testGithubDebugUnitTest`, `:app:detekt`,
`:app:ktlintCheck`, `:app:lintGithubDebug` (incl. `MissingTranslation` across the then-current 8 locales),
plus the regenerated `config/i18n/translatable-keys.txt` manifest.

| Disposition | Count |
|---|---|
| ✅ Fixed & committed | 96 |
| ⚪ Invalid (stale/hallucinated — code does not match current source) | 28 |
| ⚪ Already-correct at HEAD (already migrated to stringResource/tokens) | 13 |
| 🟡 Won't-fix (justified) | 10 |
| ⏭️ Deferred (needs multi-file ViewModel redesign) | 1 |
| **Total** | **148** |

> The audit ran with a verification caveat (only 19/148 adversarially pre-verified). On
> implementation, **41 of 148 (~28%) proved invalid or already-correct** — every finding was
> re-verified against current source before any edit, per project policy ("source-verify before fixing").

## ✅ Fixed (96)

idx: 0, 5, 7, 10, 15, 17, 18, 19, 22, 24, 25, 26, 28, 29, 30, 31, 36, 37, 40, 41, 42, 43, 44, 45, 46,
51, 53, 54, 55, 56, 57, 58, 59, 60, 63, 64, 66, 67, 71, 72, 74, 75, 76, 77, 78, 79, 80, 81, 83, 87,
89, 90, 92, 93, 94, 96, 97, 100, 102, 103, 106, 108, 110, 111, 112, 113, 114, 115, 116, 117, 118,
119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 131, 132, 133, 134, 136, 137, 138, 141, 142,
143, 144, 145, 146, 147.

Themes:
- **i18n** — new string/plural/array keys added to all 8 locales shipped at the time (en, ru, es, de, fr, fa, ar, zh-rCN)
  with correct CLDR plural categories; concatenations replaced with format resources; `Locale.US`
  formatters switched to the observable device locale; the two real Criticals (idx 25 outbound labels,
  idx 26 routing-rule summary) resolved via the existing `StringResolver`/`pluralStringResource` paths.
- **RDS tokens** — `MaterialTheme.colorScheme/typography/shapes` reads and `.dp`/`.sp`/`tween`/`spring`
  literals in the component layer replaced with `RipDpiThemeTokens.*` / `RipDpiStroke` / `RipDpiIconSizes`;
  new component-metric tokens added to `ui/theme/Spacing.kt` where no exact token existed (value-preserving).
- **Accessibility** — `contentDescription`/`Role`/`stateDescription`/`semantics` via existing helpers;
  decorative icons set to `null`; Canvas charts and the stale-data badge gained non-color cues.
- **Recomposition / state** — `rememberSaveable`, `derivedStateOf`-gated auto-scroll, narrowed params,
  state-keyed crossfades, optimistic-reorder rollback.

## ⚪ Invalid — code does not match the cited source (28)

The audit cited hardcoded strings/tokens/line numbers that do not exist in current source (stale or
hallucinated). Verified per-file; no change needed.

idx: 1, 2, 3, 4, 8, 11, 13, 21, 27, 32, 38, 39, 49, 61, 62, 65, 68, 73, 82, 84, 85, 98, 99, 101,
104, 105, 130, 135.

## ⚪ Already-correct at HEAD (13)

The flagged code already uses `stringResource`/tokens/correct a11y in current source (the audit was
run against the same HEAD but these auditors over-reported). Quoted proof captured during verification.

idx: 6, 12, 14, 16, 33, 34, 35, 47, 48, 50, 69, 70, 107.
(Includes the original Critical idx 47 `RipDpiCidrInput` — already fully `stringResource`-backed.)

## 🟡 Won't-fix (justified) (10)

| idx | File | Reason |
|---|---|---|
| 9 | AdvancedSettingsHelpers.kt | Fake-payload labels are proper nouns / protocol names (IANA, Cloudflare, Google Chrome, WireGuard, DHT, STUN…). Not translatable; the lone descriptive fallback is pinned by a pure-function contract test. |
| 20 | Relay secret fields | "Reveal toggle" for typed secrets. Config editors conventionally show typed input; secrets are not logged/persisted/telemetered (no Data-Safety leak); low shoulder-surf-only concern; shared-helper plumbing across many field files is disproportionate. |
| 23 | HomeChrome.kt | `HomeChromeMetrics` is a width-responsive `@Immutable` metrics holder — the same established pattern as `ripDpiLayoutForWidth`. Relocating into `ui/theme/` is a large, golden-affecting, multi-file change; accepted pattern, not a raw-literal leak. |
| 52 | RipDpiInteraction.kt | Verifier downgraded high→medium: a known-acceptable tradeoff, not a correctness bug; the proposed `Modifier.Node` rewrite is disproportionate. |
| 86 | HistoryFilters.kt | `Char.uppercase()` is locale-independent in Kotlin (false-positive casing concern). Localizing the open-ended option token set needs a ViewModel/data-layer mapping — out of UI scope. |
| 91 | HomeModeCard.kt | Audit rates it "acceptable as-is, low/optional"; a kill-switch confirm dialog needs new VM/dialog state with no clear defect. |
| 95 | RipDpiIntroScaffoldMetrics.kt | Shared width-responsive token table (drives onboarding + biometric + vpn-permission; tuning re-blesses goldens). Single-sourced, not screen literals. |
| 109 | LogsScreen.kt | Visible metadata chips are technical tokens (`runtime:`/`scan:`); the clip label is non-user-facing. |
| 139 | DiagnosticsWidgets.kt | The only shared candidate conflates a chip *width* with an unrelated chip *height* (coincidentally equal) — promoting it would be token-misuse. `SparklineChartHeight` is defensible as a local const. |
| 140 | widget/theme/RipDpiGlanceColors.kt | Glance cannot consume CompositionLocals; literal duplication is the accepted pattern and parity currently holds. Eliminating drift needs a shared `RipDpiRawPalette` + parity test (separate refactor). |

## ⏭️ Deferred (1)

| idx | File | Reason / follow-up |
|---|---|---|
| 88 | DnsSettingsRoute.kt | Optimistic in-flight state + rollback + failure surface for the IPv6 switch requires coordinated changes across `SettingsViewModel.updateSetting` → `SettingsMutationRunner` → the UI state model. No fix is containable to the screen; tracked for a dedicated ViewModel follow-up. |
