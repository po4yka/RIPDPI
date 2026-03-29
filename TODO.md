# Diagnostics Screen Redesign -- Task Checklist

Each task is a single PR-sized unit of work. Tasks within a phase are ordered by
dependency: complete them top-to-bottom. Phases are sequential (Phase 2 depends
on Phase 1, etc.).

File path prefix: `app/src/main/kotlin/com/poyka/ripdpi/`

---

## Phase 1: Quick Wins -- Density and Copy Cleanup

- [x] **1.1 Hide debug performance card behind developer gesture**
  - Files: `ui/screens/diagnostics/DiagnosticsScreen.kt`
  - Replace `BuildConfig.DEBUG` guard on `DiagnosticsPerformanceCard` with a
    `rememberSaveable` boolean toggled via long-press on `RipDpiTopAppBar`.
    Debug card renders only when toggled on.
  - ~30 lines

- [x] **1.2 Replace developer-facing redaction strings**
  - Files: `activities/DiagnosticsUiCoreSupport.kt`, `res/values/strings.xml`,
    `res/values-ru/strings.xml`
  - Change `redactValue()` output from `"redacted"` to a string resource
    ("Hidden" / "Скрыто"). Change `redactCollection()` from `"redacted(N)"` to
    "Hidden (N items)" / "Скрыто (N)".
  - ~20 lines

- [x] **1.3 Tighten vertical spacing in probe result and session rows**
  - Files: `ui/screens/diagnostics/DiagnosticsCards.kt`
  - Reduce `RipDpiCard` internal padding in `ProbeResultRow` and `SessionRow`.
    Remove always-visible detail sub-rows from `ProbeResultRow` inline view
    (keep them for bottom sheet detail).
  - ~40 lines

- [x] **1.4 Differentiate profile card descriptions by family**
  - Files: `ui/screens/diagnostics/DiagnosticsScanSection.kt`,
    `res/values/strings.xml`, `res/values-ru/strings.xml`
  - Write distinct 1-line descriptions for each `DiagnosticProfileFamily`:
    GENERAL ("Quick DNS, HTTP, HTTPS, and TCP connectivity check"),
    DPI_FULL ("Deep packet inspection detection across multiple services"),
    AUTOMATIC_PROBING ("Background strategy trials that produce a recommendation"),
    AUTOMATIC_AUDIT ("Full strategy matrix audit recording pass/fail/partial"),
    WEB_CONNECTIVITY, MESSAGING, CIRCUMVENTION, THROTTLING (each unique).
  - ~30 lines

- [x] **1.5 Suppress "Unknown" fields in Overview cards**
  - Files: `ui/screens/diagnostics/DiagnosticsCards.kt`,
    `activities/DiagnosticsUiContextSupport.kt`
  - In `SnapshotCard` and `ContextGroupCard`, skip rendering fields where value
    equals "Unknown" or is blank. In builder functions, filter before emitting
    UI models.
  - ~25 lines

---

## Phase 2: Scan Tab Restructure

- [x] **2.1 Extract profile picker into standalone composable**
  - Files: `ui/screens/diagnostics/DiagnosticsScanSection.kt`
  - Move the content of `ScanProfilePickerCard` (profile list grouped by family,
    radio selection, action buttons) into a new `ProfilePickerContent` composable
    that accepts the same parameters but has no card wrapper. This is a pure
    refactor with no behavior change.
  - Depends on: Phase 1 complete
  - ~60 lines

- [x] **2.2 Add ProfileSelectionBottomSheet**
  - Files: `ui/screens/diagnostics/DiagnosticsBottomSheets.kt`,
    `activities/DiagnosticsUiModels.kt`, `activities/DiagnosticsViewModel.kt`
  - Create `ProfileSelectionBottomSheet` wrapping `ProfilePickerContent` in a
    `RipDpiBottomSheet`. Add `showProfilePicker: Boolean` to
    `DiagnosticsScanUiModel`. Add `toggleProfilePicker()` and
    `dismissProfilePicker()` to ViewModel.
  - Depends on: 2.1
  - ~120 lines

