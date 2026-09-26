## Context

The two `:app` timers use `System.currentTimeMillis()`. Their wall-clock
deadlines can move when the user edits system time. See `proposal.md`.

## Goals / Non-Goals

- Goal: Base active timer decisions on `SystemClock.elapsedRealtime()`.
- Non-goal: Change PIN policy, grace duration, or biometric behavior.

## Decisions

- Keep the app relock timer in the existing observer. Its authenticated state
  is process-local, so reboot starts unauthenticated. Treat negative elapsed
  time as requiring relock.
- Persist the PIN lockout monotonic deadline with a boot count. On a different
  or unknown boot, start a fresh configured delay from the retained failure
  count. This may extend a lockout after reboot but does not shorten it.
- Migrate an active legacy wall-clock lockout to a fresh monotonic delay once.
  The old deadline is not a safe basis for remaining time after clock changes.

## Contracts and ownership

- `:app` owns both classes and their tests. No Rust crate, JNI, wire, or public
  contract changes. `PinLockoutManager` adds private preference keys; the
  existing backup deny-all rules cover them. No serialized shared files change.

## Risks / Trade-offs

- An unknown boot count or upgrade may extend an active lockout. The app
  remains usable after the configured delay in a stable process.
- Test injected clocks and boot counts; run focused unit tests, `:app` unit
  tests, and `staticAnalysis`. Device boot behavior remains to be checked on
  an emulator or device.

## Migration Plan

On first load, convert a legacy active PIN lockout into a full monotonic
delay. Existing PIN hashes and failure counts stay intact. Rollback restores
the old timer code but does not remove the new private keys.
