# Change: Repair Telegram WebSocket transport contracts

Task ID: `TRN-1794494451740063`

## Why

The TUN-06 audit found seven defects in Worker binary handling, test-DC routing, init classification and completion, bootstrap cancellation, and credential publication. These defects can reject valid sessions or retain inconsistent session state.

## What Changes

- Fix each audit finding F1 through F7 in a separate code commit.
- Fix F8, the Worker close-code defect confirmed while checking the audit's lifetime hypothesis, in a separate commit.
- Preserve binary data on current Worker runtimes and accept canonical production and test paths.
- Use an exact direct destination as the DC authority, complete partial init data, and reject a forbidden init prefix.
- Cancel every runtime bootstrap path when the runtime stops.
- Publish Worker settings and credentials without a partial durable state.
- No wire format or public configuration schema change is planned. No new transport mode is added.

## Capabilities

### New Capabilities

- `telegram-ws-session-contract`: define the repaired init, route, shutdown, and credential behavior.

### Modified Capabilities

- None. The new focused contract supplements the existing optional Worker specification.

## Impact

- Rust WS tunnel, proxy runtime, and their adapters and tests.
- Reference Worker script, local tests, and operator guide.
- Kotlin Worker credential provisioning and tests.
- No production dependency, locale, golden, or external deployment change.
