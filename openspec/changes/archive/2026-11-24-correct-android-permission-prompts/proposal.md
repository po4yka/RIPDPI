# Change: Correct Android permission prompts and recovery

Task ID: `AND-1795489914228572`

## Why

Some actions open Android permission or settings screens before the app explains
the request. Some declines go to the wrong recovery action.

## What Changes

- Explain consent and settings actions before the user leaves the app.
- Preserve the action that asked for VPN consent.
- Handle closed dialogs, partial grants, repeatable refusals, and settings return.
- Keep features that work without the grant available.

## Capabilities

### New Capabilities

- `android-permission-prompts`: In-app explanations and recovery from Android permission requests.

### Modified Capabilities

- None.

## Impact

- Android app permission UI, update and boot settings, and the detection permission planner.
