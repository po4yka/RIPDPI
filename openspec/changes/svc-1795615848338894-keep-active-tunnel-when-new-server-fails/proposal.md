# Change: Keep the working tunnel when a new server fails to start

Task ID: `SVC-1795615848338894`

## Why

A failed attempt to connect to a new server does not leave the user without the connection they had, and the error tells them what to check.

## What Changes

- When a server switch fails, the service restores the previous working mode, or the UI states that the device is now unprotected and offers to restore it.
- An AmneziaWG readiness timeout shows a user message that names the likely causes (endpoint, keys, port) and a next step; the raw state stays in diagnostics only.
- A start failure while the app is in the background posts a notification.
- Service lifecycle tests cover a failed switch from a running tunnel.

## Capabilities

### New Capabilities

- `svc-1795615848338894-keep-active-tunnel-when-new-server-fails`: Keep the working tunnel when a new server fails to start

### Modified Capabilities

- None.

## Impact

- Portfolio area: `service`.
- Exact code, contracts, and gates are constrained by the linked task and design.
