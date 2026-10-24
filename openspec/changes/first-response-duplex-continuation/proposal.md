# Change: Keep uploads moving during first-response observation

Task ID: `PRX-1792849175606125`

## Why

With autolearn enabled, the shared proxy sends one client buffer and waits for
the first server response before it starts duplex copying. A server that waits
for a complete POST body cannot respond. A real split-header 1 MiB upload fails
with autolearn enabled and passes with it disabled. Direct native Hysteria
uploads also pass, which isolates the shared proxy stage.

## What Changes

- Forward client continuation while the first response is still pending.
- Preserve response classification, autolearn, exact bytes and bounded memory.
- End reconnect and replay eligibility before unretained continuation can flow.
- Preserve first-payload-only retry behavior. No public schema or CLI changes.

## Capabilities

### New Capabilities

- `proxy-first-response-duplex`: bounded continuation and response observation with no replay after handoff.

### Modified Capabilities

- None. Existing diagnostic evidence and transport contracts remain unchanged.

## Impact

- Shared native proxy relay and portable/io_uring stream copying, with focused runtime regressions.
- No dependency, Hysteria protocol, Android configuration, provider or credential changes.