- [x] **2.3 Replace inline profile picker with compact selected-profile row**
  - Files: `ui/screens/diagnostics/DiagnosticsScanSection.kt`
  - Replace the `ScanProfilePickerCard` item in `ScanSection`'s `LazyColumn`
    with a compact single row: selected profile name + family badge + "Change"
    outlined button. Button triggers `showProfilePicker = true`.
  - Depends on: 2.2
  - ~80 lines

- [x] **2.4 Reorder Scan tab LazyColumn -- diagnosis summary first**
  - Files: `ui/screens/diagnostics/DiagnosticsScanSection.kt`
  - Reorder `LazyColumn` items to: (1) diagnosis summary card (if present),
    (2) compact profile row, (3) scan workflow card, (4) progress indicator,
    (5) latest results, (6) probe results. Currently diagnosis is positioned
    after the profile picker.
  - Depends on: 2.3
  - ~30 lines

- [x] **2.5 Wire profile picker sheet into DiagnosticsScreen**
  - Files: `ui/screens/diagnostics/DiagnosticsScreen.kt`
  - Add `ProfileSelectionBottomSheet` to `DiagnosticsBottomSheetHost` or render
    alongside existing bottom sheets at the `DiagnosticsScreen` level. Pass
    ViewModel state and callbacks.
  - Depends on: 2.2, 2.3
  - ~40 lines

---

## Phase 3: Overview Tab Progressive Disclosure

- [x] **3.1 Create CollapsibleSection composable**
  - Files: `ui/screens/diagnostics/DiagnosticsCards.kt` (or new file
    `DiagnosticsCollapsibleSection.kt`)
  - Reusable composable: `CollapsibleSection(title, badgeCount, expanded,
    onToggle, content)`. Renders a header row with title, optional count badge,
    and chevron icon. Content visibility animated with `AnimatedVisibility`
    (200ms ease-in-out).
  - Depends on: Phase 1 complete
  - ~60 lines

- [ ] **3.2 Wrap snapshot + context in "Network Details" section**
  - Files: `ui/screens/diagnostics/DiagnosticsScreen.kt`
  - In `OverviewSection`, wrap `SnapshotCard` and `ContextGroupCard` inside
    `CollapsibleSection(title = "Network Details", badgeCount = fieldCount)`.
    Default state: collapsed. Store expanded state in `rememberSaveable`.
  - Depends on: 3.1
  - ~40 lines

- [ ] **3.3 Wrap recent activity in collapsible section**
  - Files: `ui/screens/diagnostics/DiagnosticsScreen.kt`
  - Group latest session row, automatic probe history callout, and history
    callout card into `CollapsibleSection(title = "Recent Activity")`.
    Default state: expanded.
  - Depends on: 3.1
  - ~40 lines

- [ ] **3.4 Filter unknown/empty fields from snapshot and context models**
  - Files: `activities/DiagnosticsUiContextSupport.kt`,
    `activities/DiagnosticsUiSectionBuilders.kt`
  - In snapshot/context builder functions, filter out fields where value is
    "Unknown", "unknown", or blank before emitting UI models. This reduces
    the field count visible in Network Details.
  - Depends on: none (can be done in parallel with 3.1)
  - ~50 lines

- [ ] **3.5 Wrap remembered networks in collapsible section**
  - Files: `ui/screens/diagnostics/DiagnosticsScreen.kt`
  - Wrap `RememberedNetworkPoliciesCard` in `CollapsibleSection` with count
    badge showing number of remembered networks. Default: collapsed.
  - Depends on: 3.1
  - ~20 lines

---

## Phase 4: Results Display Overhaul

- [ ] **4.1 Create CompactProbeRow composable**
  - Files: `ui/screens/diagnostics/DiagnosticsCards.kt`
  - New composable rendering probe target + outcome in a single 52dp-height
    row without card wrapping. Layout: leading status indicator (shape + color),
    target name (weight 1f), outcome label (trailing). Tappable with ripple.
    Details shown via bottom sheet on tap.
  - Depends on: Phase 2 complete
  - ~50 lines

