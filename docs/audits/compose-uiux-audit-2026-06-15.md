# RIPDPI — Compose UI/UX Audit

> **Historical status:** this is the original finding snapshot. See
> [the resolution ledger](compose-uiux-audit-2026-06-15-resolution.md) for the
> verified disposition: 96 fixed, 41 invalid/already correct, 10 won't-fix,
> and 1 deferred. Do not treat the unmodified findings below as the current UI.

**Date:** 2026-06-15 · **Scope:** all Jetpack Compose UI under `app/src/main/kotlin/com/poyka/ripdpi/ui` + `widget/` (255 `@Composable` files)

**Method:** multi-agent workflow — 9 area auditors (one per UI cluster) + a dedicated RDS-token-discipline pass + a cross-cutting a11y/i18n sweep (11 auditors total), then an adversarial verification pass and synthesis. Each finding is anchored to file:line across 7 lenses: RDS token discipline, accessibility, recomposition/perf, state, i18n, Material 3 consistency, and UX completeness.

> **Verification caveat:** the adversarial verify phase was throttled by transient server-side rate limiting, so only **19 of 148** findings were independently re-confirmed against source. The remaining findings are single-auditor reports retained *fail-open* (a finding is only dropped when a verifier explicitly refuted it). Treat unverified Critical/High items as high-confidence-but-spot-check. Verified items are marked ✓.

## Executive summary

148 findings surfaced. The codebase has a mature, well-factored design-token system (`RipDpiTheme`/`RipDpiMotion`/`RipDpiSurface`), and most screens consume it correctly — but three systemic gaps recur across the app:

1. **Internationalization (56 findings)** is the single largest theme: hardcoded English string literals, string concatenation of translated fragments, and `Locale.US`-pinned date/number formatting leak through many screens. With `lint.xml MissingTranslation severity="error"` across 8 locales, these are real ship-blockers that lint can't catch (English embedded in Kotlin is invisible to it).
2. **RDS token leaks (41)** — stray `.dp`/`.sp` literals, `MaterialTheme.typography/colorScheme` reads, and literal animation specs in the component/screen layer, contradicting the RDS contract (some files even carry KDoc *asserting* token purity that is now false).
3. **Accessibility (27)** — Canvas-drawn charts and status indicators encode state by color alone with no semantics/contentDescription, plus icon-buttons missing descriptions and sub-48dp touch targets.

| Severity | Count |
|---|---|
| 🔴 Critical | 5 |
| 🟠 High | 43 |
| 🟡 Medium | 53 |
| ⚪ Low | 47 |
| **Total** | **148** |

**By category:** Internationalization (i18n) 56 · RDS token discipline 41 · Accessibility 27 · UX completeness / flow 7 · Recomposition / performance 7 · Consistency 5 · State management 4 · Material 3 / consistency 1

## Findings by severity

### 🔴 Critical (5)

#### Hardcoded English labels in TUIC, ShadowTLS, Snowflake, WebTunnel, obfs4, Tor relay fields
- **Where:** `ui/screens/config/RelayTuicFields.kt:23,28,33,36,44,51`  ·  *Internationalization (i18n)*  ·  area: config
- **Issue:** RelayTuicFields: label = "TUIC UUID" (23), "TUIC password" (28), "Enable 0-RTT" (33), Text "Congestion control" (36), chip labels "BBR" (44)/"CUBIC" (51). Same pattern in RelayShadowTlsFields.kt ("Inner profile ID" 16, "ShadowTLS password" 21), RelaySnowflakeFields.kt ("Broker URL" 16, "Front domain" 21), RelayWebTunnelFields.kt ("WebTunnel URL" 16), RelayObfs4Fields.kt ("Bridge line" 18, helper 19), RelayTorFields.kt ("Bridge line" 28, multi-line helper 30-31, WarningBanner title 35 + body 37-38).
- **Fix:** Extract all these field labels, helper texts, and warning copy into strings.xml (config_relay_tuic_uuid, config_relay_shadowtls_password, config_relay_tor_caveat_title/body, etc.) and reference via stringResource. Protocol acronyms (BBR/CUBIC/UUID) may stay literal but should still live in resources for consistency. Land in all 7 locales.

#### Hardcoded English outbound labels shown in rule editor and routes list
- **Where:** `ui/screens/routes/OutboundTargetProvider.kt:121-123, 90, 97`  ·  *Internationalization (i18n)*  ·  area: onboarding-perms
- **Issue:** BuiltInProxyLabel="Proxy", BuiltInBypassLabel="Bypass (direct)", BuiltInBlockLabel="Block" are const String literals, plus fallbacks "Group #${tag.groupId}" / "Profile #${tag.profileId}". These flow into RipDpiDropdownOption.label (RuleEditorScreen.kt:258) and RuleRow.outboundLabel rendered in RoutesScreen.kt:202/241. They are user-facing UI text but live in Kotlin, so lint MissingTranslation=error never catches them; all 8 locales show English.
- **Fix:** Move these to strings.xml (R.string.outbound_proxy, outbound_bypass, outbound_block) and resolve via a StringResolver in the catalog (it is a @Singleton, not a composable). For the #id fallbacks use a plural/formatted resource. The OnboardingDnsCatalog protocol labels are a justified code-constant exception (locale-invariant proper…

#### Rule summary line built from hardcoded English fragments
- **Where:** `ui/screens/routes/RoutesViewModel.kt:118-130`  ·  *Internationalization (i18n)*  ·  area: onboarding-perms
- **Issue:** ruleSummaryParts adds literal strings "$domains domains", "$ips IPs", "$ports ports", "$sourcePorts src ports", "process", "${rule.packages.size} apps", "TCP"/"UDP"/"TCP+UDP". Result is concatenated in ruleSummaryLine (RoutesScreen.kt:239-242) and shown as each rule's subtitle. Untranslated in all 8 locales and uses English pluralization rules.
- **Fix:** Replace with quantity strings (plurals) and stringResource. ruleSummaryParts is a @Composable-adjacent pure fn called from a @Composable (ruleSummaryLine is @Composable) so it can take resolved strings, or move the assembly into the composable using pluralStringResource. Concatenating translated fragments is itself an i18n anti-pattern —…

#### Hardcoded English route-state labels and accessibility description in production component
- **Where:** `ui/components/routes/RipDpiRouteComponents.kt:408, 523-533, 547`  ·  *Internationalization (i18n)*  ·  area: onboarding-perms
- **Issue:** RouteStateBadge (used in production via RouteProfileHeader line 223) renders label = if (active) "Active" else state.label(); label() returns hardcoded "Available"/"Selected"/"Configured"/"Setup"/"Restricted"/"Active"/"Degraded"/"Failed". accessibilityDescription() (line 547, wired into semantics{contentDescription} at line 287) builds "Secure route stack: ... to ..." with hardcoded English plus lowercased English status words. All visible to sighted users and TalkBack in every locale as English.
- **Fix:** Route every state label and the stack accessibility description through stringResource. Since these are pure functions, resolve the strings in the composable (resolve a Map<RipDpiRouteAvailabilityState,String> at the call site) or pass a label provider. The contentDescription path is doubly important: it is the screen-reader experience.

#### Hardcoded user-facing strings in production CIDR input (not localized)
- **Where:** `ui/components/inputs/RipDpiCidrInput.kt:73, 80, 117, 119`  ·  *Internationalization (i18n)*  ·  area: components-inputs
- **Issue:** `label: String = "CIDR"` default; segmented options `listOf("IPv4", "IPv6")`; validation hints `"Invalid CIDR — expected dotted-quad/0..32"` and `"...hex-colon/0..128"` are raw literals rendered to the user. These are production composables, not previews. With lint.xml MissingTranslation=error and 8 required locales, none of this text is translatable.
- **Fix:** Move all user-facing strings to stringResource(R.string.*) and ship keys into all 8 locale strings.xml files. The default label and validation hints in particular must come from resources.

### 🟠 High (43)

