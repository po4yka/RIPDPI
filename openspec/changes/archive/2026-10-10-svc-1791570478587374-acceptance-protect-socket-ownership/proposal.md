# Change: Preserve protect socket ownership across VPN session overlap

Task ID: `SVC-1791570478587374`

## Why

A clean Android acceptance run returned empty TCP EOF and a UDP timeout after VPN restart. The old protect server completed cleanup after the new server had bound the same filesystem socket path. Current teardown deletes that path without checking ownership. This can remove the new session endpoint and prevent native descriptor protection. The source defect is concrete; its exact relation to the historical failure remains to be proved.

## What Changes

- An old VPN session must not remove the active session protect endpoint.
- Startup, failure cleanup, repeated stop, and overlapping session cleanup preserve endpoint ownership.
- Descriptor protection remains required and fails closed on errors.
- No breaking wire, JNI, configuration, or storage change is planned.

## Capabilities

### New Capabilities

- `protect-socket-ownership`: protect endpoint ownership across overlapping service sessions.

### Modified Capabilities

- None.

## Impact

- `:core:service` protect server and session lifecycle, their tests, and actual Android acceptance evidence.
- Native consumers retain the socket path and one-byte ACK contract.
- No new production dependency, public schema, root requirement, or endpoint is planned.
