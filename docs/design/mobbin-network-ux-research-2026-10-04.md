# VPN and network utility UX patterns for RIPDPI

Research date: 2026-10-04. Repository baseline: `12635f714dfc5d09454c1f2faa74b7ad46cb0008`.

## Recommendation

Prioritize clearer measurement scope, understandable metrics, and configuration consequences. RIPDPI already has the main visual building blocks: a connection actuator, prioritized advisories, progressive disclosure, profile sheets, evidence details, and freshness indicators. Reuse those components rather than adding a second dashboard or copying a commercial VPN's map and security language. [R1][R2][R3][R4][R5]

The most useful references are NordVPN for stable connection-state geometry and selection hierarchy, Starlink for separating measurement paths and explaining fresh evidence, and Quo for connecting technical metrics to user consequences. Opera and Brave provide smaller configuration patterns. The recommendations below are design inferences from observed interfaces, not claims that RIPDPI already implements the referenced runtime behavior.

## Evidence and limits

- The lead researcher visually inspected Mobbin's returned screen previews and the two referenced NordVPN flows. The observations in the source table are attributed to that visual review; the report author inspected RIPDPI source and an existing Home screenshot baseline.
- These are third-party archived captures of first-party app interfaces. They support visible layout, labels, grouping, and captured flow order; they do not prove runtime correctness, security properties, current availability, or accessibility.
- The references are iOS interfaces. Adapt ideas to Android permission handling, Compose semantics, Back behavior, and RIPDPI's existing tokens; do not copy iOS controls literally.
- No live interaction, device verification, user testing, or newly rendered RIPDPI UI was performed. Repository screenshots are fixtures, not live acceptance evidence.
- App-name queries for Proton VPN, eero, and Speedtest returned other applications. No verified evidence for those named apps was obtained, and those results do not establish absence from Mobbin's catalog.
- Quo is a voice application used as an adjacent connection-quality reference, not a VPN or general network utility.
- Searches were exploratory and bounded, not an exhaustive review of the catalog. App capture dates and versions were not established.

## Observed reference patterns

| Source | Observed interface | Applicable RIPDPI idea | Boundary |
|---|---|---|---|
| [NordVPN connected Home][M1] / [paused Home][M2] | A consistent map/card region changes between connection states; connected state has location, state text, Pause and overflow; paused state has a primary reconnect CTA and resume countdown. Recents appear below the main region. | Keep one stable connection surface; make active mode/path and factual lifecycle state legible, with secondary actions below the primary control. | Keep RIPDPI's actuator. A map, generic security claim, recents, or countdown is not automatically justified. |
| [NordVPN pause sheet][M3] | A sheet offers 5/15/30 minutes, 1 hour, and 24 hours; Disconnect is separated at the bottom. | Put genuinely secondary connection actions in a contextual sheet and distinguish a temporary action from stopping the service. | Timed pause requires lifecycle, persistence, and resume behavior; it is feature work, not a cosmetic sheet. |
| [NordVPN location picker][M4] / [expanded country][M5] | Search, category chips, country rows with city counts and chevrons; expansion shows Fastest server with supporting text and city rows. | Organize existing profiles by meaningful type or provider; expose a concise selected summary before configuration details. | Search only becomes useful at sufficient list size. Favorites, recents, fastest selection, and geographic metadata require their own data contracts. |
| [Opera VPN sheet][M6] | A location row and a bypass switch sit with a consequence explanation and a separate Disconnect action. The sheet also displays broad protection language. | Explain what a setting changes immediately beside its control, especially measurement scope or routing effects. | Borrow consequence text; do not borrow the broad protection claim or imply browser-specific behavior exists. |
| [Brave VPN settings][M7] | Enabled switch; separate Subscription, Server, and Support groups; server name, WireGuard transport, and Reset Configuration are explicit rows. | Group active configuration facts separately from support and editing actions. Show actual runtime transport when available. | Do not add subscription UI or assume WireGuard/reset support from the reference. |
| [Quo connection sheet][M8] | A qualitative summary and 4.4 score lead into Jitter, Latency, and Packet loss rows; each metric combines explanation and raw value (8 ms, 46 ms, 0.0%). | Pair a supported metric with a short consequence explanation, unit, and value. Keep detailed evidence one tap away. | Do not invent a composite quality score, jitter measurement, or quality threshold. |
| [Starlink advanced test][M9] | Phone → router → Internet diagram; separate Device to Router and Router to Internet values; Download/Upload selector, labeled two-series chart, and Start advanced test. | Make the measured path explicit before a test and on its result; distinguish raw-path from in-path measurements without merging them. | RIPDPI does not gain router or access-link throughput measurement through a diagram. |
| [Starlink statistics][M10] | Metric cards combine number, unit, sparkline, named statistic, and last-15-minute window; outage text states its duration threshold and window. | Name what a chart summarizes and its time window; keep historical and current values distinct. | A graph needs actual samples and aggregation rules. Do not copy thresholds or fixed windows without product evidence. |
| [Starlink ping][M11] | This device, Starlink, and Router are distinct sections; Google and Cloudflare endpoints have status and percentage charts; Outages is a separate action. | Group evidence by source/path and show the tested endpoint, so one passing probe is not mistaken for universal reachability. | Do not imply unavailable router-side evidence or universal Internet status. |
| [Starlink debug sheet][M12] | Share action, grouped router fields, Updated just now, and separate device checks for reachability, Wi-Fi, local access, and VPN state. | Group diagnostic context; show evidence age; preview sensitive content before explicit export. | Export preview and redaction are RIPDPI-specific recommendations, not features established for Starlink by this capture. |
| [Starlink unreachable settings][M13] | Router/Starlink selector, inline router-unreachable guidance to check power/Wi-Fi, and a separate Factory reset action. | Explain the likely cause and safest relevant recovery action near the failing area. | Do not make broad reset the primary response to an uncertain failure. |
| [NordVPN enabling flow][M14] / [permission screen][M15] | Disconnected → native iOS VPN permission → connected across three captured screens. | Explain the Android VPN permission at the point of activation; keep state transitions visible. | Also design denied, cancelled, starting, failed, and stopping states; the capture does not establish their behavior. |
| [NordVPN auto-connect flow][M16] / [reconnect dialog][M17] | Off/Wi-Fi/Always selection, a Cancel/Reconnect modal, and a selected Wi-Fi state after the captured flow. | Distinguish saved configuration from currently applied configuration when changes require reconnecting. | Verify RIPDPI's edit/apply contract before adding a pending state or reconnect affordance. |