#### Confidence/branch logic substring-matches an English word inside a user-facing (localized) string
- **Where:** `ui/screens/home/HomeAnalysisBottomSheets.kt:261-268`  ·  *Internationalization (i18n)*  ·  area: home-history
- **Issue:** HomeConfidenceRow drives confidenceColor via value.contains("low"/"medium"/"high", ignoreCase = true) on `value: String?` that is rendered to the user (line 280 Text(text = it ...)). The app ships 8 locales; in ru/de/fr/fa/ar/zh-CN the confidence text will not contain the English substrings, so every non-English user falls into the `else -> colors.foreground` branch and loses the red/amber/green semantic coloring. android docs (kb://android/guide/topics/resources/localization) confirms UI behavior driven by data…
- **Fix:** Color must come from a typed field on the UiState (e.g. a Confidence enum or a tone token already resolved in the ViewModel), never from substring-matching a display string. Pass a `confidenceTone` alongside `confidenceSummary` and map enum -> colors.destructive/warning/success.

#### Concatenated translated strings build sentences/labels at the UI layer
- **Where:** `ui/screens/home/HomeNetworkConditionBanner.kt:49-53`  ·  *Internationalization (i18n)*  ·  area: home-history
- **Issue:** message = "$body ${stringResource(R.string.home_network_condition_whitelist_relay_suggestion)}" concatenates two independently-translated strings with a hard space. Word order and spacing differ across the 8 locales (notably ar RTL and zh-CN which has no inter-sentence space), so the joined result reads wrong. Same anti-pattern in HomeAnalysisSheetSections.kt:209-225 (stringResource(...) + ": " + joinToString) and HomeScreen.kt:263 ("$label →").
- **Fix:** Provide a single format string per composed message with %1$s/%2$s placeholders (and <xliff:g> for the non-translated arrow/joined list per the localization KB), and let translators control order/spacing. Replace the runtime concatenations in HomeNetworkConditionBanner.kt:51, HomeAnalysisSheetSections.kt:211/219, HomeScreen.kt:263.

#### Detection enum displayName rendered as visible text and screen-reader label (hardcoded English)
- **Where:** `ui/screens/detection/StatusVisualIndicator.kt:41, 132 (and DetectionCheckScreen.kt:378)`  ·  *Internationalization (i18n)*  ·  area: protocol-screens
- **Issue:** StatusVisualIndicator default `contentDescription: String = state.displayName` (line 41) and `Text(text = state.displayName)` (line 132); DetectionCheckScreen.kt:378 `RipDpiChip(text = mode.displayName)`. DetectionVisualState.displayName ("Clean"/"Review"/"Detected") and DetectionColorVisionMode.displayName ("Standard"/"Red/green safe"/"Blue/yellow safe"/"Achromatopsia") are hardcoded English literals in core/detection/.../ui/StatusVisualResolver.kt:7-10,34-38. These reach both visible UI and the screen-reader…
- **Fix:** Bridge the core enums to R.string via a key->R.string map in the app layer (mirror the existing Xray `stringIdFor(key)` pattern in XrayProfileImportScreen.kt:231). Never use a core enum's English displayName directly as visible Text or contentDescription. Add the new keys to all 8 locales in the same commit (lint…

#### DetectionResultCards renders many hardcoded English strings instead of stringResource
- **Where:** `ui/screens/detection/DetectionResultCards.kt:147,184,190,195,201,216,256,262-263,322,332,353,602,609,615,621,772-776`  ·  *Internationalization (i18n)*  ·  area: protocol-screens
- **Issue:** Literal user-facing text in Text composables: "Reason: ${it.reason}" (190), "What to adjust: ${it.adjustmentHint}" (195), "Where masking applied, stealth" (216), "VERDICT NARRATIVE" (322), "What was discovered" (332), "Why this verdict" (353), probeSummary "Probe outcome: $detected/$total signals exposed" (256), fallback headlines (262-263), category titles "CDN pulling"/"Call transport"/"IP consensus"/"Native signs" (602,609,615,621), and ExposureStatus.displayLabel() set "Remote endpoint"/"Public IP"/...…
- **Fix:** Move every literal into R.string and resolve via stringResource; for the ExposureStatus.displayLabel() set, use a @Composable when-to-stringResource mapping like blockcheck's BlockLayer.label(). Add keys to all 8 locales in the same commit.

#### RoutingProtectionRecommendations builds user-facing recommendation text as hardcoded English
- **Where:** `ui/screens/detection/RoutingProtectionRecommendations.kt:40-44,51-54,62-65,76-80`  ·  *Internationalization (i18n)*  ·  area: protocol-screens
- **Issue:** buildRoutingProtectionRecommendations() constructs Recommendation(title="App routing protection is available", description="Known whitelist-sensitive apps are installed...", ...) with English literals. These titles/descriptions are rendered verbatim by DetectionRecommendations() (DetectionResultCards.kt:455-456) as visible Text.
- **Fix:** Recommendation title/description must be R.string references (or resolved at the screen). Since this builder is in the services/data layer without R access, pass string keys or build the localized text in the composable. Add keys to all 8 locales.

#### SubscriptionFailover summary/labels built as hardcoded English in the ViewModel and rendered verbatim
- **Where:** `ui/screens/subscription/SubscriptionFailoverViewModel.kt:110,113-114,118,144,147,149`  ·  *Internationalization (i18n)*  ·  area: protocol-screens
- **Issue:** recentEvent default "no switchovers observed" (110); summary "server $activePosition/${size} ${activeStatus.label} · $recentEvent · $lastCheck" (113-114); activeServerLabel "Server $activePosition: ..." (118); positionLabel "server ${index+1}/$total" (144); detail "current server"/"available if the app switches" (147,149). These are rendered untranslated by SubscriptionFailoverScreen.kt (uiState.summary, activeServerLabel, server.positionLabel, server.detail) and concatenated with ' · '.
- **Fix:** Compose the summary/labels in the composable from R.string format resources, or pass structured data + string keys out of the VM. Avoid building translated sentences by concatenation. Cover all 8 locales.

#### DetectionCheckScreen has hardcoded English in toggles, banners, and the share/export dialog
- **Where:** `ui/screens/detection/DetectionCheckScreen.kt:346-347,364,388,418,432,678,741,745,754,765`  ·  *Internationalization (i18n)*  ·  area: protocol-screens
- **Issue:** WarningBanner title="TLS keylog enabled", message="Secrets written to $path..." (346-347); "Status visuals" (364); "Protanopia-safe red/green mode is unlocked." (388); RipDpiSwitch label="CDN trace and TLS MITM check" (418) and "Debug diagnostics" (432); RipDpiButton text="Copy diagnostics" (678); dialog title="Share detection report" (741); button texts "Markdown"/"JSON"/"Copy Markdown" (745,754,765). All ship untranslated.
- **Fix:** Replace all literals with stringResource(...). Format "Markdown"/"JSON" are format names but the surrounding actions should still be localized labels. Add keys to all 8 locales.

#### Hardcoded English a11y description with enum name in RipDpiStaleDataBadge
- **Where:** `ui/components/indicators/RipDpiStaleDataBadge.kt:119`  ·  *Internationalization (i18n)*  ·  area: components-feedback
- **Issue:** contentDescription = "$label, ${tier.name.lowercase()} data" — the word 'data' and the tier enum name (fresh/recent/aging/stale/expired) are unlocalized English.
- **Fix:** Resolve the tier to a localized string and use a stringResource format with the label argument.

#### Hardcoded English a11y summary built in AnalysisProgressIndicator
- **Where:** `ui/components/indicators/AnalysisProgressIndicator.kt:125-133`  ·  *Internationalization (i18n)*  ·  area: components-feedback
- **Issue:** buildStageDescription appends "$completed completed", ", $running running", ", $failed failed" — this whole string becomes the merged-semantics contentDescription (line 88) and is auto-announced as a Polite live region in English only. Counts are also not localized via plurals.
- **Fix:** Build the description from getQuantityString plurals (as StageProgressIndicator.kt lines 71-77 already do) rather than string concatenation; pass a Resources/Context in.

#### Visible (non-a11y) summary text not localized in StageProgressIndicator
- **Where:** `ui/components/indicators/StageProgressIndicator.kt:27-28, 122-123, 138`  ·  *Internationalization (i18n)*  ·  area: components-feedback
- **Issue:** private const val PassedLabel = "passed" / FailedLabel = "failed"; rendered visibly as Text("${part.count} ${part.label}"). The a11y description correctly uses plurals, but the on-screen summary chips show hardcoded English.
- **Fix:** Render the visible summary from the same R.plurals.stage_passed_count / stage_failed_count resources instead of the hardcoded constants.

#### Hardcoded 'Clear' label in production filter bar
- **Where:** `ui/components/inputs/RipDpiFilterBar.kt:64`  ·  *Internationalization (i18n)*  ·  area: components-inputs
- **Issue:** `RipDpiChip(text = "Clear", onClick = onClearAll, selected = false)` — the clear-all affordance label is a hardcoded English literal in shipping code.
- **Fix:** Replace with stringResource(R.string.filter_clear_all) (or accept a clearLabel param) and add to all 8 locales.

#### Hardcoded contentDescription strings in Stepper (screen-reader text not localized)
- **Where:** `ui/components/inputs/RipDpiStepper.kt:78, 97`  ·  *Internationalization (i18n)*  ·  area: components-inputs
- **Issue:** `description = "Decrement"` and `description = "Increment"` are passed as both onClickLabel and Icon contentDescription. Screen-reader users in non-English locales hear English.
- **Fix:** Source from stringResource(R.string.stepper_decrement / _increment) or require the caller to pass localized descriptions.

#### Hardcoded fallback contentDescription 'Slider'
- **Where:** `ui/components/inputs/RipDpiSlider.kt:60`  ·  *Internationalization (i18n)*  ·  area: components-inputs
- **Issue:** `.semantics { contentDescription = label ?: "Slider" }` — when no label is supplied the screen reader announces the English literal 'Slider'.
- **Fix:** Use a stringResource fallback (e.g. R.string.a11y_slider) instead of a hardcoded literal.

#### Non-localized stateDescription derived from enum name
- **Where:** `ui/components/inputs/RipDpiConnectionActuator.kt:449`  ·  *Internationalization (i18n)*  ·  area: components-inputs
- **Issue:** `stateDescription = stage.state.name.lowercase()` exposes the raw Kotlin enum constant (e.g. 'pending', 'warning') as the accessibility state description — English-only and developer-facing, not a localized string.
- **Fix:** Map each HomeConnectionActuatorStageState to a stringResource and assign that to stateDescription.

#### Hardcoded English a11y contentDescription "Loading" in RipDpiSpinner (never localized, spoken by TalkBack)
- **Where:** `ui/components/indicators/RipDpiSpinner.kt:46`  ·  *Internationalization (i18n)*  ·  area: a11y-i18n
- **Issue:** .semantics { contentDescription = "Loading" } — a literal English string is the only accessible name for the spinner. RipDpiSwitch in the same module already uses stringResource(R.string.semantic_state_on/off), proving the resource pattern exists. lint's MissingTranslation gate cannot see this string because it is a Kotlin literal, not a strings.xml key.
- **Fix:** Replace with stringResource(R.string.semantic_loading) (add the key to all 8 locale strings.xml in the same commit). Pass it into the composable or read it via stringResource at the call boundary since .semantics{} cannot call @Composable directly.

#### Hardcoded "Progress N%" / "Loading" a11y string in RipDpiProgressBar
- **Where:** `ui/components/indicators/RipDpiProgressBar.kt:40-41`  ·  *Internationalization (i18n)*  ·  area: a11y-i18n
- **Issue:** contentDescription = if (progress != null) "Progress ${(progress * ProgressPercentFactor).toInt()}%" else "Loading". English word "Progress" plus a manually concatenated "%" (locale-unsafe percent) are spoken by TalkBack and never reach strings.xml. The progressBarRangeInfo semantics that Compose would normally supply is overridden by this literal.
- **Fix:** Use stringResource(R.string.semantic_progress_percent, localizedPercent) where the percent is formatted with NumberFormat.getPercentInstance(); use stringResource(R.string.semantic_loading) for the indeterminate branch. Prefer setting progressBarRangeInfo rather than a hand-built string so the platform localizes the percentage.

#### Hardcoded English "Heartbeat <state>" a11y description with unlocalized enum name in RipDpiHeartbeatIndicator
- **Where:** `ui/components/indicators/RipDpiHeartbeatIndicator.kt:85`  ·  *Internationalization (i18n)*  ·  area: a11y-i18n
- **Issue:** contentDescription = "Heartbeat ${state.name.lowercase()}". Both the literal "Heartbeat" and the lowercased enum identifier (e.g. "connecting") are read aloud by TalkBack with no translation. Enum .name is a code identifier, not localizable copy.
- **Fix:** Map each state to a stringResource and compose via a parameterized resource: stringResource(R.string.semantic_heartbeat_state, stringResource(state.labelRes)). Never feed enum.name into contentDescription/stateDescription.

#### Hardcoded "<label>, <tier> data" a11y description concatenating an unlocalized enum name in RipDpiStaleDataBadge
- **Where:** `ui/components/indicators/RipDpiStaleDataBadge.kt:119`  ·  *Internationalization (i18n)*  ·  area: a11y-i18n
- **Issue:** contentDescription = "$label, ${tier.name.lowercase()} data". The English word "data", the comma-join word order, and tier.name.lowercase() (e.g. "stale"/"fresh") are all hardcoded; word order and the trailing noun differ per language. RTL locales (ar, fa) will also mis-order the comma-separated fragments.
- **Fix:** Use a single parameterized resource stringResource(R.string.semantic_stale_badge, label, stringResource(tier.labelRes)) so translators control word order and the tier label is localized.

#### Hardcoded "Coach mark: <title>. <body>" a11y description and "Dismiss" click label in RipDpiCoachMark
- **Where:** `ui/components/feedback/RipDpiCoachMark.kt:122, 178`  ·  *Internationalization (i18n)*  ·  area: a11y-i18n
- **Issue:** Line 122: .ripDpiClickable(onClickLabel = "Dismiss", onClick = onDismiss) — the TalkBack action label is hardcoded English. Line 178: contentDescription = "Coach mark: ${content.title}. ${content.body}" — the literal prefix "Coach mark:" is English-only. Both are accessibility-only strings invisible to the MissingTranslation lint.
- **Fix:** onClickLabel = stringResource(R.string.action_dismiss); build the description from a parameterized resource stringResource(R.string.semantic_coach_mark, content.title, content.body).

#### Raw N.dp literals in the screen layer (RDS token violation) across home/dns/history/customization
- **Where:** `ui/screens/home/HomeChrome.kt:22-27, 41-57`  ·  *RDS token discipline*  ·  area: home-history
- **Issue:** RDS contract (rds-spec.md) and the token tests forbid `N.dp` outside ui/theme/; Spacing.kt is the sanctioned home for dp tokens. HomeChromeMetrics hard-codes 72/192/172/20/24/28.dp (and Medium/Expanded variants). Because these sit in @Immutable data-class default args, the pre-commit grep in rds-spec.md misses them, but they are still component-layer dp literals. Same pattern: HomeConnectionButtonLayout.kt:88 (1.dp border) + Spacer dp via connectionButtonIconSpacerDp.dp/connectionButtonModeSpacerDp.dp,…
- **Fix:** Promote these dimensions into ui/theme/ tokens (extend RipDpiThemeTokens.layout / a components token group, or move HomeChromeMetrics defaults to a theme-owned token holder). Replace the pill padding in HistoryCards.kt with the existing components.rows.compactPill* tokens already used in HistoryCards.kt:178-179, and the icon-container…

#### Direct MaterialTheme.colorScheme read in the component layer
- **Where:** `ui/screens/home/HomeConnectionButtonState.kt:83, 91-92`  ·  *RDS token discipline*  ·  area: home-history
- **Issue:** `val scheme = MaterialTheme.colorScheme` then `scheme.surface` is used for the connection button container in Disconnected/Error states. RDS bans direct MaterialTheme.colorScheme.* reads in components — color must come from RipDpiThemeTokens.colors so brand identity overrides the system accent. This is the only direct colorScheme read in the four audited screen dirs.
- **Fix:** Add a surface token to RipDpiThemeTokens.colors (e.g. colors.surface or reuse colors.inputBackground/cardBackground) and consume it here; drop the MaterialTheme import.

#### Raw .dp literals in route component layer (outside ui/theme/)
- **Where:** `ui/components/routes/RipDpiRouteComponents.kt:423, 550-554`  ·  *RDS token discipline*  ·  area: onboarding-perms
- **Issue:** RouteStateBadge padding(horizontal = 8.dp, vertical = 2.dp) at line 423; module-level vals RouteGlyphSize=40.dp, RoutePillIconGap=4.dp, RouteConnectorHeight=2.dp, RouteStackNodeMinWidth=48.dp, RouteStackNodeMaxWidth=74.dp (lines 550-554). RDS forbids raw N.dp outside app/.../ui/theme/. These define real layout dimensions, not just preview.
- **Fix:** Move these dimensions into RipDpiThemeTokens (spacing/components/icon-size tokens). The pre-commit grep in rds-spec.md catches inline N.dp in diffs; module-level val N.dp evades the grep but is the same violation. The preview-only 12.dp at line 561 is acceptable (preview scaffold).

#### Raw .dp literals in BiometricPromptScreen and VpnPermissionScreen component layers
- **Where:** `ui/screens/permissions/BiometricPromptScreen.kt:400, 274 (VpnPermissionScreen.kt: 123)`  ·  *RDS token discipline*  ·  area: onboarding-perms
- **Issue:** BiometricPromptPinInput uses Arrangement.spacedBy(8.dp) at line 400. VpnPermissionScreen.kt AuthPromptScaffold top-action uses .padding(horizontal = 12.dp) at line 123, and AuthPromptBadge biometric body uses CornerRadius(4.dp.toPx(), 4.dp.toPx()) at line 274. All in screen/component layer, RDS forbids raw .dp there.
- **Fix:** Replace 8.dp with RipDpiThemeTokens.spacing.sm (or appropriate token), 12.dp with a spacing token, and source the 4.dp corner radius from a shape/spacing token. Canvas-fraction constants (shield/bio/pin fractions) are fine — they are unitless geometry, not Dp.

#### BlockcheckRoute reads MaterialTheme.typography.* directly instead of RipDpi type tokens
- **Where:** `ui/screens/blockcheck/BlockcheckRoute.kt:112,120,129,164,169,201,210,229,281,287,311,320`  ·  *RDS token discipline*  ·  area: protocol-screens
- **Issue:** Pervasive `style = MaterialTheme.typography.titleMedium/bodyMedium/bodyLarge/bodySmall` reads in the component layer. Every sibling screen (StrategyTuner, Health, Subscription, Browser, etc.) uses RipDpiThemeTokens.type.* . The RDS contract requires type to come from RipDpiTheme tokens, not direct MaterialTheme reads; Blockcheck is the lone outlier and the typography read is not caught by the colorScheme-only lint gate.
- **Fix:** Replace all MaterialTheme.typography.* with the matching RipDpiThemeTokens.type.* token (sectionTitle/bodyEmphasis/body/caption) for consistency and theme parity.

#### Direct MaterialTheme.shapes read in component layer (RipDpiDialog)
- **Where:** `ui/components/feedback/RipDpiDialog.kt:113`  ·  *RDS token discipline*  ·  area: components-feedback
- **Issue:** shape = MaterialTheme.shapes.extraLarge — the RDS contract bans direct MaterialTheme.* reads in components; shape must come from RipDpiThemeTokens.shapes.
- **Fix:** Replace with RipDpiThemeTokens.shapes.<token> (e.g. shapes.xl) so the dialog shape is governed by the design-system token tree.

#### Direct MaterialTheme.shapes read in component layer (RipDpiBottomSheet)
- **Where:** `ui/components/feedback/RipDpiBottomSheet.kt:74`  ·  *RDS token discipline*  ·  area: components-feedback
- **Issue:** shape = MaterialTheme.shapes.extraLarge.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize) — direct MaterialTheme.shapes read, banned by the RDS token contract for the component layer.
- **Fix:** Derive the base shape from RipDpiThemeTokens.shapes and apply the bottom-corner override there.

