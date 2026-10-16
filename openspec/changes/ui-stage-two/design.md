## Context

The app already has saved Mode, relay selection, pending actions and runtime
recovery. Config section selection is local navigation, yet it currently picks
the runtime action. Diagnostics combines a latest session with a selected-profile
scan and a separate composite audit. Logs already receives continuous flows.
See proposal.md and specs/ui/stage-two-workflows/spec.md for observable behavior.

## Goals / Non-Goals

- Goal: one clear saved connection summary, compact profile disclosure, one
  diagnostic outcome and a dominant live event list.
- Non-goal: change engine settings, stored profiles, network orchestration,
  service lifecycle, scan algorithms, recommendation confidence or auto-apply.
- Non-goal: claim user-comprehension, TalkBack or release-performance acceptance
  from screenshots or component instrumentation.

## Decisions

- Keep saved state authoritative. Separate traffic scope controls from settings
  navigation. Use a single primary runtime action tied to activeMode; a settings
  tab does not silently change Mode or activate a relay. Keep existing explicit
  profile activation callbacks and consent recovery.
- Show saved profile name and current path in a compact card. A change action
  discloses choices. Put CLI precedence prose and long configuration help in
  explicit detail disclosure. Keep errors beside the main runtime action.
- Add a pure app outcome projection with deterministic evidence ownership:
  active work, then a composite audit with its own metadata, then the latest
  session, then empty. Selected-profile recommendations must retain matching
  provenance; never borrow another source's time or confidence. Missing
  metadata stays unknown. Keep historical and technical detail below one hero.
- Rename the local expert disclosure to technical details. Use the persisted
  persona to choose both the primary operation and its visible label. Expanding
  details does not select another scan type.
- Remove the outer log vertical scroll and false pull-to-refresh. Use a bounded
  screen Column, compact actions, optional filters and a weighted LazyColumn.
  Preserve stable event keys, copy semantics, source flows and live-edge guards.
- Use existing RDS surfaces, state and motion tokens. New composable models are
  Immutable or Stable. New list items have stable keys and content types.

## Contracts and ownership

- Affected module: app. No Rust crates or public JNI/protobuf/wire/storage
  contracts change. Mode, relay activation and profile mutation contracts remain.
- Connection writer owns config screen family and config tests. Diagnostics
  writer owns app diagnostic overview/projection and its tests. Logs writer owns
  LogsScreen, LogsViewModel and log tests. Each uses a dedicated worktree.
- The integration writer owns every locale XML, RipDpiTestTags, shared device
  tests, OpenSpec, task files and combined validation. Workers request keys/tags
  before use. No concurrent shared-file writers.
- RDS references: vpn-profile-switcher, diagnostic-report-summary,
  components-log-stream and components-log-row. Read-only preview files stay
  unchanged. The deliberate hierarchy changes are recorded in the portfolio.

## Risks / Trade-offs

- Similar diagnostic models may describe different runs. Keep source identity
  and metadata together; add mixed-profile, stale and partial regression cases.
- Header or actions can displace content at 360dp and 2x font. Check bounds and
  reachability in JVM tests and fresh light/dark emulator images.
- A new event can steal scroll position. Preserve the previous live-edge state
  and test append while viewing history.
- Intentional UI changes may differ from Roborazzi fixtures. Run owning verify
  tests and inspect expected/actual/diff before any update. Do not bless without
  explicit authorization for the affected fixture family.
- Hosted CI has a prior coverage failure whose root cause is unconfirmed.
  Record local and hosted results separately; do not weaken gates.

## Migration Plan

No data migration or new dependency is needed. Each feature slice includes
behavior tests and implementation. Roll back with ordinary revert commits if
acceptance reveals a regression; stored settings remain compatible.

Run reproducing JVM tests before each fix, then focused tests on the combined
tree. Required final gates: staticAnalysis; app lintGithubFullDebug and service
lintDebug; Full debug app/test APK assembly; app theme tests; architecture health;
cargo metadata --locked; task/OpenSpec strict validation; affected screenshot
verification. Build under build-gate, unset CARGO_BUILD_JOBS, max-workers=4 and
nativeCpuBudget=4. Use the verified local Xray AAR directory when needed.

Use a command-scoped API 37 arm64 emulator pool lease for interaction and fresh
screenshots. Exercise saved Mode versus settings navigation, profile disclosure,
single outcome with stale/partial data, disclosure/action consistency and live
logs/filters/history retention. Inspect light/dark, 360dp and 2x font. Preserve
the first-stage device regressions. Integrate and push only after the final
combined-tree gates; user authorization already includes both operations.