## Mapping to the current implementation

| Current RIPDPI source | Existing capability evidenced by source | Design implication |
|---|---|---|
| [HomeScreen.kt][R1], lines 100, 117, 134, 151 | One actuator, one severity-ordered advisory slot, measured degradation strip, and Modes & diagnostics disclosure. | Retain the hierarchy. Improve wording and contextual evidence instead of adding another status-card stack. |
| [HomeModeCard.kt][R2], lines 52, 62, 123, 162 | Action/loading state, selected active card surface, font-scale adaptation, textual status indicator. | Reuse selection and state styling; test wording without relying on color alone. |
| [DiagnosticsScanSection.kt][R3], lines 94, 120 | Profile selection sheet and compact selected-profile row. | Reuse the picker before introducing new navigation. Label selected scope and consequence near the run action. |
| [DiagnosticsBottomSheets.kt][R4], lines 40, 60, 71, 352 | Session/event/probe detail host, sensitive-detail visibility toggle, and profile picker sheet. | Use existing drill-down and privacy controls; keep results concise until the user asks for evidence. |
| [DiagnosticsLiveSection.kt][R5], lines 132, 207 | Live hero and wall-clock freshness indicator; stale data receives a distinct badge. | Preserve freshness behavior and expose timestamp/window consistently for other measured summaries when the model supports them. |
| [ConnectionHealthScreen.kt][R6], lines 64, 74, 84, 239, 314 | Quality summary, no-data state, latency distributions, and DNS counters. | Add meaning to available values rather than constructing a new health dashboard. |
| [DiagnosticsScreen.kt][R7], lines 75–95, 235 | Session/event filters and searches, detail actions, explicit share/save actions, and section switching. | Existing history navigation provides the starting point; avoid duplicating it as Home recents. |
| [RipDpiRouteComponents.kt][R8], lines 94, 123, 267 | Route profile presentation and stack-diagram UI components. | These are presentation structures, not evidence of a new protocol, provider, or geographic catalog. |
| [RipDpiExportConsentDialog.kt][R9], lines 38–46, 52, 66 | Export metadata/content input and endpoint-redaction choice defaulting to true. | Reuse consent UI where the actual export flow supports it; audit wiring separately before claiming every export is redacted. |
| [Design system][R10], lines 153–160, 168–172, 209–214 | Collapsed Home disclosure, evidence grouping, monospace low-level facts, modal drill-down. | References fit the current system when translated into its existing layout and semantic roles. |