#### Raw .dp literals and hardcoded a11y strings in RipDpiCoachMark (production code)
- **Where:** `ui/components/feedback/RipDpiCoachMark.kt:45,59,133,138,153,174`  ·  *RDS token discipline*  ·  area: components-feedback
- **Issue:** radius: Dp = 48.dp (l45), BubbleGapDp = 24 (l59), (anchor.radius + 8.dp) (l133), Stroke(width = 2.dp.toPx()) (l138), BubbleGapDp.dp (l153), widthIn(max = 280.dp) (l174). Also Color.Black.copy(alpha=0.55f) (l125) instead of colors.scrim, and onClickLabel = "Dismiss" (l122) hardcoded English.
- **Fix:** Move all dp constants to RipDpiSpacing tokens, use RipDpiThemeTokens.colors.scrim instead of Color.Black, and pass onClickLabel via stringResource.

#### Raw dp literals in component layer (RDS token violation) — module-private val pattern
- **Where:** `ui/components/inputs/RipDpiStepper.kt:41-45`  ·  *RDS token discipline*  ·  area: components-inputs
- **Issue:** StepperOuterPadding=2.dp, StepperButtonSize=32.dp, StepperButtonCornerRadius=6.dp, StepperValueMinWidth=48.dp, StepperIconSize=16.dp are raw dp literals declared as private vals. RDS forbids `.dp` literals outside ui/theme/; equivalents exist in RipDpiThemeTokens.spacing (xs=4, sm=8, md=12) and RipDpiIconSizes.
- **Fix:** Route every dimension through RipDpiThemeTokens.spacing / RipDpiIconSizes / a component metrics token. Declaring them as private vals does not exempt them from the RDS literal ban.

#### Raw dp literals in RipDpiConnectionActuator
- **Where:** `ui/components/inputs/RipDpiConnectionActuator.kt:81, 329, 339, 450, 465`  ·  *RDS token discipline*  ·  area: components-inputs
- **Issue:** StageIconSize = 12.dp (81), `.padding(horizontal = 8.dp)` (329), `Spacer(Modifier.width(4.dp))` (339), `.padding(horizontal = 5.dp)` (450), `Spacer(Modifier.width(3.dp))` (465). Also literal alpha multipliers `copy(alpha = 0.38f/0.42f/0.34f)` (367, 377, 445). These are layout/visual constants in the component layer.
- **Fix:** Replace dp literals with RipDpiThemeTokens.spacing tokens (4.dp==xs, 8.dp==sm) and move literal alpha values into the actuator state-style tokens.

#### Raw dp literals in RipDpiSegmentedButton
- **Where:** `ui/components/inputs/RipDpiSegmentedButton.kt:45, 46, 48`  ·  *RDS token discipline*  ·  area: components-inputs
- **Issue:** `.border(width = 1.dp, ...)`, `.padding(2.dp)`, `Arrangement.spacedBy(2.dp)` are raw dp literals; the border should use RipDpiStroke.Thin and spacing should use a token.
- **Fix:** Use RipDpiStroke.Thin for the border width and RipDpiThemeTokens.spacing for padding/spacing.

#### RipDpiNavRail uses raw .dp literals instead of RDS spacing tokens
- **Where:** `ui/navigation/RipDpiNavRail.kt:64, 93, 103, 130`  ·  *RDS token discipline*  ·  area: navigation-shell
- **Issue:** The rail width and brand-badge dimensions are hardcoded: line 64 `.width(80.dp)`, line 93 `.size(40.dp)`, line 103 `Modifier.size(24.dp)`, line 130 `Arrangement.spacedBy(2.dp)`. `import androidx.compose.ui.unit.dp` is present (line 25). The RDS contract (rds-spec.md rule 5 and the token-consumption tests under app/src/test/kotlin/com/poyka/ripdpi/ui/theme/) forbids `.dp` literals outside `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/`. Spacing.kt has no nav-rail width or brand-badge token (RipDpiNavigationMetrics…
- **Fix:** Add nav-rail tokens to RipDpiNavigationMetrics (e.g. railWidth, brandBadgeSize, brandBadgeIconSize, itemLabelGap) and consume them here. The 2.dp item gap should come from a spacing token (RipDpiStroke.Thick is 2.dp, or add a token). This is hook/test-enforced and will redden the RDS token-consumption gate, so it ships as a real…

#### Direct MaterialTheme.colorScheme read in home connection button (bypasses RipDpiTheme)
- **Where:** `ui/screens/home/HomeConnectionButtonState.kt:83, 85, 91`  ·  *RDS token discipline*  ·  area: rds-tokens
- **Issue:** `val scheme = MaterialTheme.colorScheme` is read in a component, then used for `containerColor = scheme.surface` in the Disconnected/Error branches while every other color (foreground, background) comes from `RipDpiThemeTokens.colors`. The RDS rule explicitly forbids direct `MaterialTheme.colorScheme.*` reads in components — they must go through RipDpiTheme. This is the ONLY direct colorScheme read in the entire non-theme Compose tree.
- **Fix:** Replace `scheme.surface` with the equivalent RDS token `colors.card` (RipDpiColors.card maps to LightCard=0xFFFFFFFF / DarkCard=0xFF1A1A1A, which is exactly what MaterialTheme.surface resolves to per Color.kt line 281/314). Remove the `val scheme = MaterialTheme.colorScheme` line and the `scheme` key from the `remember(...)` block. This…

#### VpnConfigScreen clickable rows lack merged semantics / contentDescription present on parallel LocalBypass rows
- **Where:** `ui/screens/config/VpnConfigScreen.kt:328-342`  ·  *Accessibility*  ·  area: config
- **Issue:** VpnActionRow wraps content in Box(Modifier.ripDpiClickable(role = Role.Button, onClick = onClick).ripDpiTestTag(testTag)) with no accessibility label and no semantics merge. The structurally identical LocalBypassActionRow (LocalBypassConfigScreen.kt:318-341) adds .clearAndSetSemantics { contentDescription = accessibilityLabel; role = Role.Button; onClick(...) } and takes an accessibilityLabel param. So the VPN relay/protocol/credentials/DNS rows expose a clickable region whose inner SettingsRow has…
- **Fix:** Give VpnActionRow the same accessibilityLabel parameter + clearAndSetSemantics treatment as LocalBypassActionRow (pass the row title as the label), or factor the two into one shared ActionRow composable to guarantee parity. The two are clearly meant to mirror each other.

#### Hardcoded English a11y contentDescription in RipDpiProgressBar (i18n + TalkBack)
- **Where:** `ui/components/indicators/RipDpiProgressBar.kt:39-42`  ·  *Accessibility*  ·  area: components-feedback
- **Issue:** contentDescription = if (progress != null) "Progress ${(progress * ProgressPercentFactor).toInt()}%" else "Loading" — literal English strings are read verbatim by TalkBack in all 8 locales. The percent value is also not locale-formatted.
- **Fix:** Replace with stringResource(R.string.progress_percent_format, percent) and stringResource(R.string.loading). The sibling StatusIndicator.kt (line 105) and StageProgressIndicator.kt (getQuantityString) already do this correctly — follow that pattern.

#### Hardcoded English 'Loading' contentDescription in RipDpiSpinner
- **Where:** `ui/components/indicators/RipDpiSpinner.kt:46`  ·  *Accessibility*  ·  area: components-feedback
- **Issue:** contentDescription = "Loading" — literal English; the only spoken text for an indeterminate spinner. Not translated for ru/es/de/fr/fa/ar/zh-CN.
- **Fix:** Use stringResource(R.string.loading). Add the key to all 8 locales (lint MissingTranslation=error gate).

#### Hardcoded English + enum-name a11y description in RipDpiHeartbeatIndicator
- **Where:** `ui/components/indicators/RipDpiHeartbeatIndicator.kt:85`  ·  *Accessibility*  ·  area: components-feedback
- **Issue:** contentDescription = "Heartbeat ${state.name.lowercase()}" — both the word 'Heartbeat' and the enum constant name (healthy/degraded/failed/idle) are unlocalized English spoken to TalkBack.
- **Fix:** Map each RipDpiHeartbeatState to a localized stringResource and compose via a format string; never expose enum.name to the accessibility tree.

#### Segmented button segments have no Role and signal selection by color only
- **Where:** `ui/components/inputs/RipDpiSegmentedButton.kt:54-73`  ·  *Accessibility*  ·  area: components-inputs
- **Issue:** Each segment uses `.ripDpiClickable(enabled = true) { onSelect(index) }` with no role and no selected/state semantics. Selection is conveyed only via container/content color (lines 52-53). TalkBack users cannot tell which segment is selected, and the control is announced as a plain button, not a selectable in a group.
- **Fix:** Use ripDpiSelectable(selected = isSelected, role = Role.RadioButton/Tab, ...) per segment and set a stateDescription, or wrap with selectableGroup() and Role.RadioButton so the selected state is exposed.

#### Copy pill in link-preview card uses raw clickable with no role, label, or touch target
- **Where:** `ui/components/cards/RipDpiLinkPreviewCard.kt:155-166`  ·  *Accessibility*  ·  area: components-inputs
- **Issue:** `Surface(... modifier = Modifier.clickable(onClick = onCopy))` wraps the COPY pill. It has no Role.Button, no onClickLabel/contentDescription, and no minimumInteractiveComponentSize — the pill height is only padding-driven (vertical = spacing.xs ~4dp), well under the 48dp touch-target minimum the a11y KB requires.
- **Fix:** Use ripDpiClickable(role = Role.Button, onClickLabel = copyLabel, ...) which applies minimumInteractiveComponentSize() and a role, instead of foundation clickable.

#### AppPickerSheet row is a malformed composite toggle: clickable Row + independently-clickable Checkbox, no merged semantics, no Role
- **Where:** `ui/screens/routes/AppPickerSheet.kt:116-129`  ·  *Accessibility*  ·  area: a11y-i18n
- **Issue:** Row(modifier = ...ripDpiClickable(onClick = onToggle)) wraps Checkbox(checked, onCheckedChange = { onToggle() }) plus label/package Text. The Row clickable has no role (defaults null → announced as generic Button), and the Checkbox is a second independent focusable target. TalkBack sees two stops for one logical control and the checked/unchecked state is not associated with the row. The Material3 Checkbox also bypasses RipDpiTheme tokens.
- **Fix:** Make the Row a single control: Modifier.ripDpiToggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }) with semantics(mergeDescendants = true), and set the Checkbox onCheckedChange = null so it is decorative. This yields one focusable target announced as a checkbox with correct state.

