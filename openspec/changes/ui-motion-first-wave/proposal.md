# Change: Add clear connection and diagnostics feedback

Task ID: `UIX-1792222949017657`

## Why

The disconnect confirmation does not show when its window expires. Diagnostics results replace active content without a clear transition. Readers of old events have no direct return to recent events. The shared spring helper also returns timed motion when animations are disabled.

## What Changes

- Settle shared spring consumers immediately when animations are disabled.
- Show the real remaining disconnect confirmation window.
- Change active diagnostics into the actual terminal result with one short transition.
- Offer Jump to latest without moving a reader when new events arrive.
- No breaking changes.

## Capabilities

### New Capabilities

- `ui-action-feedback`: accessible feedback for action deadlines, diagnostics completion, and recent events.

### Modified Capabilities

- None.

## Impact

- App Compose components, theme motion, diagnostics and history screens, localized labels, and focused tests.
- No new dependencies or runtime, network, JNI, protobuf, or storage contracts.
