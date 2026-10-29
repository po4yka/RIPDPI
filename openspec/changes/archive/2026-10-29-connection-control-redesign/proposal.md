# Change: Make connection controls clear and accessible

Task ID: `UIX-1791532861060606`

## Why

The home actuator makes route information look like an action. The action label can disappear in connection and error states. The displayed connection stages use elapsed time to mark prior stages complete. Users need clear actions and status that follows evidence.

The user requested Mobbin MCP research before the redesign. The integration is now connected. The inspected NordVPN widgets and Opera VPN panel support separate action, status, and route areas. See design.md for source links and the selected layout.

## What Changes

- Separate the primary connection action from read-only route information.
- Keep the current action visible in all states, including cancel and retry.
- Show connection progress only when runtime evidence supports it.
- Preserve keyboard, accessibility, large-text, RTL, and lockdown support.
- Record the actual Mobbin references before selecting the final layout.
- No schema, JNI, wire, or service lifecycle changes are planned. UI automation may need updates for the new action layout.

## Capabilities

### New Capabilities

- `home-connection-controls`: clear action and route presentation on the home screen.

### Modified Capabilities

- None. Existing connection service behavior is preserved.

## Impact

- `:app`: connection component, UI state projection, resources, tests, and previews.
- All ten locales for any changed user-facing resource.
- Home screenshots may need a separately authorized golden update after actual renders are reviewed.
- No new production dependency or external data transfer from the app.
