# Diagnostics Screen Redesign Roadmap

## Motivation

The Diagnostics screen is the primary interface for users to understand their network
status, run connectivity scans, and interpret results. User testing and internal review
have identified several pain points:

- **Excessive scrolling**: The Scan tab requires 4+ screens of scrolling. The inline
  profile picker alone occupies 1-2 screens before any results are visible.
- **Buried critical information**: Diagnosis summaries (the most actionable insight)
  appear below the profile list, forcing users to scroll past configuration to reach
  results.
- **Low information density**: Generous padding, always-expanded sections, and
  full-card-wrapped probe rows waste vertical space.
- **Developer jargon leaks**: Strings like `redacted(1)`, `Restrictions: enabled . disabled`,
  and a persistent debug performance bar are visible to end users.
- **Flat data dumps**: The Overview tab renders 25+ raw key-value rows (carrier codes,
  SIM IDs, signal levels) with no progressive disclosure. Fields showing "Unknown" still
  occupy space.
- **Unclear navigation**: Five tabs (Overview, Scan, Live monitor, Approaches, Share)
  have overlapping boundaries. "Live monitor" is only useful during active VPN sessions;
  "Approaches" and "Share" are low-frequency utilities.
- **Inconsistent status indicators**: Color-only dots without labels or shapes make
  status interpretation difficult, especially for colorblind users.

### Current implementation scope

~11,400 lines across 23 Kotlin files (pure Compose, Hilt ViewModel, StateFlow).
Core UI files: `DiagnosticsScanSection.kt` (1,619 lines), `DiagnosticsScreen.kt`
(1,116 lines), `DiagnosticsCards.kt` (1,080 lines).

---

## Design Principles

These principles guide every phase of the redesign:

1. **Global-to-Granular hierarchy**
   Status hero at top, category summaries in the middle, per-target results on
   drill-down, raw technical data at the deepest level. Users should never have to
   scroll past configuration to reach results.

2. **Tap-to-act**
   Profile selection should lead directly to action. Avoid two-step select-then-confirm
   flows. For automated profiles (probing, audit), use toggle switches.

3. **Progressive disclosure**
   Show the 5 most relevant fields by default; collapse the rest. Use expandable
   sections with count badges, bottom sheets for detailed views, and drill-down
   navigation for raw data.

4. **Standardized status system**
   Five levels: OK (green circle), Degraded (amber triangle), Blocked (red square),
   Inconclusive (grey diamond), Running (blue pulsing circle). Always combine
   color + shape + text. Never rely on color alone.

5. **No developer jargon in user-facing surfaces**
   Debug tools are accessible via developer gestures, not always-visible UI elements.
   Redacted values use human-readable labels. Technical codes are explained or hidden.

### Reference implementations

- **OONI Probe**: Dashboard with test category cards, tap-to-run, results grouped
  by test run, drill-down to technical details.
- **Fing**: Clean bottom-tab navigation, human-readable diagnostic results.
- **Grafana/Datadog Mobile**: Time-range selectors, sparkline mini-charts,
  alert-driven "problems" tab.

---

## Phases

Each phase is independently shippable and builds on the previous one.

### Phase 1: Quick Wins -- Density and Copy Cleanup

**Goal**: Reduce visual noise and fix the most jarring UX issues with minimal
structural changes. Biggest visual impact for the smallest code diff.

**Changes**:
- Hide debug performance bar behind a developer gesture (long-press on title)
- Replace `redacted(N)` with user-friendly text ("Hidden" / localized equivalent)
- Tighten vertical spacing in probe result rows and session rows
- Write distinct descriptions for each profile family (currently near-identical)
- Suppress fields showing "Unknown" in Overview snapshot/context cards

**Scope**: ~400 lines changed across 5 files.
No structural or architectural changes. No new UI components.

**Acceptance criteria**:
- Debug bar not visible without developer gesture
- No developer-facing strings visible in normal usage
- Scan tab scrolls noticeably less due to tighter row spacing
- Each profile card has a unique, descriptive summary

---

### Phase 2: Scan Tab Restructure

**Goal**: Eliminate the primary source of scrolling (inline profile picker) and
surface critical results at the top of the Scan tab.

**Changes**:
- Extract profile picker into a modal bottom sheet
- Replace inline profile list with a compact "selected profile" row + "Change" button
- Reorder Scan tab content: diagnosis summary first, then scan controls, then results
- Profile selection bottom sheet groups profiles by family with section headers

