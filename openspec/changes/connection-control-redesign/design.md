## Context

Task ID: `UIX-1791532861060606`. See proposal.md and specs/home-connection-controls/spec.md.

The current component combines a moving carriage, a fading action label, and an outlined route indicator. The resolver advances connection stages on a timer. Mobbin MCP research is complete. The search returned iOS references; this change uses their information hierarchy with the existing Android RDS components.

## Goals / Non-Goals

- Goal: give users one visible connection action and a separate route summary.
- Goal: show measured status only when evidence supports it.
- Non-goal: change network, service, JNI, storage, or wire behavior.

## Audit findings

| Priority | Finding in the initial control | Change and acceptance check |
|---|---|---|
| High | Elapsed time advances startup stages without measured completion events. | Remove timer projection and the stage pipeline; resolver tests keep all startup stages pending after 60 seconds. |
| High | The moving carriage hides the action label and makes the activation gesture unclear. | Use one visible labeled button; test touch, keyboard, and accessibility callbacks. |
| Medium | The outlined Direct indicator looks like a separate button but has no action. | Show route and exit as read-only text; assert no click semantics. |
| Medium | Fixed drag distances and a single-row label make compact widths and large text fragile. | Remove the rail and wrap labels; inspect 320dp, fontScale 2.0, and RTL. |
| Medium | Disabled and guarded states need an explicit action contract. | Use disabled button semantics and a visible second-press disconnect label; test lockdown, expiry, swipes, and state changes. |

These findings combine the supplied screenshot with source inspection. A screenshot alone cannot prove focus order, TalkBack behavior, runtime traffic, or exact color contrast. Runtime and device acceptance remain separate evidence categories.

## Decisions

- Use the existing RDS theme, surface, state, typography, and motion contracts.
- Place a status row and a wrapping text-only route summary above one full-width RipDpiButton. Keep the error detail below it. The busy spinner is outside the button so cancel remains enabled.
- Prefer the existing RipDpiButton interaction and focus behavior over a custom drag-only control. Preserve guarded disconnect confirmation.
- Use an indeterminate connection state when actual stage events are unavailable. Do not infer successful stages from elapsed time.
- Keep existing service callbacks and lockdown policy. Test action-to-callback consistency in every state.
- Reference specs: docs/design/rds/preview/components-actuator.html and components-actuator-states.html. The separate action and route block intentionally replaces their rail layout because the rail hides action labels and makes read-only route information resemble a button. Do not regenerate the spec deck.

## Contracts and ownership

- Connection UI owns :app component sources, HomeConnectionActuatorUiState, MainStateResolvers, related tests and previews, and the actuator string resource family.
- One writer owns the ten locale sets. No other serialized shared files need edits.
- No affected Rust crates, new dependencies, schema changes, migrations, or credentials.
- Preserve the public component entry point and automation tags where their meaning remains valid. Test any changed role or tag meaning.

## Risks / Trade-offs

- Mobbin supports iOS and web in this integration. The references establish information hierarchy, not Android platform compliance.
- A button changes the rail metaphor. Review the actual compact, large-font, RTL, light, and dark renders.
- Home goldens will differ. Review actual renders first; golden blessing requires explicit authorization for the affected fixture family.
- Native build inputs may be unavailable in this worktree. Report any blocked native or device gates separately from UI checks.

## Migration Plan

No data migration is needed. Deliver the change on the isolated job branch. Revert the local implementation commit to restore the prior UI. Merge, push, and golden updates need their separate authorization.

Run component and resolver regression tests, theme tests, locale lint, static analysis, preview rendering, and an independent review before committing implementation. The verification record contains the exact planned commands and unresolved evidence.

## Inspected Mobbin references

- [NordVPN widgets](https://mobbin.com/screens/8b54292c-d164-4c53-a5c4-df5ed16853e5): the connection action is prominent and separate from server and status text. Adopt this hierarchy, not its colors or claims about security.
- [Opera VPN panel](https://mobbin.com/screens/d535f3b2-da66-427c-92d7-b679c7dede17): status, location, and disconnect occupy separate zones. Adopt the separation; RIPDPI route information remains read-only.

These images were retrieved and inspected through Mobbin MCP on 2026-10-09. Selection is a local design decision, not evidence that either reference has been usability-tested for RIPDPI. The design retains RDS monochrome primary/outline buttons, typography, focus, motion, and disabled treatment.

## Behavior details

- Connect and retry use one press. Cancel during startup uses one press.
- Disconnect requires a second press within four seconds, for touch, keyboard, and accessibility alike. Status or availability changes clear confirmation.
- If an error occurs while the service remains running, the action follows the existing STOP dispatch and says Disconnect. Retry is shown only when the dispatch can start the configured mode.
- No stage pipeline appears in this component. Startup reports indeterminate progress, and timer-based stage projection is removed. Unknown stages before a fault remain pending.
- The retired rail source assertion test is removed with the rail. Behavior tests cover swipe cancellation, confirmation, semantics, and adaptive text instead. Golden fixtures are not changed.
