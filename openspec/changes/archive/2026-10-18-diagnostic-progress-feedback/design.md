## Context

ScanProgressCard displays actual diagnostics snapshots. The shared progress bar
also serves other workflows and has a polite live region. Phase text currently
uses a decorative checkmark without an explicit accessible state.

## Goals / Non-Goals

- Goal: smooth truthful measured progress and show accessible phase changes.
- Goal: observe normal, reduced, system-off, real TalkBack, RTL and large font.
- Non-goal: alter scan scheduling, service lifecycle, data contracts or results.
- Non-goal: animate the event stream, add haptics, or infer progress from a timer.

## Decisions

- Use a diagnostics-only measured-progress wrapper. Keep shared progress callers
  unchanged. Animate forward values in one scan with the existing state token.
  Snap at initialization, new scan, backward correction, known/unknown boundary,
  reduced/off motion and inspection mode. Retarget from the current visual value.
- Expose current actual progress on one stable semantic node. Clear descendant
  semantics so intermediate fill frames and the shared bar live region cannot
  become speech. A phase-only polite summary has separate stable semantics.
- Keep phase chips keyed by phase label. Expose localized state using existing
  pending/active/done resources. Finite token-based feedback follows state only.
  Retain visible labels and reserve the completion mark width to avoid jumps.
- Keep current-probe visual text separate from phase speech. Under off/reduced
  motion show the current label directly; normal fade has no size transform or
  accessible outgoing label.
- Match components-progress-bar.html, components-stage-progress.html,
  motion-probe-pulse.html and motion-reduced-motion.html. Local phase feedback
  may differ from the deck to preserve speech and stable focus.

## Contracts and ownership

- Only :app presentation and tests change. No Rust crates, public wire schemas,
  database, JNI, service, migration or production dependency change is needed.
- Root owns locales, shared tags, task records, combined build/test runs and
  instrumented test sources. The task record lists all isolated worker paths.
- Progress worker owns the local wrapper and tests. Phase worker owns phase UI
  and tests. Root wires the progress call after the phase patch.
- Review is read-only. Device operations use one command-scoped pool lease.
  Actual TalkBack and TTS provenance and service state are retained. UiAutomation
  must not suppress the real accessibility service during its acceptance pass.

## Risks / Trade-offs

- Interpolated visuals can lag truth. Semantic values stay current, updates
  coalesce, resets snap, and completion cannot depend on the animation clock.
- Repeated live regions can create speech noise. Use one phase-only source and
  verify its real TalkBack output during progress-only ticks and focus changes.
- Stable chip width can change screenshots. Inspect owning golden diffs and
  seek exact fixture approval before any recording or blessing operation.
- Emulator evidence is not physical-device or real-network acceptance. Record
  controlled production-component tests separately from MainActivity smoke.

## Migration Plan

No stored migration or compatibility change is required. Revert the presentation
commit to roll back. Gate integration on focused RED/GREEN, theme and locale
checks, staticAnalysis, independent review, real APK device acceptance, and
architecture health with locked Cargo metadata. Main integration and push are
explicitly authorized. Retain exact-source CI separately from local proof.