**Scope**: ~600 lines changed across 5 files. One new composable
(`ProfileSelectionBottomSheet`).

**Before**: Profile Picker (1-2 screens) -> Diagnosis Summary -> Controls -> Results
**After**: Diagnosis Summary -> Compact Profile Row -> Controls -> Results

**Acceptance criteria**:
- Scan tab fits on ~2 screens (down from 4+)
- Profile selection opens in a bottom sheet, not inline
- Diagnosis summary is visible without scrolling when present
- Profile selection bottom sheet groups profiles by family

---

### Phase 3: Overview Tab Progressive Disclosure

**Goal**: Transform the Overview tab from a flat list of raw key-value cards into
a layered dashboard with collapsible sections and meaningful grouping.

**Changes**:
- Create a reusable `CollapsibleSection` composable with animated expand/collapse
- Merge snapshot + context cards into a collapsible "Network Details" section
- Group latest session + automatic probes into collapsible "Recent Activity" section
- Wrap remembered networks in a collapsible section with count badge
- Filter empty/unknown fields from snapshot and context models
- Collapsed sections show item count badges

**Scope**: ~500 lines changed across 5 files. One new composable
(`CollapsibleSection`).

**Before**: 8 always-expanded cards with 25+ raw fields, many showing "Unknown"
**After**: 3-4 collapsible sections, unknown fields hidden, count badges on headers

**Acceptance criteria**:
- Overview tab fits on ~2 screens with sections collapsed
- Network Details defaults to collapsed
- Recent Activity defaults to expanded
- No "Unknown" values visible in any section

---

### Phase 4: Results Display Overhaul

**Goal**: Reduce per-result vertical footprint and standardize the status system
across all result types.

**Changes**:
- Create `CompactProbeRow` composable (~52dp height vs current ~80dp)
- Unify status tone mapping into a single canonical function
- Replace `ProbeResultRow` card-wrapped layout with compact inline rows
- Enhance probe detail bottom sheet with all fields (details on tap, not inline)
- Add shape tokens to status indicators (circle/triangle/square/diamond) for
  accessibility alongside color

**Scope**: ~700 lines changed across 5 files. One new composable (`CompactProbeRow`).

**Acceptance criteria**:
- Probe results take ~35% less vertical space
- Status indicators use consistent color + shape + text across all surfaces
- Tapping a probe row opens a detail bottom sheet with full information
- Shape tokens are distinguishable without color (accessibility)

---

### Phase 5: Navigation and Information Architecture Rethink

**Goal**: Consolidate five tabs into three primary surfaces that match user mental
models.

**Changes**:
- Redesign tab structure: **Dashboard** (overview + live metrics when VPN active),
  **Scan** (profile selection + execution + results), **Tools** (approaches + share/export)
- Merge live monitoring widgets into Dashboard (conditionally shown during VPN sessions)
- Create Tools section combining approach browser and export controls
- Update section enum, pager, and all ViewModel navigation
- Update all related tests

**Scope**: ~800 lines changed across 6 files. Highest risk phase due to test volume
(~3,200 lines of ViewModel tests reference the current 5-tab enum).

**Before**: Overview | Scan | Live monitor | Approaches | Share
**After**: Dashboard | Scan | Tools

**Acceptance criteria**:
- Three tabs with clear, non-overlapping purposes
- Live metrics appear in Dashboard when VPN is active, hidden otherwise
- Approaches and Share accessible from Tools tab
- All existing ViewModel tests pass with updated assertions

---

## Risk Assessment

| Phase | Risk | Mitigation |
|-------|------|------------|
| 1 | Low | Pure cosmetic, no behavior changes |
| 2 | Medium | Profile picker extraction changes user flow | Test profile selection + scan execution end-to-end |
| 3 | Low | Additive (wrapping existing cards in sections) |
| 4 | Medium | Probe row rewrite affects all result displays | Snapshot test comparison |
| 5 | High | Tab restructure touches navigation, ViewModel, and 3,200 lines of tests | Develop on a dedicated branch, thorough test updates before merge |

## Timeline Estimate

Phases are sequential but can overlap. Phase 1 can ship immediately.
Phase 5 should be developed on a dedicated branch due to its scope.
