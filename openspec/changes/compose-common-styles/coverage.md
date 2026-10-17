# Styles migration coverage

This inventory records source scope and explicit exceptions. A source candidate is not an accepted migration. See `verification.md` for observed compile, behavior, visual, device, and performance results.

## Owned shells

| Family | Source scope | Style scope | Validation |
|---|---|---|---|
| Actions | Button, IconButton | Variant shell, border, numeric radius, content scale and padding | 87 selected theme/shared-control tests passed, including recipe replacement motion; visual/device checks pending |
| Inputs | Chip, Switch, SegmentedButton | Own shell and state fills; Switch track and thumb use separate resolvers | 87 selected tests passed; device/visual checks pending |
| Editing | TextField, ConfigTextField, Dropdown | Own decoration shell, focus/error border, logical padding and disabled content alpha | Six new input tests passed within the 87-test gate; device IME pending |
| Cards and rows | Card, PresetCard, SettingsRow | Role surfaces, selected preset badge/radio marker, row and leading badge | 99 selected tests passed; visual/device checks pending |
| Routes | ProfileCard, CapabilityPill, StackDiagram, OpportunityPanel | Own shells, status badge/glyph, connectors | 99 selected tests passed; visual/device checks pending |
| Connection | ConnectionActuator | Delegated primary action and own fault-detail shell | 99 selected tests passed; visual/device checks pending |
| Feedback | Accordion; nested banner/dialog/sheet/degradation decoration | Accordion shell, icon badges, sheet handle and degradation stripe | 99 selected tests passed; visual/device checks pending |
| Indicators | LogRow, StaleDataBadge, PageIndicators, AnalysisProgressIndicator | Badges/chips, stale capsule, page fill, outer analysis shell | 99 selected tests passed; visual/device checks pending |
| Navigation | BottomNavBar, RipDpiNavRail | Bar/rail containers, shared and local selection pills, focus border, brand and selected rail fill; typed tab scale/alpha State through styles | 142 selected unit tests passed, including five navigation tests. Owning screenshot verification: nine passed, two known Diagnostics mismatches; actuals match pre-navigation source. Broad repeat pending |
| Scaffolds | ContentScreen, Settings, Dashboard, Intro | Existing background nodes; layout and inset contracts stay in place | Pending |
| Home and history | HomeScreen, HomeModeCard, analysis sheet sections, HistoryScreen/Cards/EmptyStates | Screen backgrounds and own summary/badge shells | Pending |
| Configuration and settings | ConfigScreen, ModeEditorChainBlockEditor/ScaffoldSections, AdvancedSettingsContent/Components, SettingsPreferences, RememberedNetworks, RootModeStrategies, DataTransparency | Own page, chain, badge and summary shells | Pending |
| Diagnostics and logs | DiagnosticsScreen/Cards, PortMatrix, PcapViewer, ReplayFailure, LogsScreen | Existing page, matrix, packet and replay decoration shells | Pending |
| Onboarding and related screens | OnboardingScreen/OptionCard/SetupPages, VpnPermission, AppCustomization/IconPicker/About, DNS, OwnedStackBrowser | Own backgrounds, option/radio/badge shells, including runtime icon colors | Pending |
| Simple | Shared controls and SettingsRow; existing Material Scaffold | Receives shared component styles; no redundant screen resolver | Pending |

## Design references

The migration keeps the current Compose tokens and verified screenshots. The HTML deck is read-only and retains some earlier compact layouts, as documented in `docs/design-system.md`. This change introduces no design revision.

| Family | Existing RDS preview references |
|---|---|
| Actions | `components-buttons`, `components-icon-buttons` |
| Inputs | `components-chips`, `components-switch`, `components-segmented`, `components-text-field`, `components-dropdown` |
| Surfaces | `components-card`, `components-preset-card`, `components-settings-row`, `components-actuator`, `components-actuator-states` |
| Feedback and chrome | `components-accordion`, `components-warning-banner`, `components-dialog`, `components-bottom-sheet`, `components-bottom-nav`, `android-nav-bar`, `android-nav-rail` |
| Indicators | `components-status-indicator`, `components-page-indicators`, `components-log-row`, `components-analysis-progress`, `components-stage-progress` |
| Feature scenes | `android-history`, `diagnostic-port-matrix`, `vpn-in-app-browser`, `onboarding-tour-cards` |