The inspected [expanded Home baseline][R11] shows the actuator, Connection health entry, and three mode cards inside the disclosure. The lead also reviewed disconnected Home, Diagnostics scan, and History fixtures. Together with the source, these establish existing hierarchy; they do not establish current device rendering or the quality of real measurements.

## Recommended flows

### Home: state → consequence → next action

1. Keep the existing actuator first. Show the actual active mode/path and lifecycle state; avoid implying that activation proves encryption, privacy, or universal reachability. NordVPN's stable geometry is useful; its broad security wording is not the product contract. [M1][M2][R1]
2. Keep the existing highest-priority advisory immediately below. State the failing requirement or condition, its consequence, and a relevant repair action. Keep ordinary metrics in the degradation strip rather than in competing banners. [M13][R1]
3. Keep Connection health as the entry to measured details. Consider one concise measured fact only when current data exists; include freshness and keep the existing no-data state. [M8][M10][R5][R6]
4. Retain collapsed Modes & diagnostics. Within it, separate selected configuration facts from Enable/Run and Configure actions; avoid repeating the same status as both a badge and supporting sentence. Use the active-card surface already implemented. [M7][R2][R10]

### Diagnostics: scope → run → result → evidence

1. Present selected profile and measurement path before running. The architecture distinguishes raw-path/direct measurement with VPN stopped from in-path measurement through the active proxy/VPN. Explain that consequence beside the action, using orchestration-specific wording such as “temporarily stops VPN for direct-path measurement” only where truthful. Any diagram names measurement scope rather than pretending to measure individual router hops. [M6][M9][R3][R14]
2. During a run, preserve meaningful progress and cancellation. On completion, present the observed result, measurement scope, and available timestamp. Do not equate a passing DNS/TCP/TLS/HTTP probe with all traffic being healthy. [M9][M11][R3][R4]
3. For supported metrics, show `name + value + unit`, a short explanation, and the statistic/window where applicable. A latency percentile and an average must remain distinguishable. Use the existing Connection health and live components. [M8][M10][R5][R6]
4. Open row details for evidence, endpoint context, and uncertainty. Keep raw fields grouped and monospace; put sensitivity controls beside sensitive fields. [M12][R4][R10]

### Details and configuration: facts → editing → explicit effects

1. Reuse profile sheets and route cards for a concise identity and configuration summary. Add grouping/search only when actual profile volume warrants it. [M4][M5][R3][R8]
2. Keep saved and active configuration distinct where runtime semantics require that distinction. Before implementing a reconnect dialog, verify cancellation leaves the current runtime and saved settings in a coherent state. [M16][M17]
3. For exporting evidence, describe content and sensitivity, offer supported redaction, and require explicit export. Do not suggest automatic cloud sync or background upload. [R9]

## Priorities and implementation boundaries

| Priority | Proposed improvement | Work classification | Completion evidence for a future implementation |
|---|---|---|---|
| P1 | Clearer raw-path/in-path labels and inline scope explanations | Primarily copy/layout reuse; derive wording from the existing scan contract. | Both paths understandable before running; targeted UI tests and Android screenshots with real-state labels. |
| P1 | Metric explanations, units, named statistic/window, consistent freshness | Existing components/models first; add model fields only where data exists. | No-data, fresh, stale, and partial-data cases; displayed values trace to actual samples and timestamps. |
| P1 | Factual active-configuration summary and causal recovery wording | Reuse mode cards/advisory slot; verify lifecycle and pending-apply semantics. | Starting/active/failed/denied cases; cancellation and failed application retain truthful state. |
| P2 | Profile grouping and search | Existing picker/route components; new search state only if needed. | Selection, empty/filter-empty, large lists, Back/dismiss, and preservation of selection. |
| P2 | Evidence/export preview consistency | Existing sheets and consent component; verify each intended export path. | Sensitive fields, redaction choice, export failure, cancellation, and actual output inspection. |
| Later | Timed pause/resume | Genuine runtime feature: service lifecycle, deadlines, persistence, and recovery. | Resume after elapsed time, app/process interruption, network change, and user cancellation; no countdown without a valid resume contract. |
| Later | Favorites, recents, or automatic fastest selection | Genuine product/data work, not a visual pattern alone. | Persistence/privacy rules, measured selection basis, and honest unavailable/stale states. |

