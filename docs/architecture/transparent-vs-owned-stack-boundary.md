# Transparent vs Owned-Stack Mode Boundary

## Overview

RIPDPI operates in two distinct product modes that must never be conflated:

| Dimension | Transparent mode | Owned-stack mode |
|-----------|-----------------|------------------|
| Traffic source | Arbitrary third-party apps | Our own browser / SDK |
| Network layer | TUN interface | platform `HttpEngine` plus native owned-TLS fallback |
| Socket protection | `VpnService.protect()` on every upstream socket | Not used |
| ECH availability | Not available | Available |
| DNS path | System or DoH resolver | ECH-capable resolver |

## Type-level boundary

The boundary is encoded as a sealed class:

```kotlin
// com.poyka.ripdpi.diagnostics.shared.TransportMode
sealed class TransportMode {
    data object Transparent : TransportMode()
    data object OwnedStack  : TransportMode()
}
```

The `sealed` modifier guarantees that every `when` expression over `TransportMode` is **exhaustive** at compile time. Adding a new variant without updating all `when` branches is a compile error, not a runtime surprise.

## Per-mode invariants

### Transparent mode

- All upstream sockets must be protected via `VpnService.protect()`.
- Owned-stack HTTP client code must not be invoked.
- Probe arms that run under transparent mode must not reference `OwnedStack`-specific types.

### Owned-stack mode

- `VpnService.protect()` is not called; the SDK owns the full stack.
- ECH handshake negotiation is permitted and may be required (`requiresEch = true`).
- Probe arms that run under owned-stack mode must not reference `Transparent`-specific TUN types.

## Mode-selection rule

```
if (policy.requiresEch) → OwnedStack
else                     → Transparent
```

This rule is encoded in `TransportPolicyStub.resolveMode()` and is the single authoritative place where mode is chosen. No other code may branch on ECH availability to select a mode.

## Shared neutral types

Types consumed by **both** arms live in `com.poyka.ripdpi.diagnostics.shared` and must:

- Carry **no** platform HTTP (`HttpEngine`) or `VpnService` imports.
- Not reference mode-specific probe infrastructure.

| Type | Purpose |
|------|---------|
| `DnsClassification` | DNS probe outcome taxonomy (CLEAN, POISONED, ECH_CAPABLE, …) |
| `TransportPolicyStub` | Minimal policy descriptor; owns `resolveMode()` selection rule |
| `ArmStats` | Success/failure counters for any diagnostic arm |

## Boundary enforcement mechanisms

1. **Sealed type + exhaustive when** — compiler rejects unhandled variants.
2. **`internal` modifiers** — mode-specific internals are not visible outside their own package.
3. **`TransportModeBoundaryTest`** — unit test suite that verifies: - Both variants exist and are distinct. - `when` exhaustiveness holds. - Owned-stack policy never resolves to `Transparent`. - Shared types reside in `*.diagnostics.shared`. - Shared source files contain no platform HTTP or `VpnService` imports.

## Relationship to the diagnostic state machine

The diagnostic orchestrator (see `epic-direct-mode-diagnostic-state-machine`) selects a mode before dispatching probe arms. Once a mode is selected, only arms compatible with that mode are eligible for dispatch. This is enforced by passing a `TransportMode` token into the arm factory; arms are typed to accept only `TransportMode.Transparent` or only `TransportMode.OwnedStack`.