- [ ] **4.2 Unify statusTone() mapping**
  - Files: `ui/screens/diagnostics/DiagnosticsTonePalette.kt`
  - Extract all scattered `statusTone(tone: DiagnosticsTone)` mappings into a
    single canonical function in `DiagnosticsTonePalette.kt`. Standardize the
    5-level mapping: healthy -> OK, attention -> Degraded, critical -> Blocked,
    neutral -> Inconclusive, running -> Running.
  - Depends on: none (can be done in parallel with 4.1)
  - ~40 lines

- [ ] **4.3 Replace ProbeResultRow with CompactProbeRow in Scan tab**
  - Files: `ui/screens/diagnostics/DiagnosticsScanSection.kt`
  - Replace `ProbeResultRow` items in the results section with
    `CompactProbeRow`. Wrap all probe results in a single containing card
    with a section header ("Probe Results (N)").
  - Depends on: 4.1, 4.2
  - ~60 lines

- [ ] **4.4 Enhance probe detail bottom sheet**
  - Files: `ui/screens/diagnostics/DiagnosticsBottomSheets.kt`
  - Expand the existing probe detail sheet to display all `probe.details`
    fields, retry count, timing data, and raw evidence. Add a "Copy raw JSON"
    action button.
  - Depends on: 4.1
  - ~50 lines

- [ ] **4.5 Add shape tokens to status indicators**
  - Files: `ui/screens/diagnostics/DiagnosticsTonePalette.kt`,
    status indicator component (design system)
  - Augment color-only status dots with distinct shapes:
    OK = filled circle, Degraded = filled triangle, Blocked = filled square,
    Inconclusive = open diamond, Running = pulsing circle.
    This addresses accessibility for colorblind users.
  - Depends on: 4.2
  - ~80 lines

---

## Phase 5: Navigation and Information Architecture Rethink

- [ ] **5.1 Design new 3-tab section enum and UI models**
  - Files: `activities/DiagnosticsUiModels.kt`
  - Replace `DiagnosticsSection { Overview, Scan, Live, Approaches, Share }`
    with `DiagnosticsSection { Dashboard, Scan, Tools }`. Create
    `DiagnosticsDashboardUiModel` merging overview + live fields. Create
    `DiagnosticsToolsUiModel` merging approaches + share fields.
  - Depends on: Phase 4 complete
  - ~100 lines

- [ ] **5.2 Merge Live metrics into Dashboard section**
  - Files: `ui/screens/diagnostics/DiagnosticsScreen.kt`,
    `ui/screens/diagnostics/DiagnosticsLiveSection.kt`,
    `activities/DiagnosticsUiStateFactory.kt`
  - Extract reusable live widgets (metrics row, sparkline) from
    `LiveSectionContent`. Render them conditionally in Dashboard when VPN
    is active. Merge `buildLiveState` into `buildDashboardState`.
  - Depends on: 5.1
  - ~150 lines

- [ ] **5.3 Create Tools section**
  - Files: `ui/screens/diagnostics/DiagnosticsScreen.kt`
  - New `ToolsSection` composable rendering: (1) Approaches browser with
    existing Profiles/Strategies mode toggle, (2) Share/Export controls
    with existing action buttons.
  - Depends on: 5.1
  - ~100 lines

- [ ] **5.4 Update section switcher and pager for 3 tabs**
  - Files: `ui/screens/diagnostics/DiagnosticsScreen.kt`
  - Update `rememberPagerState` page count from 5 to 3. Update
    `DiagnosticsSectionSwitcher` to render 3 chips (Dashboard, Scan, Tools).
    Update `when` block in `HorizontalPager` to route to correct sections.
  - Depends on: 5.1, 5.2, 5.3
  - ~60 lines

- [ ] **5.5 Update ViewModel navigation and tests**
  - Files: `activities/DiagnosticsViewModel.kt`,
    `test/.../DiagnosticsViewModelTest.kt`
  - Update `selectSection`, `SelectionState`, section-related actions, and
    all test assertions referencing the old 5-tab enum. This is the largest
    single task due to ~3,200 lines of ViewModel tests.
  - Develop Phase 5 on a dedicated branch; merge after all tests pass.
  - Depends on: 5.4
  - ~200 lines
