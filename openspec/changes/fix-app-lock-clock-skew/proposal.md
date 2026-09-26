# Change: Keep app lock timers stable across clock changes

Task ID: `AND-1790430239664191`

## Why

PIN lockout and automatic app relock use the device wall clock. A person who
changes the clock can bypass the lockout or delay relock while the app is in the
background.

## What Changes

- PIN lockout remains active for its configured delay when wall time jumps.
- An authenticated app relocks after the background grace period despite wall
  time changes.
- Both controls fail closed after a reboot or when boot identity is unavailable.
- No breaking changes.

## Capabilities

### New Capabilities

- `app-lock-clock-integrity`: Wall clock changes cannot weaken PIN lockout or
  automatic app relock.

### Modified Capabilities

- None.

## Impact

- `:app` security timers and focused unit tests. No wire, storage schema,
  dependencies, permissions, or other modules change.