Each name above is an existing `docs/design/rds/preview/<name>.html` file. Route shells and feature scenes without an exact preview use their current implementation and screenshot catalogs as the visual reference.

## Explicit exceptions

| Area | Retained implementation | Reason |
|---|---|---|
| Text and icons | Explicit RDS typography, text/icon colors, required text animations | Material does not consume CommonStyle. A resolver must not be read from Composition before attachment. |
| Material feedback hosts | Surface shells in WarningBanner, DialogCard, BottomSheetCard, Snackbar, DegradationStrip and metric surfaces, ContextMenu, ExportConsentDialog, tooltips | Preserve Material content locals, elevation, semantics and host behavior through explicit parameters. Own nested decoration is in scope above. |
| Material controls/chrome | Tabs/SecondaryTabRow, dropdown menu/items, Material Scaffold, navigation destinations/transition hosts, modal sheet host and dividers | Preserve the native component contract. No ineffective style overlay is added. |
| Diagnostic Material cards | WinningPathCandidateCard, StrategyProbeCandidateRow, CollapsibleCountBadge, OomRecoveryBanner/WarningBadge, PCAP metadata/desync badges, ProfileVariantCard/ChipRow, state-machine NodeCard, StrategySidePanel/VerdictStrip, StrategyImport SourceTile/Badge, ThroughputRangeChip/StatCell | These are Material Surface hosts. Their explicit surface parameters remain the source of paint. |
| Navigation geometry | Bottom navigation indicator offset/width/alpha/scale, measured label reflow, rail width/scroll | Preserve adaptive geometry and existing typed tween timing. Owned paint and focus state use styles. |
| Feature motion and plots | OnboardingScreen parallax; DetectionSettingsScreen network-section opacity; DiagnosticsWidgets and detection StatusVisualIndicator graphs/Canvas; dropdown/accordion chevron rotation, Switch thumb offset, PageIndicators width | These values describe feature motion, plotted data, or layout, rather than shared shell styling. |
| Drawing and progress | StatusIndicator, Spinner, ProgressBar, StageProgressIndicator, inner Analysis progress, stale marker Canvas, telemetry plots and disconnect countdown | Preserve drawing algorithms, numeric progress, custom geometry and animation timing. |
| Remaining clipping | Diagnostics pager, handshake bar track, latency plot/loss row, quality/throughput plots, active onboarding checklist marker | Clipping defines drawing or page bounds. It is not a shared visual state recipe. |
| Structure and delegation | TextAction, action layouts, category headers, endpoint rows, top app bar and chrome wrappers with no owned paint | These compose styled RDS or Material components and do not need another resolver. |
| Glance widgets | Existing Glance modifiers and widget theme | These widgets use the Glance target, not Compose UI modifier nodes. This migration adds no shared color, shape, or motion value for the widget theme. |
| Preview-only decoration | Preview scrims and scene backgrounds | Test/preview context is outside production styling. It must still match the intended screenshot scene. |

Experimental opt-in is file-local. There are no inherited text-style flags, transitional style APIs, new locale keys, new backend services, or approved golden changes in this migration.

## Evidence boundaries

- The dependency-only commit is separate from the style source changes. It establishes a reproducible middle state for the three-state comparison.
- Component tests exercise actual clicks, selection, disabled/loading behavior, required targets, theme updates, clipping, alpha and RTL padding. Synthetic parser tests only validate report handling.
- The benchmark-only activity renders real screen/control composables with fixed local data. It does not establish production navigation, storage, service, or network acceptance. Product startup is measured separately with the production Application.
- Performance runs must use the same profileable target configuration, harness, device and conditions for original, dependencies, and styles in both series. Report emulator measurements as emulator evidence. Missing runs and single-series threshold breaches are inconclusive, not passes.
- Screenshot differences require classification. This change does not authorize golden blessing, main integration, publication, or push.
