---
id: EPC-1791124000119505
title: Implement measured network UX and durable connection controls
kind: epic
status: doing
area: epic
priority: high
owner: Codex sequential delivery
parent: null
blocked_by: []
spec_mode: required
openspec_change: measured-network-ux
created: 2026-10-04
updated: 2026-10-04
---

## Goal

Implement the seven approved Mobbin research improvements as sequential, runnable Android feature slices, each reviewed, validated, committed, integrated into main, and pushed.

## Acceptance criteria

- Explain direct and active-path scan scope and lifecycle consequences before running and in results.
- Explain measured metrics with units, aggregation/window and freshness; preserve no-data and uncertainty.
- Show factual active versus saved configuration and actionable recovery/permission states.
- Search and group actual diagnostic and saved relay profiles without losing selection.
- Preview diagnostic exports, apply supported redaction, and handle cancel/error without sharing.
- Persist timed pause and resume intent with explicit cancellation, restart recovery and truthful countdown.
- Persist favorite/recent profiles and expose measured automatic selection using actual payload URL-tests.
- Keep every new resource in all ten locales; use existing RDS components.
- Observe targeted tests, lint, visual artifacts, final combined gates, remote SHA and terminal CI; report device gaps honestly.
- Never close unfinished behavior or refusal-only output as delivered.

## Ownership and serialized lanes

- Root owns portfolio/OpenSpec state, integration to main, push, and final evidence.
- For API35 acceptance attribution, the debugger exclusively owned the four test-probe/Xray instrumentation files in `network-ux-api35-attribution`; root owns portfolio/verification records and real-device/fixture execution. Production routing and fixture assertions are unchanged until causal attribution. TUN source and its one approved JNI baseline remain frozen in `network-ux-tun-final`.
- One implementation sub-agent at a time owns the active feature slice in its own worktree; root never edits its production files concurrently.
- Locale sets, persistence schemas, and golden fixtures have exactly one writer at a time.
- Independent review agents are read-only. Heavy commands use build-gate and four workers/jobs maximum.
- Root owns only the Xray acceptance fixture, instrumentation evidence and CI failure capture in the isolated network-ux-xray-evidence worktree while the implementation agent owns the configuration slice. This lane gates real provider acceptance without changing routing or test assertions.
- The configuration foundation is frozen in network-ux-delivery. The implementation agent owns the Home measurement correction, its tests and all locale additions in network-ux-home-metrics; root owns task state, review, PNG verification and integration. After that source freezes, the golden-blesser alone owns affected Home PNG families in network-ux-home-goldens, using the identical reviewed source snapshot and the user's PNG authorization. Root imports only those reviewed PNGs; neither lane edits the other's source.
- For the export slice, the implementation agent owns core export contracts, managed leases, the retained preview owner, entrypoint integration and their tests in network-ux-export-preview. Root owns only the pure ExportPreview presentation/sheet, its UI and screenshot test sources, ExportPreviewTestTags, all ten locale resource sets and the translation export in the separate network-ux-export-ui worktree. This split was agreed before either writer touched those paths; root records it here before further source work. Root imports the frozen UI slice after its targeted gate. Root remains the sole writer of the seven existing portfolio/OpenSpec documents; the golden-blesser later owns only reviewed export PNGs in isolation. No other source, locale, schema or golden writer runs concurrently.
- Research source: docs/design/mobbin-network-ux-research-2026-10-04.md.

## Acceptance incident

- 2026-10-05: Export commit `cbf07ec1c6c4a3c28f65c7f00aff40a9d1ed23c2` passed full local core/app/static/locale/PNG gates and five ordinary committed-APK export scenarios. Exact CI `37340533624` completed with 45 successful jobs, 17 optional skips and two failed jobs: API35 instrumentation and its required aggregate. All four other Android API jobs and all three Full/Simple release channels passed. The unchanged Xray negative-identity test observed a real extra direct-target request in its XHTTP phase (count 3 to 4, label `wrong-identity-direct`). Export acceptance remains open while routing attribution and the cause are investigated; neither a retry nor weaker assertion closes it.
- 2026-10-05: Test-only attribution commit `60deb542fe14f2121538db8a405f6dc27df756a4` passed terminal CI `37357122304`, including all five Android API jobs and all three Full/Simple release channels. API35 used the same Google APIs x86_64 image and recorded VPN default-network state before and after both negative probes, without a direct-target receipt. These queries can affect timing, and aggregate counters do not establish per-flow ingress. The original Xray direct request remains unexplained; no production fix, assertion weakening, wait or retry was introduced.

- 2026-10-04: Reopened scope step EPC-1791124243600668 after real API 37 device validation. Scope controls and explanations passed, but an active-VPN scan failed before session persistence with a generic start error. Local UI tests had not exercised the public error projection for unavailable route evidence. The recovery slice must preserve typed unavailable reasons and observe a real scan retry without weakening authenticated route eligibility. Positive active-path acceptance remains unverified.
- 2026-10-04: Reopened metrics step EPC-1791124244103077 after real API 37 Home inspection showed nominal quality and zero RTT/jitter with zero samples. Health/detail fixtures and local tests did not exercise this Home projection. The correction must distinguish absent and partial measurements on Home and observe the real no-sample state.

- 2026-10-05: Exact-commit native API37 search input exposed an ANR in event-row comparison through DiagnosticsUiState.equals in DiagnosticsScreenPager. Correction c2f60e70 isolates rendered screen projections and moves state publication/comparison off the main thread while preserving event/history data and selection. Regression tests and 2051 combined app tests passed. Ordinary native input/filter/cancel/reopen/reset/selection acceptance then passed without a new ANR; the same process and native VPN service remained active. Feature a37fc813 and correction c2f60e70 are integrated into main and pushed; exact-SHA terminal CI 37261557639 succeeded. Search step is complete.
