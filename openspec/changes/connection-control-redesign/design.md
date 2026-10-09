## Context

Task ID: `UIX-1791532861060606`. See proposal.md and specs/home-connection-controls/spec.md.

The current component combines a moving carriage, a fading action label, and an outlined route indicator. The resolver advances connection stages on a timer. The requested Mobbin MCP research has not run: no tools are connected and plugin discovery returned no result.

## Goals / Non-Goals

- Goal: give users one visible connection action and a separate route summary.
- Goal: show measured status only when evidence supports it.
- Non-goal: change network, service, JNI, storage, or wire behavior.

## Decisions

- Use the existing RDS theme, surface, state, typography, and motion contracts.
- Start with a status and route block above one full-width action. Keep the error detail below it. Confirm the final arrangement after the Mobbin research step, before any implementation.
- Prefer the existing RipDpiButton interaction and focus behavior over a custom drag-only control. Preserve guarded disconnect confirmation.
- Use an indeterminate connection state when actual stage events are unavailable. Do not infer successful stages from elapsed time.
- Keep existing service callbacks and lockdown policy. Test action-to-callback consistency in every state.
- Reference specs: docs/design/rds/preview/components-actuator.html and components-actuator-states.html. A separate action and route block would differ from their rail layout; record that intentional difference after final reference selection. Do not regenerate the spec deck.

## Contracts and ownership

- Connection UI owns :app component sources, HomeConnectionActuatorUiState, MainStateResolvers, related tests and previews, and the actuator string resource family.
- One writer owns the ten locale sets. No other serialized shared files need edits.
- No affected Rust crates, new dependencies, schema changes, migrations, or credentials.
- Preserve the public component entry point and automation tags where their meaning remains valid. Test any changed role or tag meaning.

## Risks / Trade-offs

- Mobbin reference selection is pending. Keep implementation blocked until research is available or the user chooses an alternative source.
- A button changes the rail metaphor. Review the actual compact, large-font, RTL, light, and dark renders.
- Home goldens will differ. Review actual renders first; golden blessing requires explicit authorization for the affected fixture family.
- Native build inputs may be unavailable in this worktree. Report any blocked native or device gates separately from UI checks.

## Migration Plan

No data migration is needed. Deliver the change on the isolated job branch. Revert the local implementation commit to restore the prior UI. Merge, push, and golden updates need their separate authorization.

Run component and resolver regression tests, theme tests, locale lint, static analysis, preview rendering, and an independent review before committing implementation. The verification record contains the exact planned commands and unresolved evidence.
