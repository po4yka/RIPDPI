# ADR 0007: Relay-assisted QUICstep rescue after NO_DIRECT_SOLUTION

> Status: accepted (decision spike). Decision date: 2026-06-05. Recommendation: **RESEARCH-ONLY (parked branch).** Do not promote a relay-assisted QUICstep first-flight rescue to an implementation epic now; its viable niche overlaps with the relay fallback that already runs after `NO_DIRECT_SOLUTION`, it is a liability under the dominant generic-QUIC-blocking threat, and its Android costs are not justified by the marginal gain. Keep the go/no-go indicators below so re-evaluation is cheap.

## Context

When direct-mode probing exhausts its strategy budget it emits the `NO_DIRECT_SOLUTION` verdict (`ripdpi-runtime-policy/src/transport_policy.rs`), which `ripdpi-runtime-adaptive` (`strategy_context/direct_path_capability.rs`) consumes to block the direct TCP path while a cooldown is active (`DirectModeNoDirectSolutionCooldownMs` = 30 min, in `core/data/model/.../TransportPolicy.kt`). After that verdict the client already falls back to the **relay stack** (VLESS/REALITY/xHTTP/MASQUE/etc.). The current direct-mode plan deliberately keeps relay-assisted **QUICstep** first-flight hiding *out* of the default no-proxy path.

"QUICstep" is, today, only a research concept (`quicstep-first-flight-hiding`); there is no `quicstep` construct in the workspace. RIPDPI does carry QUIC *desync candidates* in `ripdpi-diagnostics-candidates` (`quic_crypto_split`, `quic_padding_ladder`, `quic_version_negotiation_decoy`, `quic_multi_initial_realistic`), which are direct-mode probes, not a relay bootstrap.

This spike asks the narrow question: should there be a **second-tier rescue** that runs a relay-assisted QUICstep first-flight bootstrap *only after* `NO_DIRECT_SOLUTION`, for controlled infrastructure?

## Acceptable deployment scopes

QUICstep first-flight hiding requires controlling the endpoint the first flight is hidden toward. Therefore the **only** acceptable scopes are:

- **Controlled server** — an exit/relay RIPDPI (or the user) operates.
- **CDN-backed controlled property** — a controlled origin fronted by a CDN, *if* the fronting actually detaches the later path from the censored bootstrap.

**Arbitrary third-party sites are explicitly out of scope** and rejected: you cannot perform first-flight hiding toward infrastructure you do not control, so QUICstep can never be a general direct-mode rescue.

## Go / no-go indicators (from `quicstep-first-flight-hiding`)

QUICstep is only worth it when **all three** hold:

1. **Strong QUIC migration support** on the path — the post-bootstrap connection can migrate and genuinely **detach** from the censored first-flight path. If migration is weak, the "rescue" stays bound to the blocked bootstrap and buys nothing.
2. **First-flight classification is the censor's mechanism** — QUICstep hides the first flight, so it only defeats classifiers that key on it.
3. **Generic QUIC blocking does NOT dominate** — if the operator drops/throttles UDP/443 QUIC broadly (common on RU TSPU and many mobile carriers), QUICstep is dead on arrival.

If generic QUIC blocking dominates *or* migration cannot detach, it is a **no-go**.

## Where it would attach in product flow

**Post-`NO_DIRECT_SOLUTION` remediation only** — never in default transparent mode (that would reopen the direct-mode plan, which this spike must not do). It would sit between the `NO_DIRECT_SOLUTION` verdict and the generic relay fallback, as an optional controlled-infra-only attempt before the standard relay path takes over.

The problem: that slot is **already served**. After `NO_DIRECT_SOLUTION` the relay stack runs anyway, and for the controlled-server / CDN scopes where QUICstep is even applicable, the existing relay transports (REALITY, xHTTP-over-QUIC, MASQUE/H3) already connect through the same controlled infrastructure. So QUICstep's marginal benefit over "the relay fallback we already do" is small, and concentrated in exactly the conditions (controlled infra, QUIC not generically blocked) where the relay path also succeeds.

## Android-specific costs

- **Battery:** a rescue track fires *after* direct already failed — extra QUIC handshake/migration attempts on a path that just lost its strategy budget, repeated against the 30-min cooldown window.
- **Background execution:** another async track to keep alive under Doze/App-Standby, competing with the foreground VPN service's existing work.
- **Socket lifecycle:** every QUICstep socket is a non-loopback outbound and MUST be `VpnService.protect()`-ed before connect (`vpnservice-protect-invariant.md`); more sockets, more protect round-trips, more teardown paths to get right across LMK.
- **Policy interaction:** two fallback mechanisms (QUICstep rescue + the existing relay stack) competing for the post-`NO_DIRECT_SOLUTION` slot adds decision complexity to `runtime-adaptive`'s capability gating and the cooldown logic — for a narrow niche.

## Decision

**RESEARCH-ONLY (parked branch).** Not `promote to implementation epic` (the niche is narrow, overlaps the existing relay fallback, and is defeated by the dominant generic-QUIC-blocking threat; Android costs aren't justified). Not `do not pursue` either — the controlled-infra + first-flight-classifier + strong-migration niche is real and worth revisiting if the threat landscape shifts. Keep it parked; do not reopen the default direct-mode plan.

**Re-open triggers:** (1) field data shows first-flight QUIC classification (not generic QUIC blocking) becoming a dominant mechanism against RIPDPI's user base; (2) a controlled-infra deployment with demonstrably strong QUIC migration/detachment becomes a first-class product surface; (3) the existing relay fallback proves insufficient for controlled-infra reconnection specifically.

## References

- `native/rust/crates/ripdpi-runtime-policy/src/transport_policy.rs` — the `NO_DIRECT_SOLUTION` verdict.
- `native/rust/crates/ripdpi-runtime-adaptive/src/strategy_context/direct_path_capability.rs` — verdict consumption + cooldown gating.
- `core/data/model/src/main/kotlin/com/poyka/ripdpi/data/TransportPolicy.kt` — `DirectModeNoDirectSolutionCooldownMs` (30 min) and the outcome enum.
- `native/rust/crates/ripdpi-diagnostics-candidates/src/candidates/config_builders/quic.rs` — existing QUIC desync candidates (direct-mode probes, not a relay bootstrap).
- `.claude/rules/vpnservice-protect-invariant.md`, `.claude/rules/android-vpn-lifecycle.md` — the protect + lifecycle costs any rescue socket inherits.
- `quicstep-first-flight-hiding`, `ripdpi-android-direct-mode-plan-2026-04-20` — the research notes that scope this niche.
