---
title: Replace generic relay suggestion with transport-specific remediation ladder
type: task
status: done
area: diagnostics
priority: high
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-22
updated: 2026-05-16
---

- [x] #task Replace generic relay suggestion with transport-specific remediation ladder #repo/RIPDPI #area/diagnostics #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `replace-generic-relay-suggestion-with-transport-specific-remediation-ladder`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Replace the current one-size-fits-all "Russian mobile relay preset"
recommendation with a remediation ladder that chooses between owned-stack,
browser-camouflage relay, QUIC-heavy relay, or "no useful relay hint" based on
direct-mode verdicts plus saved capability evidence.

## Context

RIPDPI already has relay suggestion plumbing in `ConfigRelaySupport.kt` and
capability-aware preset reasons in `RelayPresetCatalog.kt`, but the runtime
message is still generic: whitelist pressure maps to one Russian mobile relay
preset.

Today's research notes make that too coarse:

- [[whitelist-oriented-censorship-resilience-2026]] shows whitelist pressure is
an escalation ladder, not one binary state.
- [[naiveproxy-vs-hysteria2-russia-2026]] separates browser-camouflage fallback
from QUIC/system-wide fallback.
- [[orthogonal-fallback-portfolio-2026]] argues these branches should not be
collapsed into one "relay mode".

The user-facing action after a failed direct-mode run should therefore be:
"open in RIPDPI browser", "prefer NaiveProxy", "prefer Hysteria2/TUIC/MASQUE",
or "direct path unavailable and no reliable relay hint yet" rather than one
generic fallback sentence.

## Current landing status

As of 2026-04-23, the first product slice is landed in
`/Users/po4yka/GitRep/RIPDPI`:

- Diagnostics and Home now project typed direct-mode verdict metadata into a
shared transport-remediation selector.
- The remediation ladder can now branch to owned-stack browser,
browser-camouflage relay, QUIC-heavy relay, or "no reliable relay hint"
instead of collapsing every negative direct-mode result into a generic relay
fallback.
- Home also consumes saved authority capability evidence when choosing between
browser-camouflage and QUIC-heavy relay guidance.
- Mode Editor is now wired as the relay handoff action from both surfaces.

The remaining work is config-side unification and taxonomy completion:
`ConfigRelaySupport.kt` still uses its older preset-suggestion heuristic rather
than the same selector, and the distinct `DOMESTIC_DIRECT_RELAY_FOREIGN` branch
is still implicit in preset heuristics instead of being surfaced as its own
remediation class.

## Acceptance criteria

- [x] A shared remediation model maps `DiagnosticResult + TransportClass +
    saved capability evidence` to a specific action class instead of one
    generic relay suggestion.
- [ ] The ladder distinguishes at least:
    `OWNED_STACK_ACTION`, `BROWSER_FALLBACK`, `QUIC_FALLBACK`,
    `DOMESTIC_DIRECT_RELAY_FOREIGN`, and `NO_RELIABLE_RELAY_HINT`.
- [ ] Diagnostics UI and config relay suggestions use the same remediation
    model, so users do not see contradictory recommendations.
- [ ] When saved evidence shows `quicUsable == false` or HTTPS proxying is the
    safer path, the recommendation prefers a browser-camouflage branch such
    as NaiveProxy over QUIC-heavy presets.
- [x] When saved evidence shows QUIC/UDP relay paths are healthy, the
    recommendation prefers the QUIC-heavy branch rather than the generic
    Russian mobile relay fallback.
- [x] Focused unit/UI tests cover the owned-stack branch, browser fallback,
    QUIC fallback, and no-supported-relay-hint branch.

## Notes

Keep the existing three direct-mode result classes. This task is about
remediation above the verdict, not about exploding `DiagnosticResult` itself.

## Links

- [[Epic - Direct-mode diagnostic state machine]]
- [[Report OWNED_STACK_ONLY verdict from diagnostic]]
- [[naiveproxy-vs-hysteria2-russia-2026]]
- [[orthogonal-fallback-portfolio-2026]]
- [[whitelist-oriented-censorship-resilience-2026]]