#### Onboarding info page uses banned per-element decorative parallax / rotation / scale
- **Where:** `ui/screens/onboarding/OnboardingScreen.kt:584-626, 87-91, 591`  ·  *UX completeness / flow*  ·  area: onboarding-perms
- **Issue:** OnboardingInfoPageScene drives graphicsLayer{ translationX/translationY/rotationZ/scaleX/scaleY/alpha } off pagerState page offset: illustration travels and rotates (rotationZ = clampedOffset * 2f, scale 0.88->1.0), title and body counter-translate. RDS spec (.claude/rules/rds-spec.md 'Not a decorative system') and docs/design/rds/README.md:408 explicitly ban parallax and hero animations. The only sanctioned parallax is the nav page transition in RipDpiMotion, not per-content-element parallax.
- **Fix:** Remove the per-element parallax/rotation/scale (reduce to a token-driven crossfade via RipDpiMotion) OR add a documented RDS deviation card for the onboarding entrance. Also note rotationZ = ...*2f is a raw motion constant outside RipDpiMotion. Recorded in agent memory (project_onboarding_parallax_rds_deviation.md).

#### composed{} used for core interaction modifiers (recomposition / skipping anti-pattern)
- **Where:** `ui/components/RipDpiInteraction.kt:96, 124, 151`  ·  *Recomposition / performance*  ·  area: components-inputs
- **Issue:** ripDpiClickable, ripDpiSelectable, ripDpiToggleable are all implemented with `composed { ... }`. These modifiers are applied on hot paths (every Button, IconButton, Chip, Card, SettingsRow, SegmentedButton, Stepper). `composed` defeats modifier skipping/reuse, allocates a new modifier instance per composition, and is the legacy pattern the Compose KB (kb://android/develop/ui/compose/custom-modifiers) recommends replacing with Modifier.Node factories.
- **Fix:** Reimplement as Modifier.Node-based factories (Modifier.Element + ModifierNodeElement) per the custom-modifiers KB. This removes per-recomposition allocation across nearly every interactive component in the app.

#### Direct Material3 components bypass RDS text-field component and use default M3 colors
- **Where:** `ui/components/inputs/RipDpiCombobox.kt:47-57`  ·  *Consistency*  ·  area: components-inputs
- **Issue:** RipDpiCombobox uses raw `OutlinedTextField` with no color overrides, so it renders default MaterialTheme.colorScheme colors (indirectly violating the 'no MaterialTheme.colorScheme reads / brand overrides system accent' rule) instead of the branded RipDpiTextField. RipDpiCidrInput.kt:93,102 has the same issue (two raw OutlinedTextFields).
- **Fix:** Replace OutlinedTextField with RipDpiTextField / RipDpiConfigTextField so the field renders branded tokens. If M3 OutlinedTextField must be used, pass OutlinedTextFieldDefaults.colors(...) sourced from RipDpiThemeTokens.

#### QrScannerScreen CameraPreview leaks single-thread executor and never unbinds camera (no DisposableEffect)
- **Where:** `ui/screens/scanner/QrScannerScreen.kt:186-213`  ·  *State management*  ·  area: protocol-screens
- **Issue:** analyzerExecutor = remember { Executors.newSingleThreadExecutor() } (186-190) and the camera binding in LaunchedEffect(previewView) (192-211) are never cleaned up. When CameraPreview leaves composition the executor thread keeps running and the CameraX use-cases stay bound to the lifecycleOwner. android docs (kb://android/develop/ui/compose/side-effects) specify DisposableEffect with onDispose for resources requiring cleanup.
- **Fix:** Wrap acquisition in DisposableEffect(lifecycleOwner) and in onDispose call cameraProvider.unbindAll() and analyzerExecutor.shutdown(). Keep AndroidView for the view itself.

### 🟡 Medium (53)

**Internationalization (i18n)**

- **Shared-result deep-link landing screen is almost entirely hardcoded English** — `ui/screens/diagnostics/share/SharedResultRenderScreen.kt:84, 117, 136, 145, 154, 182, 187, 204, 207-211, 218-232`. User-facing strings are literal English: title "Shared diagnostic" (117), "Result snapshot" (136), snapshotBanner "Shared diagnostic from $origin, $timestamp.… → *Fix:* Move all of these into strings.xml (with the 8-locale parity the project requires) and use stringResource / a @Composable label mapping for the…
- **DPI/diagnostic tool cards ship hardcoded English titles, buttons, and row text** — `ui/screens/diagnostics/DiagnosticsToolsSection.kt:411, 432, 443-445, 472, 489-493, 502-507, 527, 565, 595-599, 622, 636, 645-661, 823, 839-841, 864, 881-902, 951-981`. Card titles are literals: "TCP16 fat-header" (411), "SNI compatibility" (472), "DNS availability" (565), "HTTP compression" (622), "DNS integrity" (823),… → *Fix:* Extract every user-facing literal to strings.xml across all 8 locales and read via stringResource; for the running/idle button label pairs use stringResource…
- **DPI probe-suite, BYOH, whitelist, transport, DNS-rows, and RKN cards hardcode English** — `ui/screens/diagnostics/DiagnosticsDpiProbeSuiteCard.kt:48, 65-67, 72, 159, 174-184; ByohCompatibilityCard 52,70-72,80-83,90,99-101,139; CidrWhitelistCard 37,49,76-79; Ipv4WhitelistCard 44,56,74,86,93,101; PluggableTransportProbeCard 32,62-64; DnsIntegrityRows 26,46,50,70,75; rkn/RknBlockDiagnosisScreen 54-60,74-76,95,111,136; rkn/SelfInfoCard 22-44`. Consistent pattern across the tool-card files: card title literals ("DPI-CH Comprehensive", "Bring-your-own-host compatibility", "CIDR whitelist detection",… → *Fix:* Apply the same string-extraction pass to this whole cluster. For the core enum DpiProbeKind.label(), use a @Composable stringResource mapping (the project's…
- **Scan-progress badges, network-context chips, and resolver card hardcode English** — `ui/screens/diagnostics/DiagnosticsScanProgress.kt:256-257, 272, 297, 526, 551, 561`. DnsBaselineBadge text "DNS: Clean" / "DNS: Tampered (DoH fallback)" (256-257), DpiFailureClassBadge "DPI: ${...}" (272), NetworkContextRow "Validated"/"Not… → *Fix:* Extract to strings.xml; the badge tone-to-text and validated/not-validated pairs map cleanly to stringResource with a when().
- **ReplayFailure step names and error labels rendered in English from the ViewModel** — `ui/screens/diagnostics/ReplayFailureViewModel.kt:74, 127, 137, 145, 216-233`. stepDisplayName() returns "DNS resolve"/"TCP open"/"TLS ClientHello"/"TLS handshake"/"First byte" (216-223) and errorKindLabel() returns "DNS… → *Fix:* Resolve step/error labels to localized strings at the screen layer (the ViewModel should emit a stable key / enum and the @Composable maps it via…
- **Hardcoded dropdown option labels (Altorder / Duplicate / Sequential)** — `ui/screens/settings/AdvancedSettingsContentState.kt:116-127`. rememberFakeOrderOptions() builds RipDpiDropdownOption labels "Altorder 0"/"Altorder 1"/"Altorder 2"/"Altorder 3" and rememberFakeSeqModeOptions() builds… → *Fix:* Move these labels into string-array resources (e.g. R.array.fake_order_modes / _entries and R.array.fake_seq_modes / _entries) and use rememberSettingsOptions…
- **Hardcoded English UI strings bypass the 8-locale MissingTranslation gate (Cloudflare Tunnel fields)** — `ui/screens/config/RelayCloudflareTunnelFields.kt:24,29,37,43,65,72,79,80,86`. Multiple user-facing strings are literal English: Text(text = "Cloudflare Tunnel publishes or consumes an origin...") (24), Text(text = "Tunnel mode") (29),… → *Fix:* Move every literal into app/src/main/res/values/strings.xml with config_relay_cloudflare_* keys and reference via stringResource(...). Add the keys to all 7…
- **Hardcoded English strings in relay-chain trust warnings and validation messages** — `ui/screens/config/RelayChainRelayFields.kt:327-372`. RelayChainTrustWarning and helpers emit raw English: "jurisdiction $it"/"operator $it" (327-329), WarningBanner(title = "Missing trust metadata") (335),… → *Fix:* Replace all literals with stringResource keys (e.g. config_relay_chain_trust_missing_title, config_relay_chain_trust_shared_body with %s placeholders). Avoid…
- **Hardcoded English labels in finalmask header/sudoku/noise/fragment fields** — `ui/screens/config/RelayFinalmaskHeaderFields.kt:21,28,33,48,65`. label = "Header hex" (21), "Trailer hex" (28), "Random range" (33), "Sudoku seed" (48), "Noise range" (65). Companion file RelayFinalmaskFragmentFields.kt has… → *Fix:* Add config_relay_finalmask_header_hex / _trailer_hex / _rand_range / _sudoku_seed / _noise_range / _fragment_packets / _fragment_min_bytes /…
- **Hardcoded chain-block descriptor labels render untranslated for every visual chain step** — `ui/screens/config/ModeEditorChainBlockEditor.kt:312,334,336,373,541-758`. ChainStepDescriptor.label/addLabel/explanation are English literals across ~20 descriptors (e.g. "Split"/"Add split"/"Splits the first payload..." 541-543;… → *Fix:* Convert ChainStepDescriptor to carry @StringRes Int fields (labelRes/addLabelRes/explanationRes) resolved with stringResource at the call sites, and move all…
- **i18n via string concatenation of translated fragments** — `ui/screens/diagnostics/DiagnosticsScanProgress.kt:486-505`. DiagnosticsProfileCard builds the description/badge with buildString { append(description); if (manualOnly) append(" Manual run only."); if (...) append("… → *Fix:* Use full localized format strings (a single stringResource with placeholders) for each variant rather than appending hardcoded English clauses.
- **Hardcoded 'Standby' fallback label in LiveHeroCard** — `ui/screens/diagnostics/DiagnosticsLiveSection.kt:120`. val liveBadgeText = live.networkLabel ?: live.modeLabel ?: "Standby" — the fallback badge text is a hardcoded English literal rendered in the live hero card… → *Fix:* Replace the "Standby" fallback with stringResource(R.string.diagnostics_live_standby) (add the key in all 8 locales).
- **RememberedNetworkPoliciesCard concatenates hardcoded English metric prefixes** — `ui/screens/diagnostics/DiagnosticsOverviewSection.kt:329-335`. Text built as listOfNotNull("Success ${policy.successCount}", "Failures ${policy.failureCount}", policy.lastValidatedLabel?.let { "Validated $it" },… → *Fix:* Use stringResource format strings for each fragment (e.g. R.string.diagnostics_network_success_count with a %d placeholder).
- **User-facing timestamps formatted with hardcoded Locale.US** — `ui/screens/settings/HostPackHelpers.kt:113-122`. hostPackTimestampFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US) is used by formatHostPackGeneratedAt / formatHostPackFetchedAt, whose… → *Fix:* Use Locale.getDefault() (or a localized DateTimeFormatter.ofLocalizedDateTime style) so month abbreviations and ordering follow the user's locale. Apply the…
- **Strategy-pack timestamp formatter pinned to Locale.US** — `ui/screens/settings/StrategyPackHelpers.kt:153-157`. strategyPackTimestampFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US); formatStrategyPackFetchedAt is rendered into… → *Fix:* Switch to Locale.getDefault() or a localized formatter, matching the recommended HostPackHelpers fix so both packs share locale-aware formatting.
- **Byte-size units hardcoded in export success message** — `ui/screens/settings/BackupRestoreScreen.kt:665-670`. formatBytes() returns String.format(Locale.US, "%.1f MB", ...) / "%.1f KB" / "$bytes B". The result is interpolated into the localized backup_export_success… → *Fix:* Use android.text.format.Formatter.formatShortFileSize(context, bytes) (locale- and unit-aware) or move the unit suffixes into string resources and format the…
- **Hardcoded preview labels acceptable but VLESS/MASQUE kindLabel literals duplicate untranslated strings in shipped data path** — `ui/screens/config/RelayCloudflareTunnelFields.kt:37`. Chip labels like "Consume existing"/"Publish local" (37,43) are shown via RelayKindChip(label = ...) — the chip variant that takes a raw String… → *Fix:* Reserve the String-label RelayKindChip overload for true brand/proper-noun tokens (Tor, obfs4, Snowflake) only, and route all descriptive copy (Consume…
- **Filter chip labels are display-capitalized at the UI layer (locale-fragile transform on possibly-translated values)** — `ui/screens/history/HistoryFilters.kt:121`. RipDpiChip(text = option.replaceFirstChar { it.uppercase() }, ...). The filter `option` strings flow straight from uiState.connections.modes / .statuses /… → *Fix:* Decide whether filter options are wire tokens (map each to a stringResource for display) or already-localized (then don't re-case them in the UI). If casing is…
- **Event timestamp/occurrence strings assembled with hardcoded separators at the UI layer** — `ui/screens/history/HistoryCards.kt:183, 191-195`. Text(text = "x$occurrenceCount") hardcodes the 'x' multiplier glyph (not localized; CJK/RTL expectations differ), and timestampText = "${event.source} ·… → *Fix:* Move the multiplier to a quantity/format string (e.g. R.string.history_event_occurrence_format with %d) and express the source/timestamp/range as a single…
- **DetectionResultCards uses raw string-concat for score and ratio display (no locale-aware/number formatting)** — `ui/screens/detection/DetectionResultCards.kt:220,256 (also DetectionHistoryCommunityCards.kt:94; BlockcheckRoute.kt:332)`. "$animatedScore/100" (220), "Probe outcome: $detected/$total signals exposed" (256), Text("${entry.stealthScore}") (94), and Blockcheck "${(result.successRate… → *Fix:* Use stringResource format args with %d/%s placeholders (as StrategyTuner does) so number grouping and the surrounding text are localizable.
- **Number not locale-formatted in RipDpiLiveCounter (visible value + a11y)** — `ui/components/indicators/RipDpiLiveCounter.kt:46-49`. val display = "$animated$suffix"; used both as visible Text and as contentDescription. A value like 42_500_000 renders as '42500000' with no grouping… → *Fix:* Format the integer with NumberFormat.getInstance(Locale) / android.icu before display so grouping respects locale; keep suffix as a stringResource argument.
- **Non-localized enum name used as TalkBack contentDescription in several feedback components** — `ui/components/feedback/RipDpiDialog.kt:188`. RipDpiModalIconBadge sets contentDescription = tone.name (Default/Destructive/Info). Same anti-pattern: RipDpiSnackbar.kt l89 (tone.name), WarningBanner.kt… → *Fix:* Map each tone to a localized stringResource for the icon/badge contentDescription, or set contentDescription = null where the adjacent title already conveys…
- **Locale-unaware uppercase() on user-facing titles and labels** — `ui/components/chrome/RipDpiSectionHeader.kt:36`. `title.uppercase()` (default-locale) on a user-supplied, translatable title. Same pattern in RipDpiScreenChrome.kt:54, RipDpiLinkPreviewCard.kt:123/162/216,… → *Fix:* For visual capitalization prefer a typography/text-transform style token, or call uppercase(Locale.getDefault()) explicitly; avoid uppercasing translated…
- **Unlocalized enum name used as stateDescription in RipDpiConnectionActuator pipeline stage** — `ui/components/inputs/RipDpiConnectionActuator.kt:449`. stateDescription = stage.state.name.lowercase(). The primary actuator (line 175-177) is exemplary (Role.Switch + localized statusDescription + liveRegion), but… → *Fix:* Add a localized label per stage state (stringResource(stage.state.labelRes)) and use it for stateDescription instead of stage.state.name.lowercase().
- **Locale-unsafe percent formatting via manual "%" concatenation and Locale-less format()** — `ui/screens/home/HomeScreen.kt:436`. value = "%.1f%%".format(quality.lossPct) uses String.format with the default locale (decimal separator varies: "4.1" vs "4,1") and a hardcoded percent sign… → *Fix:* Format percentages with NumberFormat.getPercentInstance(Locale.getDefault()) (pass the fraction, e.g. lossPct/100) so the separator and percent-sign placement…

**RDS token discipline**

- **StateMachineScreen uses raw .dp despite KDoc claiming none exist outside ui/theme/** — `ui/screens/diagnostics/StateMachineScreen.kt:88-96 (docstring), 571-572, 629, 719, 722, 726`. The screen KDoc asserts 'no literal colours, dp values outside ui/theme/, or animation-spec constants appear in this file' (lines ~92-94), but NodeCorner =… → *Fix:* Source NodeCorner/DotSize/legend swatch sizes from RipDpiThemeTokens (shape corner + spacing/icon-size tokens) and correct or remove the docstring claim.
- **Raw .dp literals in SummaryCapsule outside ui/theme/** — `ui/screens/settings/AdvancedSettingsComponents.kt:520-521`. SummaryCapsule applies .border(1.dp, border, ...) and .padding(horizontal = 10.dp, vertical = 6.dp) with raw dp literals in the component layer. The RDS… → *Fix:* Replace 1.dp with a RipDpiTheme border-width token, and the 10.dp/6.dp padding with RipDpiThemeTokens.spacing values (e.g. sm/xs) or a dedicated capsule…
- **Intro metrics holder hardcodes .dp tokens outside ui/theme/** — `ui/components/intro/RipDpiIntroScaffoldMetrics.kt:12-33, 47-67`. RipDpiIntroScaffoldMetrics defaults every dimension as raw .dp literals (topActionRowHeight=48.dp, illustrationSize=80.dp, etc.) and the Medium/Expanded… → *Fix:* Either relocate this metrics holder under ui/theme/ (it is functionally a responsive token table) or derive its values from existing…
- **Raw N.dp literals in component layer (RDS token violation)** — `ui/screens/detection/DetectionResultCards.kt:85,86,294,340,375,401,691,703,759 (also DetectionHistoryCommunityCards.kt:92,147,148; StatusVisualIndicator.kt:40; LogsScreen.kt:182; StrategyTunerScreen.kt:176; BlockcheckRoute.kt:105)`. Hardcoded dp literals outside ui/theme/: icon/indicator sizes 10/14/16/18/20/24.dp, strokeWidth 2.dp, heightIn(max=220.dp), LogsScreen… → *Fix:* Add an icon-size scale to RipDpiSpacing/RipDpiIcons (e.g. iconXs/iconSm/iconMd) and a text-field-min-height token, then consume those. The remaining height…
- **Raw .dp literals defined at file scope in RipDpiDiffViewer** — `ui/components/feedback/RipDpiDiffViewer.kt:20-25`. private val diffRowGap = 1.dp; diffUnifiedHorizontalPadding = 6.dp; diffRowVerticalPadding = 2.dp; diffUnifiedColumnGap = 8.dp; diffSideBySideColumnGap = 4.dp;… → *Fix:* Source these from RipDpiSpacing (e.g. spacing.xxs/xs/sm) or add dedicated diff tokens to the theme layer.
- **Raw .dp literals and hardcoded chevron labels in RipDpiJsonTree** — `ui/components/feedback/RipDpiJsonTree.kt:22-24,65,113`. JsonRowVerticalPadding = 2.dp, JsonChevronSize = 14.dp, JsonIndentPerLevel = 16 (-> (depth*16).dp at l65) are raw dp; contentDescription = if (open) "Collapse"… → *Fix:* Move dp values into RipDpiSpacing/icon tokens and use stringResource for the Collapse/Expand chevron descriptions.
- **Raw .dp literals and hardcoded labels in RipDpiAccordion** — `ui/components/feedback/RipDpiAccordion.kt:64,83`. .border(width = 1.dp, ...) (l64) is a raw dp literal (RipDpiStroke.Thin is used elsewhere for exactly this); contentDescription = if (expanded) "Collapse" else… → *Fix:* Use RipDpiStroke.Thin for the border width and stringResource for the chevron contentDescription.
- **Raw .dp literals in RipDpiLogStream production composable** — `ui/components/feedback/RipDpiLogStream.kt:49,73,87`. height: Dp = 240.dp default (l49); Arrangement.spacedBy(2.dp) (l73); horizontalArrangement = Arrangement.spacedBy(8.dp) (l87) — raw dp literals in the… → *Fix:* Default the height from a layout token and source the row gaps from RipDpiSpacing.xxs/xs.
- **Raw .dp literals in RipDpiTooltipRich production composable** — `ui/components/feedback/RipDpiTooltipRich.kt:59,68`. Modifier.padding(8.dp) on the action (l59) and widthIn(max = 320.dp) (l68) are raw dp literals outside ui/theme/. → *Fix:* Use RipDpiThemeTokens.spacing for the action padding and a layout token (or tooltipMaxWidth) for the max width.
- **Raw .dp literals embedded in size enums (Spinner, KbdShortcut, BrandBadge)** — `ui/components/indicators/RipDpiSpinner.kt:26-28`. RipDpiSpinner enum: Small(16.dp,1.5.dp), Standard(24.dp,2.dp), Large(40.dp,3.dp). Same pattern in RipDpiKbdShortcut.kt metricsFor()… → *Fix:* Promote these size scales into RipDpiThemeTokens (e.g. a spinner/kbd/badge size token group) so the dp values live in ui/theme/ per the RDS contract.
- **Raw dp literals across multiple components (border/spacing/indicator)** — `ui/components/cards/PresetCard.kt:136-138`. PresetCard: RadioIndicatorSize=18.dp, RadioIndicatorDotSize=8.dp, RadioIndicatorBorderWidth=2.dp (private vals). Similar raw literals:… → *Fix:* Route all of these through RipDpiThemeTokens.spacing, RipDpiStroke, RipDpiIconSizes, or a component metrics token. The RDS pre-commit grep (rds-spec.md) flags…
- **RipDpiNavRail hardcodes CircleShape/RoundedCornerShape instead of RDS shape tokens** — `ui/navigation/RipDpiNavRail.kt:95, 120`. Line 95 `.background(colors.foreground, CircleShape)` and line 120 `RoundedCornerShape(RipDpiThemeTokens.spacing.md)` build shapes ad hoc.… → *Fix:* Use RipDpiThemeTokens.shapes.* for the selected-item container, and add a brand-badge pill/circle shape token rather than `CircleShape`. Keeps the rail…
- **Raw N.dp spacing literals in screen/component bodies where RipDpiSpacing tokens exist** — `ui/screens/diagnostics/DiagnosticsCards.kt:77, 83, 191, 199, 253, 263, 366 (representative; ~200 similar hits across screens/components)`. DiagnosticsCards.kt already consumes `spacing.sm/xs/md` in 22 places yet still hardcodes `vertical = 8.dp` (=spacing.sm), `Arrangement.spacedBy(2.dp)`,… → *Fix:* Replace literal spacing/padding/gap `N.dp` with the matching `RipDpiThemeTokens.spacing.*` token (4.dp→xs, 8.dp→sm, 12.dp→md, 16.dp→lg, 20.dp→xl, 24.dp→xxl).…
- **Raw N.dp stroke-width literals instead of RipDpiStroke token** — `ui/screens/config/ModeEditorChainBlockEditor.kt:138, 363 (and 1.dp/2.dp borders elsewhere)`. `.border(1.dp, colors.border, shape)` hardcodes the hairline-vs-thin stroke even though `RipDpiStroke` (Spacing.kt:160) defines `Hairline=0.5.dp / Thin=1.dp /… → *Fix:* Replace literal stroke widths with `RipDpiStroke.Thin` (1.dp), `RipDpiStroke.Hairline` (0.5.dp), `RipDpiStroke.Thick` (2.dp). These three constants already…
- **Raw N.dp/N.sp literals in RoundedCornerShape and corner radii instead of shape tokens** — `ui/screens/config/ModeEditorChainBlockEditor.kt:129, 356`. `val shape = RoundedCornerShape(8.dp)` hardcodes a corner radius even though RipDpiShapeMetrics (Spacing.kt:53-65) defines compactCornerRadius=8.dp,… → *Fix:* Use `RipDpiThemeTokens.components.shapes.compactCornerRadius` (8.dp), `.extraSmallCornerRadius` (4.dp), `.mediumCornerRadius` (10.dp) etc. for…

**Accessibility**

- **Color-only state encoding on charts and timelines lacks non-color cues / semantics** — `ui/screens/diagnostics/LatencyGraphScreen.kt:126-202; QualityGraphsScreen 152-187; ThroughputGraphScreen 159-191; HandshakeTimelineScreen 184-222`. The Canvas-drawn line/threshold/loss plots and the handshake Gantt track convey status purely via color (warning threshold, destructive spikes,… → *Fix:* Add a Modifier.semantics { contentDescription = ... } summarizing each chart's key values (already available as p50/p95/now labels), and/or a non-color cue. At…
- **Hand-rolled drag reorder bypasses Compose semantics for accessibility** — `ui/screens/routes/RoutesScreen.kt:171-188`. RuleListRow implements reorder via detectDragGesturesAfterLongPress on the card with offset{}. The drag gesture has no accessibility affordance.… → *Fix:* The up/down buttons keep this from being critical. Consider adding a custom semantics CustomAccessibilityAction for 'move up'/'move down' on the row, or rely…
- **Inert AssistChip/RipDpiChip with empty onClick announced as interactive** — `ui/screens/blockcheck/BlockcheckRoute.kt:324-336 (also StrategyTunerScreen.kt:241-256; XrayProfileImportScreen.kt:215-219)`. AssistChip(onClick = {}) renders a clickable-role badge that does nothing — "best"/percentage badge in blockcheck (324) and tuner (241), and RipDpiChip(onClick… → *Fix:* Use a non-interactive display element (a StatusIndicator / labeled Text / SuggestionChip without click, or a chip variant that sets Role appropriately / clears…
- **Icon contentDescription = null on meaningful narrative/status icons** — `ui/screens/detection/DetectionResultCards.kt:374,400,690`. Warning icon for homeRoutedRoamingNote (374), NarrativeRow status icon (400), and CollapsibleCard category icon (690) all set contentDescription = null. The… → *Fix:* Provide a contentDescription for icons that carry state (warning/exposure tone). Where the icon is purely decorative and the adjacent Text fully conveys…
- **Language row uses raw clickable plus RadioButton (double click target, no selectable role)** — `ui/components/LanguagePickerSheet.kt:77-94`. LanguageRow applies `Modifier.clickable(onClick = onSelected)` to the whole row AND a RadioButton with `onClick = onSelected`. The row is announced as a… → *Fix:* Use Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelected) on the row and pass `onClick = null` to the RadioButton (M3…
- **QR code Image contentDescription defaults to null** — `ui/components/cards/RipDpiQrCodeShareCard.kt:65, 91`. `contentDescription: String? = null` default is passed straight into `Image(contentDescription = contentDescription)`. A QR code is meaningful content (it… → *Fix:* Either require a non-null contentDescription, or default to a stringResource describing the QR (e.g. R.string.a11y_share_qr_code).
- **RipDpiAccordion expand/collapse header lacks Role and stateDescription (expanded state not announced)** — `ui/components/feedback/RipDpiAccordion.kt:71`. .ripDpiClickable(enabled = true) { onExpandedChange(!expanded) } — the header toggles an expanded boolean but passes no role and sets no stateDescription.… → *Fix:* Pass role = Role.Button (or Role.Switch) and add a semantics { stateDescription = if (expanded) expandedLabel else collapsedLabel } using stringResource-backed…
- **Raw .clickable() rows bypass the 48dp minimum touch-target helper and omit Role** — `ui/components/LanguagePickerSheet.kt:81`. LanguageRow uses Modifier.fillMaxWidth().clickable(onClick = onSelected) with raw .padding(16.dp, 12.dp) instead of ripDpiSelectable. The whole row is the… → *Fix:* Replace raw .clickable with ripDpiSelectable(selected, role = Role.RadioButton) (for LanguageRow) or ripDpiClickable(role = Role.Button) for the others, and…

**UX completeness / flow**

- **ipv6Enable toggle has no in-flight / success feedback and uses a stringly-typed setting key** — `ui/screens/dns/DnsSettingsRoute.kt:69-77`. onIpv6Changed calls viewModel.updateSetting(key = "ipv6Enable", value = enabled.toString()) { setIpv6Enable(enabled) }. The persist is fire-and-forget with no… → *Fix:* Reflect persistence state back to the switch (optimistic with rollback on failure) or at least disable it while in-flight, and surface a failure path. Prefer a…
- **Custom DNS save buttons gate on validity but give no inline reason when disabled** — `ui/screens/dns/DnsSettingsCustomResolver.kt:117-125, 207-215, 287-295`. RipDpiButton(text=config_save, enabled = customDotValid && customDotDirty) (and DoH/DNSCrypt equivalents). Field-level errorText only appears once a field… → *Fix:* When the form is invalid-and-dirty, show a short summary hint near the Save button listing the unmet requirement(s), or surface required-field errors on…

**Recomposition / performance**

- **mainUiState hoisted at NavHost root and threaded through the whole graph** — `ui/navigation/RipDpiNavHost.kt:148, 211-213, 229, 352-378`. `val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()` (line 148) is read at the top of RipDpiNavHost, then passed down through… → *Fix:* Either collect mainUiState lazily inside the Settings composable (it already receives mainViewModel via hiltViewModel on the SettingsGraph entry and could read…

**Consistency**

- **Brand-name chip labels hardcoded inline in protocol section model rather than resources** — `ui/screens/config/ModeEditorRelaySection.kt:274,275,285,294,295,296,302`. relayProtocolSections() mixes resourced labels (labelRes = R.string.config_relay_kind_vless) with raw literals: label = "NaiveProxy" (274), "ShadowTLS v3"… → *Fix:* Either route every relay-kind label through a @StringRes (proper nouns can still be resources that are identical across locales, which keeps the translation…

**State management**

- **RoutesScreen optimistic local order can desync from persisted state** — `ui/screens/routes/RoutesScreen.kt:78, 89`. var localRows by remember(state.rows) { mutableStateOf(state.rows) } resets only when state.rows identity changes. move() mutates localRows and fires onReorder… → *Fix:* Acceptable for happy-path, but add a failure path: have reorder() surface success/failure and reset localRows on failure, or key localRows on a derived order…

### ⚪ Low (47)

**Internationalization (i18n)**

- **Hardcoded English fake-payload profile labels shown in UI (untranslated)** — `ui/screens/settings/AdvancedSettingsHelpers.kt:3-30`. formatHttpFakeProfileLabel/formatTlsFakeProfileLabel/formatUdpFakeProfileLabel return literal English strings: "IANA GET", "Cloudflare GET", "Compatibility… → *Fix:* Replace the when-branches with stringResource(...) lookups (these functions would need to become @Composable or take a resolver), or map each profile id to an…
- **Hardcoded labels in Masque mTLS, TUIC, and same_hop validation** — `ui/screens/config/ModeEditorValidation.kt:19`. validationMessage maps "same_hop" -> "Entry and exit must use different profiles." as a raw literal (19) while every sibling branch (invalid_dns_ip, required,… → *Fix:* Add R.string.config_error_same_hop and return stringResource for the same_hop branch, matching the surrounding arms. Ship to all 7 locales.
- **Concatenated translated strings in bottom-sheet candidate subtitle** — `ui/screens/diagnostics/DiagnosticsBottomSheets.kt:261`. RipDpiBottomSheet message = "${candidate.familyLabel} · ${candidate.suiteLabel}" concatenates two (possibly localized) labels with a literal separator. While… → *Fix:* Use a stringResource format (e.g. R.string.diagnostics_candidate_subtitle_format with two %s placeholders).
- **Non-localized number formatting on quality/latency stat labels** — `ui/screens/diagnostics/QualityGraphsScreen.kt:59-67`. nowLabel/p50Label built by string interpolation: "${it.rttP50Ms} ms", "p50 ${it.rttP50Ms} ms", "avg ${it.jitterMs} ms" — units and the 'p50'/'avg' prefixes are… → *Fix:* Use stringResource format strings with %d/%s placeholders for the unit and prefix, and a locale-aware number format; this also covers locales that use a…
- **Concatenated suggestion text bypasses localization grammar** — `ui/screens/settings/RoutingProtectionSection.kt:178`. RoutingProtectionSummaryCard renders Text(text = "${suggestion.title}: ${suggestion.body}") — a hardcoded ": " separator concatenating two strings. While… → *Fix:* Use a string resource template with placeholders, e.g. stringResource(R.string.routing_protection_suggestion_line, suggestion.title, suggestion.body), so the…
- **SSH/Mieru dropdown options display raw untranslated technical values** — `ui/screens/ssh/SshProfileScreen.kt:38 (also MieruProfileScreen.kt:33,37)`. SshAuthTypeOptions.map { RipDpiDropdownOption(value = it, label = it) } shows raw "password"/"private_key" as the visible label; Mieru shows raw "tcp"/"udp"… → *Fix:* If these are meant as user-facing choices, map each to a localized label (R.string) while keeping the wire value separate; if they are deliberately protocol…
- **Non-user-facing clipboard label uses bare literal in Logs copy** — `ui/screens/logs/LogsScreen.kt:239 (metadataChips runtime:/scan:/active at 524-528)`. ClipData.newPlainText("log", ...) uses a hardcoded clip label (239) and metadataChips emits literal "runtime:"/"scan:"/"active" technical chips shown in… → *Fix:* Clip label can stay (not surfaced); for the visible metadata chips, decide if they are intentional technical tokens (document) or localize them. Lowest…

**RDS token discipline**

- **SharedResultRenderScreen reads MaterialTheme.typography directly (RDS-forbidden)** — `ui/screens/diagnostics/share/SharedResultRenderScreen.kt:8, 212-213`. import androidx.compose.material3.MaterialTheme (line 8) then `style = MaterialTheme.typography.bodyMedium` (line 213) in SharedResultError. RDS forbids direct… → *Fix:* Replace MaterialTheme.typography.bodyMedium with the matching RipDpiThemeTokens.type token (e.g. RipDpiThemeTokens.type.body) and drop the MaterialTheme import.
- **PortMatrixScreen: hardcoded English headers plus heavy raw .dp usage** — `ui/screens/diagnostics/PortMatrixScreen.kt:79, 88, 103, 115-120, 133-141, 161, 187`. Raw dp literals throughout the live screen: border 1.dp (79), HorizontalDivider 0.5.dp (88), LegendChip spacedBy(4.dp) + Box size(10.dp) +… → *Fix:* Replace dp literals with RipDpiThemeTokens.spacing/layout tokens (and RipDpiStroke for the divider), and move "Port matrix"/"HOST / PORT" into strings.xml.
- **DiagnosticsCards / DiagnosticsWidgets / DiagnosticsScanProgress use raw .dp literals** — `ui/screens/diagnostics/DiagnosticsCards.kt:77,83,199,254,366,409; DiagnosticsWidgets 58-59,120,174,548; DiagnosticsScanProgress 223-224,264,343-344,379,386,394; DiagnosticsIpv4WhitelistCard 83`. DiagnosticsCards.kt: vertical=8.dp (77), spacedBy(2.dp) (83), PaddingValues(8.dp,2.dp) (199), vertical=8.dp (254), EventBadge PaddingValues(8.dp,4.dp) (366),… → *Fix:* Replace every raw .dp with RipDpiThemeTokens.spacing.* equivalents (xs/sm) and component shape/size tokens; for the named *Dp consts, source the value from a…
- **Raw .dp literals in chain block editor violate RDS no-literal-token floor** — `ui/screens/config/ModeEditorChainBlockEditor.kt:129,138,356,363`. RoundedCornerShape(8.dp) (129 and 356) and .border(1.dp, colors.border, shape) (138 and 363) use literal dp values in the component layer. The RDS contract… → *Fix:* Replace RoundedCornerShape(8.dp) with the corresponding RipDpiTheme shape token and the 1.dp border width with a RipDpiThemeTokens border-width token (the…
- **Raw .dp literal for relay protocol chip height violates RDS token floor** — `ui/screens/config/ModeEditorRelaySection.kt:49`. private val relayProtocolChipHeight = 64.dp is a literal dp constant in the screen layer, consumed at lines 214 and 229 (Spacer height and chip height). RDS… → *Fix:* Source the chip height from a RipDpiThemeTokens.components or layout token (add one if absent) rather than a file-local 64.dp constant, so the visual contract…
- **Raw 1.dp top padding on bullet glyph outside ui/theme/** — `ui/screens/settings/DataTransparencyScreen.kt:131`. BulletItem aligns the U+2022 glyph with Modifier.padding(top = 1.dp) — a raw dp literal in the screen layer, which the RDS token contract disallows outside… → *Fix:* Use a RipDpiThemeTokens.spacing token (or a theme-defined micro-offset) instead of the literal 1.dp; or rely on Alignment to avoid the manual nudge.
- **Raw .dp literals at file scope in RipDpiStaleDataBadge** — `ui/components/indicators/RipDpiStaleDataBadge.kt:33-35`. staleBadgeBorderWidth = 1.dp, staleBadgeVerticalPadding = 3.dp, staleBadgeDotSize = 6.dp — raw dp constants in the component layer (RipDpiStroke.Thin exists… → *Fix:* Use RipDpiStroke.Thin for the border and RipDpiSpacing tokens for padding/dot size.
- **Raw 6.dp segment height literals in StageProgressIndicator and AnalysisProgressIndicator** — `ui/components/indicators/StageProgressIndicator.kt:105`. StageProgressSegments uses .height(6.dp) (l105). AnalysisProgressIndicator.kt defines SegmentHeight = 6.dp and SegmentGap = 4.dp at file scope (l51-52). Both… → *Fix:* Add a progress-segment height/gap token to the theme layer and reference it from both indicators for visual consistency.
- **Unused MaterialTheme import in component layer** — `ui/components/inputs/RipDpiDropdown.kt:21`. `import androidx.compose.material3.MaterialTheme` is present but MaterialTheme is never referenced in the file body. Same dangling import in… → *Fix:* Remove the unused MaterialTheme imports.
- **Chip border uses raw 1.dp instead of RipDpiStroke token** — `ui/components/inputs/RipDpiChip.kt:124`. `.border(1.dp, animatedBorderColor, chipShape)` uses a literal stroke width where RipDpiStroke.Thin (used elsewhere, e.g. ConnectionActuator) is the token. → *Fix:* Use RipDpiStroke.Thin for the chip border width.
- **RipDpiNavRail references Color via fully-qualified inline path** — `ui/navigation/RipDpiNavRail.kt:115`. Line 115 `val container = if (selected) colors.accent else androidx.compose.ui.graphics.Color.Transparent`. This is not a `Color(0x........)` literal (which… → *Fix:* Expose a transparent/neutral surface token (or use Color.Transparent via a top-level import). Minor — fold into the nav-rail tokenization pass above.
- **Local private val Dp metric constants defined in component/screen files instead of theme tokens** — `ui/screens/diagnostics/DiagnosticsWidgets.kt:58, 59`. File-private dp constants live outside ui/theme/: DiagnosticsWidgets.kt `SparklineChartHeight = 84.dp` / `SparklineChipWidth = 64.dp`;… → *Fix:* These are the gray area: they ARE named (better than inline literals) but still sit in the component layer. Where the value is component-specific (sparkline…
- **Glance widget theme duplicates raw hex values rather than importing from a shared color source** — `widget/theme/RipDpiGlanceColors.kt:7-28`. All 20 Glance ColorProvider values are hand-copied hex literals (LightPrimary=0xFF1A1A1A, LightBackground=0xFFFAFAFA, LightSurface=0xFFFFFFFF,… → *Fix:* Glance cannot consume Compose CompositionLocals, so literal duplication is the accepted pattern — but move the source-of-truth hex constants into a single…

**Accessibility**

- **Chevron toggle has hardcoded English contentDescription** — `ui/screens/diagnostics/DiagnosticsWidgets.kt:127-132`. CollapsibleSection's chevron Icon sets contentDescription = if (expanded) "Collapse" else "Expand" as raw English string literals — yet the same composable… → *Fix:* Set the Icon contentDescription = null (the row already carries a Role.Button + stateDescription + onClickLabel, making the icon decorative) or reuse the…
- **Interactive sparkline tap target may fall below the 48dp minimum** — `ui/screens/diagnostics/DiagnosticsWidgets.kt:58, 348-372`. SparklineCanvas attaches detectTapGestures to a Canvas whose height is SparklineChartHeight = 84.dp but individual selectable data points are ~ (width /… → *Fix:* This is a known trade-off for dense charts; consider snapping taps to nearest point (already done via roundToInt) plus a larger hit-slop, and document the…
- **Bullet glyph announced by TalkBack as a separate node** — `ui/screens/settings/DataTransparencyScreen.kt:122-139`. BulletItem renders the decorative U+2022 bullet as its own Text node alongside the content Text in a Row, with no semantics merge or decorative clearing.… → *Fix:* Wrap the Row in Modifier.semantics(mergeDescendants = true){} (or clearAndSetSemantics on the bullet Text) so TalkBack reads only the item content as a single…
- **Connection actuator status updates announced, but the three AnimatedContent text swaps are not text-scaling/RTL audited and lack their own semantics** — `ui/screens/home/HomeConnectionButtonLayout.kt:98-101, 141-171`. The button is a fixed-size circle (homeChrome.connectionButtonSize 172-196.dp) with fixed horizontal/vertical padding and three stacked AnimatedContent Text… → *Fix:* Include modeLabel in the merged contentDescription (or expose it via stateDescription), and verify the fixed-size circle tolerates fontScale 1.3-2.0 (add…
- **Stage/confidence status iconography paired with color but status text already carries meaning — verify not color-only** — `ui/screens/home/HomeAnalysisBottomSheets.kt:354-381`. StageResultRow chooses statusIcon (Error/Warning/Check) AND statusColor, with the Icon given contentDescription = null (line 378). The shape distinction (icon… → *Fix:* Optionally give the status icon a contentDescription (e.g. 'Failed'/'Passed'/'Skipped') so the severity is announced independent of the summary copy. Low —…
- **RouteStateBadge color-only state signaling for non-active badges** — `ui/components/routes/RipDpiRouteComponents.kt:414-424, 250-255`. RouteStateBadge differentiates states by badge container/border/content color from the route state style; the text label does carry the state name (good), so… → *Fix:* No change strictly required since text labels and the composite contentDescription provide non-color cues. If the i18n fix for label() lands, ensure the badge…
- **CircularProgressIndicator / LinearProgressIndicator without progress semantics label** — `ui/screens/browser/OwnedStackBrowserScreen.kt:158 (also health/ConnectionHealthScreen.kt:136; DetectionResultCards.kt:84,94)`. Bare CircularProgressIndicator()/LinearProgressIndicator(...) with no contentDescription/stateDescription. Loading and rate-progress indicators are not… → *Fix:* Add a semantics { contentDescription = stringResource(...) } (or stateDescription for determinate progress) so TalkBack announces loading / the success-rate…
- **Color-only differentiation of stale tiers in RipDpiStaleDataBadge** — `ui/components/indicators/RipDpiStaleDataBadge.kt:77-98`. Tiers Fresh/Recent/Aging/Stale/Expired are distinguished visually only by container/dot color (and a pulse on Fresh); the caller-supplied label ('18 m ago')… → *Fix:* This is mitigated for TalkBack by the description, but for low-vision sighted users consider a tier glyph or text marker in addition to color…
- **NavRail and BottomNav meet 48dp touch targets and Tab semantics (no a11y defect)** — `ui/components/RipDpiInteraction.kt:128`. Verified against android docs (kb://android/develop/ui/compose/accessibility/api-defaults — 48dp minimum touch target). Both BottomNavItem (BottomNavBar.kt… → *Fix:* No action. Retain the minimumInteractiveComponentSize() wrapper; do not bypass ripDpiSelectable for nav items.
- **ripDpiClickable call sites omit Role (announced as generic, not Button)** — `ui/components/feedback/WarningBanner.kt:77`. surfaceModifier.ripDpiClickable(onClick = it) with no role. Same omission at RipDpiStepper.kt:121, RipDpiSegmentedButton.kt:60, RipDpiJsonTree.kt:109. role… → *Fix:* Add role = Role.Button (or Role.RadioButton for the segmented button segments / Role.Tab where appropriate) to each of these ripDpiClickable calls.
- **Hardcoded "$timestamp <TYPE> $message ..." composite a11y string mixes uppercased type token into spoken text** — `ui/components/indicators/LogRow.kt:52-53`. semantics(mergeDescendants = true) { contentDescription = "$timestamp ${type.uppercase()} $message ${metadataChips.joinToString(" ")}" }. The mergeDescendants… → *Fix:* Lower priority since this is diagnostic log data, but consider mapping the log type to a localized label and using a parameterized resource for the spoken…
- **Hardcoded numeric Text without explicit semantics in DetectionHistoryCommunityCards** — `ui/screens/detection/DetectionHistoryCommunityCards.kt:92-94`. An Icon conveys score direction (KeyboardArrowDown/Remove) with a desc that can be null (line 89 else-branch passes null), and the bare stealthScore number is… → *Fix:* Provide a non-null contentDescription for the trend icon in all branches (including 'unchanged'), and give the score Text a semantics contentDescription like…

**UX completeness / flow**

- **Secret/password relay fields rendered as plain text without PasswordVisualTransformation** — `ui/screens/config/RelayShadowTlsFields.kt:18-22`. relayShadowTlsPassword (ShadowTlsFields 18-22) is a plain RipDpiTextField with no PasswordVisualTransformation/keyboardType. Same for relayTuicPassword… → *Fix:* Apply the same RipDpiTextFieldBehavior(keyboardType = Password, visualTransformation = PasswordVisualTransformation()) used in NaiveProxy to all secret-bearing…
- **Destructive-ish 'Disable' connection action fires immediately with no confirmation** — `ui/screens/home/HomeModeCard.kt:179-188`. The primary action button toggles enable/disable directly (onPrimaryAction -> onVpnToggle(!isActive) in HomeScreen.kt:388-394). Disabling an active VPN/bypass… → *Fix:* Acceptable as-is for a primary connect toggle; if product wants a guardrail, add a confirm only on disabling an active VPN while a kill-switch/lockdown is…
- **OnboardingDnsSelectionContent may show raw provider id if catalog lookup fails** — `ui/screens/onboarding/OnboardingSetupPages.kt:133`. title = dnsProviderById(option.providerId)?.displayName ?: option.providerId. If a curated providerId ever fails to resolve in the data layer, the UI falls… → *Fix:* Provider ids are test-pinned so the fallback is unlikely to fire, but prefer a stringResource fallback (R.string.dns_unknown_provider) over leaking the…
- **animateScrollToItem in LaunchedEffect can fight user scroll in RipDpiLogStream** — `ui/components/feedback/RipDpiLogStream.kt:60-64`. LaunchedEffect(filtered.size, autoScroll) always animateScrollToItem(lastIndex) when autoScroll is true on every size change. If the user scrolls up to read an… → *Fix:* Gate auto-scroll on the user already being near the bottom (e.g. check state.layoutInfo / a derivedStateOf isAtBottom) before jumping, a common log-tail UX…

**Recomposition / performance**

- **Dead surfaceStyle computation in ConfigImportMenu does work in composition with no effect on most of the menu** — `ui/screens/config/ConfigImportMenu.kt:58`. val surfaceStyle = RipDpiThemeTokens.surfaces.resolve(RipDpiThemeTokens.surfaceRoles.inputs.dropdownMenu) is computed unconditionally on every recomposition of… → *Fix:* Move the surfaceStyle resolve inside the DropdownMenu content lambda (or wrap in remember) so it is only computed when the menu is open. Low impact since…
- **rememberHomeChromeMetrics() recomputed per child instead of hoisted, called twice in the button content path** — `ui/screens/home/HomeConnectionButtonLayout.kt:59, 137`. HomeConnectionButtonLayout reads homeChrome = rememberHomeChromeMetrics() at line 59, and HomeConnectionButtonContent calls rememberHomeChromeMetrics() again… → *Fix:* Pass connectionIconSize (or the homeChrome instance) into HomeConnectionButtonContent rather than calling rememberHomeChromeMetrics() a second time inside it.…
- **AnimatedContent on free-text label/modeLabel keyed by the raw String triggers transitions on any content change** — `ui/screens/home/HomeConnectionButtonLayout.kt:141-171`. AnimatedContent(targetState = label) and (targetState = modeLabel) animate a fade whenever the string value changes. For modeLabel (e.g. profile name /… → *Fix:* If only connection-state transitions should animate, key the AnimatedContent on a derived stable state token (the ConnectionState) and read the latest label…
- **Two LaunchedEffects on pager <-> uiState risk ping-pong / redundant scrolls** — `ui/screens/onboarding/OnboardingScreen.kt:212-223`. LaunchedEffect(uiState.currentPage) animates the pager to the target page; LaunchedEffect(pagerState.settledPage) calls actions.onPageChanged when settledPage… → *Fix:* Pattern is the standard two-way pager sync and the equality guards make it safe. Optionally collapse to a single snapshotFlow on settledPage for the VM…
- **Synchronous QR matrix encode runs in composition** — `ui/screens/proxyimport/ProfileShareDialog.kt:132 (also ProfileShareScreen.kt:125)`. qrBitmap = remember(shareUri) { renderQrBitmap(shareUri) } encodes a 512px QR matrix + allocates an IntArray + Bitmap synchronously during composition.… → *Fix:* Acceptable for a one-shot dialog; if the encode ever shows jank, move it to produceState/LaunchedEffect off the main thread. No change required unless…

**Consistency**

- **BiometricPromptPinInput double-wraps padding already applied by caller** — `ui/screens/permissions/BiometricPromptScreen.kt:288, 395-400`. BiometricPromptScreen passes horizontalPadding=introLayout.bodyHorizontalPadding into BiometricPromptPinInput, which applies it again as Column padding. The… → *Fix:* Verify the PIN field aligns with the body text horizontally; consolidate the padding source so the field and the body share one token. Minor visual-consistency…
- **RipDpiNavHost declares required mainViewModel parameter after the optional modifier** — `ui/navigation/RipDpiNavHost.kt:124-131`. `fun RipDpiNavHost(modifier: Modifier = Modifier, startDestination: Route = Route.Home, mainViewModel: MainViewModel, actions: ... , ...)`. The required… → *Fix:* Reorder to `RipDpiNavHost(mainViewModel: MainViewModel, modifier: Modifier = Modifier, startDestination: Route = Route.Home, ...)` to match the slot-table…
- **Unused MaterialTheme import in RipDpiNavHost** — `ui/navigation/RipDpiNavHost.kt:7`. `import androidx.compose.material3.MaterialTheme` (line 7) is never referenced in the file (grep for `MaterialTheme` returns only the import; the file colors… → *Fix:* Remove the unused import. ktlint/detekt should already flag this; confirm it is not baseline-suppressed.

**State management**

- **SharedFlow effects collected via LaunchedEffect(Unit) are not lifecycle-aware** — `ui/screens/settings/BackupRestoreScreen.kt:354,401,570,626`. The four effect handlers (BackupRestoreEffectHandler, BackupResetEffectHandler, BackupShareEffectHandler, BackupExportEffectHandler) collect their SharedFlow… → *Fix:* Optional: key the LaunchedEffect on the flow instance (LaunchedEffect(flow)) and/or wrap collection in repeatOnLifecycle(STARTED) for parity with the…
- **diagnosticsInitialSection uses mutableStateOf rather than rememberSaveable** — `ui/navigation/RipDpiNavHost.kt:133`. `val diagnosticsInitialSection = remember { mutableStateOf<DiagnosticsSection?>(null) }`. This is a navigation-intent flag held in composition; on… → *Fix:* If DiagnosticsSection is Parcelable/serializable, use rememberSaveable; otherwise confirm the intent is always re-delivered by the shell on recreation (it…

**Material 3 / consistency**

- **Combobox/FilterBar use literal alpha-free but border/shape from spacing token misuse** — `ui/components/inputs/RipDpiCombobox.kt:64`. `RoundedCornerShape(RipDpiThemeTokens.spacing.sm)` and FilterBar.kt:50 `RoundedCornerShape(RipDpiThemeTokens.spacing.sm)` derive a corner radius from a… → *Fix:* Use RipDpiThemeTokens.shapes.* for corner radii so the shape scale stays consistent across components.

## Cross-cutting themes

- **i18n is not enforceable by lint when text is hardcoded in Kotlin.** The recurring anti-patterns are (a) `Text("literal")`/`contentDescription="literal"`, (b) `buildString { append(localized); append(" hardcoded clause") }` which breaks grammar/RTL, and (c) `DateTimeFormatter.ofPattern(..., Locale.US)` and `"$n ms"` interpolation. Fix pattern: full format-string resources with placeholders + `Locale.getDefault()`; mirror the existing `DiagnosticsHealth.displayLabel()` @Composable enum-label pattern. Every new key must land in all 8 locales in the same commit.
- **RDS token discipline drifts at the edges.** Leaks cluster in newer/secondary screens (share, blockcheck, detection) and a few components. Note: the RDS pre-commit grep + token-consumption tests only cover `Color(0x..)`/`.dp`/`tween`/`spring`/`colorScheme` — `MaterialTheme.typography` reads slip through the gate, so they accumulate silently. Consider extending the lint grep to typography.
- **Charts/indicators rely on color-only state.** Canvas plots (latency/quality/throughput/handshake) and several badges/indicators have no text/semantics alternative — inaccessible to TalkBack and color-blind users. `PortMatrixScreen` already pairs color with a glyph + per-cell `contentDescription`; adopt that pattern everywhere.
- **Self-attesting KDoc can lie.** At least one screen's KDoc claims "no dp/color literals outside ui/theme" while containing them — false attestations are a maintenance hazard; prefer the test/grep gate over prose claims.

## Recommended remediation order

1. **Triage & confirm the 5 Critical + 43 High items** (most are unverified due to the rate-limited verify phase). Re-run verification or spot-check by file; the report flags ✓ for the 19 already confirmed.
2. **i18n sweep, screen-by-screen, highest-traffic first** (home → diagnostics → settings → config). Extract literals to `strings.xml` + 7 locale files in the same commit; convert concatenations to format resources; switch `Locale.US` formatters to `Locale.getDefault()`. This clears the largest bucket and removes latent `MissingTranslation` risk. Note the translatable-keys manifest gate (`config/i18n/translatable-keys.txt`) must be regenerated.
3. **RDS token leaks** — batch the `.dp`/`.sp`/`typography`/`colorScheme` replacements per file; correct/remove false token-purity KDoc. Extend the RDS grep gate to catch `MaterialTheme.typography` so this doesn't regress.
4. **Accessibility** — add `Modifier.semantics { contentDescription = … }` summaries to Canvas charts, add missing icon descriptions, and audit touch targets to ≥48dp. Adopt the `PortMatrix` color+glyph pattern for status surfaces.
5. **Lower-frequency lenses** (recomposition, state, ux-flow, consistency) — fold into the per-file passes above where co-located; otherwise a final cleanup pass.

Batch by file (not by lens) so each touched file is fixed once across all its findings. Do the work on a feature branch / worktree, not on `main`.

## Coverage

Audited areas (findings per area): components-feedback 21 · components-inputs 19 · diagnostics 17 · protocol-screens 15 · config 13 · home-history 13 · onboarding-perms 13 · a11y-i18n 13 · settings 10 · navigation-shell 8 · rds-tokens 6.

Top files by finding count:

- 4 — `ui/screens/config/ModeEditorChainBlockEditor.kt`
- 4 — `ui/screens/detection/DetectionResultCards.kt`
- 4 — `ui/components/indicators/RipDpiStaleDataBadge.kt`
- 4 — `ui/navigation/RipDpiNavHost.kt`
- 3 — `ui/screens/diagnostics/DiagnosticsWidgets.kt`
- 3 — `ui/components/routes/RipDpiRouteComponents.kt`
- 3 — `ui/components/indicators/RipDpiSpinner.kt`
- 3 — `ui/components/inputs/RipDpiConnectionActuator.kt`
- 3 — `ui/navigation/RipDpiNavRail.kt`
- 3 — `ui/screens/home/HomeConnectionButtonLayout.kt`
- 2 — `ui/screens/diagnostics/share/SharedResultRenderScreen.kt`
- 2 — `ui/screens/diagnostics/DiagnosticsScanProgress.kt`

---
*Generated from a multi-agent audit workflow (`compose-uiux-audit`, run `wf_918cfcf1-c17`). Raw findings JSON retained in the workflow task output.*
