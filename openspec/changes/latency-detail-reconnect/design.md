## Context

Home already receives aggregate quality and source metadata. It has no RTT time
series. Existing LatencyGraphRoute renders sample data and is not a production
data source. Home reconnect uses the real MainViewModel admission flow and a
safe saved/current comparison, but its prior UI acceptance did not run a real
service reconnect. See proposal.md and the delta requirements.

## Goals / Non-Goals

- Goal: open truthful selected RTT evidence and preserve accessible context.
- Goal: verify Home review against real native service and network traffic.
- Non-goal: future route calculation, a synthetic chart, runtime redesign or new dependencies.

## Decisions

- Add opt-in interaction to the RTT metric only; retain separate Reprobe behavior.
- Capture immutable quality plus its source on activation. Keep it while reading,
  and read new evidence on reopening. Describe it as a selected snapshot.
- Reuse the existing modal surface content and tokens. Use one local controlled
  host with a short finite reveal/fade and immediate reduced/off state. Preserve
  Home and its anchor. Do not rely on Material sheet motion to read app tokens.
- Reuse measurement strings where exact. Root owns any new labels in ten locales.
- Use the existing service entry and Home confirmation for acceptance. A local
  fixture may provide network endpoints and test configuration, but must not
  fake running status, native startup or applied runtime evidence. Retain actual
  requests before and after, session identity and saved/applied configuration.
- Separate real-service reconnect proof from controlled latency presentation
  snapshots. Test cancellation and stale review as well as confirmation.
- RDS references: components-metric-pill, components-bottom-sheet,
  motion-page-transitions and motion-reduced-motion. Adapt the Trackables
  reference to a local detail surface; no cross-window shared morph is claimed.

## Contracts and ownership

Only app UI and tests are planned. No Rust crate, service lifecycle, JNI,
protobuf, storage, activation or public contract change is planned. Existing
runtime admission remains authoritative. If acceptance reveals a production
fault, define its smallest root-cause fix and required gates before editing it.

Ownership is recorded in docs/tasks/issues/latency-detail-reconnect.md.
Root serializes locale resources, translation metadata, test tags and combined
builds. UI and AndroidTest writers use separate worktrees. Review is read-only.
Golden fixtures and quality baselines stay unchanged unless separately approved.

## Risks / Trade-offs

- Mismatched source/value: capture them together at the click boundary.
- Freshness overstatement: retain capture context and label the selected snapshot.
- Hidden motion: control host animation in normal/reduced/off and check actual pixels.
- Lost accessibility focus: preserve the origin node and verify real TalkBack,
  rather than treating input FocusRequester as proof of accessibility focus.
- Apparent reconnect success: require a changed actual runtime plus network traffic.
- Stale settings: test current admission guards rather than storing an old confirm callback.
- Device state: exclusive pool lease; record and restore motion, locale, TalkBack
  preferences and service state. No root or foreign-device reset is required.

## Migration Plan

No migration. Rollback uses scoped commits. Run a meaningful test-only RED before
production edits, focused host GREEN, affected Home/health/theme regressions,
unchanged golden verification, app/service locale lint and staticAnalysis.
Build fresh genuine Full app/test APKs and retain source and byte hashes. Use
API37 arm64 Google pool acceptance for detail normal/reduced/off, actual TalkBack
normal/off, RTL/font2, Back/dismiss, snapshot updates and real reconnect.
Rebase before combined architecture health and locked Cargo metadata checks.
Integrate ff-only and ordinary push with user authorization; inspect exact-SHA
hosted CI and keep task evidence open until the actual result is observed.