No dependency is needed merely to express these layouts. Any later implementation should use the existing monochrome-first [design contract][R12] and shared components, then validate light/dark, large font, RTL, TalkBack, and Android Back behavior. New resource keys must cover the repository's ten locales. Screenshot fixtures would need review through their owning workflow; this research does not authorize blessing them. [R10][R13]

## Ideas to reject

- **World map as the primary surface:** geography is useful only with meaningful geographic route data. It displaces RIPDPI's stronger path and diagnostic evidence. [M1][M4][R1]
- **Generic Protected/Secured badge:** an enabled tunnel or local service is not proof of every traffic property's security. Name the observed state and the specific measurement instead. [M1][M6][R1]
- **Arbitrary connection score:** Quo's score is visible, but its formula is not established by the capture. Show supported metrics and uncertainty instead of inventing a comparable score. [M8][R6]
- **Invented router/Internet throughput split:** Starlink's hardware-oriented path model cannot be copied as a RIPDPI measurement claim. [M9]
- **Permanent metric-heavy Home or duplicate recents/history:** these conflict with current progressive disclosure and existing diagnostic/history navigation. [R1][R7][R10]
- **Commercial onboarding, subscription upsells, broad reset, or automatic sharing:** they do not follow from RIPDPI's offline-first diagnostic purpose and add unrelated product obligations. [M7][M13][R13]

## Source index

Mobbin links resolve to individual captured screens or flows. Repository links are relative to this report and line pointers refer to the baseline SHA above.

[M1]: https://mobbin.com/screens/efe68d88-42bb-4d50-b002-15a052b95f3d
[M2]: https://mobbin.com/screens/a4af6c13-52b3-4e79-b6a4-65f351ec9255
[M3]: https://mobbin.com/screens/4866f93d-811c-4bc1-a4b4-b4daf178f860
[M4]: https://mobbin.com/screens/4d34bcf5-6a1e-4ceb-aa06-90d55ac49b3c
[M5]: https://mobbin.com/screens/ad595447-3d00-41ea-8119-80eb86d7ddfe
[M6]: https://mobbin.com/screens/d535f3b2-da66-427c-92d7-b679c7dede17
[M7]: https://mobbin.com/screens/3958184b-6973-4b23-8fd8-3f8c812a3f70
[M8]: https://mobbin.com/screens/0a290bb8-a09e-4569-a5e6-d82ba3f1421d
[M9]: https://mobbin.com/screens/177843a6-2027-4a20-96e1-9fe9225053dc
[M10]: https://mobbin.com/screens/bd4e8d2b-1702-4ec0-84b9-c129b7844b73
[M11]: https://mobbin.com/screens/77305d30-a9e6-42fb-a030-7b706463605f
[M12]: https://mobbin.com/screens/acc1a38c-2041-43cd-bc4c-d38fd085bedb
[M13]: https://mobbin.com/screens/1b93e87a-9f5e-4c24-a422-d0c2a1586a32
[M14]: https://mobbin.com/flows/009d5a29-83bb-4292-abae-7e17dbe4ce40
[M15]: https://mobbin.com/screens/b5d40787-b9a6-4dca-8bf7-b392e987b38e
[M16]: https://mobbin.com/flows/24cb0fc2-b7eb-4f5e-bb69-b399ade7f65c
[M17]: https://mobbin.com/screens/ad20d5ff-6235-4203-9bdb-2c4bd2758cf3
[R1]: ../../app/src/main/kotlin/com/poyka/ripdpi/ui/screens/home/HomeScreen.kt
[R2]: ../../app/src/main/kotlin/com/poyka/ripdpi/ui/screens/home/HomeModeCard.kt
[R3]: ../../app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScanSection.kt
[R4]: ../../app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsBottomSheets.kt
[R5]: ../../app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsLiveSection.kt
[R6]: ../../app/src/main/kotlin/com/poyka/ripdpi/ui/screens/health/ConnectionHealthScreen.kt
[R7]: ../../app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreen.kt
[R8]: ../../app/src/main/kotlin/com/poyka/ripdpi/ui/components/routes/RipDpiRouteComponents.kt
[R9]: ../../app/src/main/kotlin/com/poyka/ripdpi/ui/components/feedback/RipDpiExportConsentDialog.kt
[R10]: ../design-system.md
[R11]: ../../app/src/test/screenshots/com.poyka.ripdpi.ui.screenshot.HomeDisclosureScreenshotTest.expandedDefault_full.png
[R12]: ../../DESIGN.md
[R13]: ../../AGENTS.md
[R14]: ../architecture/DIAGNOSTICS_ARCHITECTURE.md
