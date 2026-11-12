## Context

The audit identifies F1–F6 and H1. Real bytes must remain intact; raw behavior cannot be inferred from mock sends.

## Goals / Non-Goals

- Goal: fix all confirmed findings and prevent late-error replay.
- Non-goal: wildcard syntax, IDNA, external DPI compatibility claims, root activation or golden blessing.

## Decisions

- Add non-terminal fallback control so receipts change while subsequent steps continue.
- Apply MD5 by segment role; bound offsets and use wrapping addition.
- Share a span-aware fake-region builder between planner/runtime. Generate logical fake then overlay physical hostname spans; retain record gaps and public HostFakeSpan shape.
- Validate raw ASCII before lowercase in Kotlin, matching native policy; retain CLI dot acceptance.
- Add default-false randomFakeHost to nested proto and chain model/codecs/backups. Omit false JSON. Preserve a HostFake-only random=true DSL token and clear the flag on other kinds.
- Convert errors from the raw-send attempt onward to terminal errors retaining their source; keep helper delivered errors fail-closed. No new helper schema or blanket errno retry.
- Update existing HostFake settings text to explain raw requirement; do not probe capability or promise live readiness.

## Contracts and ownership

Portfolio ownership lanes apply. Config worker alone owns proto. Parent alone owns locales, generated exports and planning state. Each issue has a separate fix commit. Pure packet tests may run in nonroot Docker with all capabilities dropped; no live raw acceptance is implied.

## Risks / Trade-offs

- Reparse SNI mapping on first flight to avoid a public span shape change.
- Default omission retains old fixture bytes; additive proto false retains old settings.
- Terminal raw-send errors may conservatively refuse some no-send failures, preventing unsafe replay.
- Physical raw acceptance remains unverified.

## Migration Plan

Old proto data reads false. Allocate a fresh nested field number. Existing false JSON stays unchanged. Rollback drops true as older Android did. Run owner tests, independent review and combined gates, then fetch/rebase and fast-forward main/push as authorized. Keep worktrees and branches.
