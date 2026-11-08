# Change: Repair shared listener admission and errors

Task ID: `PRX-1794118808814556`

## Why

LOC-06 found that DNS outlives the handshake, raw input can retry a completed address and HTTP can admit malformed headers or return misleading errors. The settings text and CLI help also misdescribe the listener.

## What Changes

- Cancel DNS on shutdown or the handshake deadline. A late DNS answer SHALL NOT start an upstream connection.
- An address listener MUST resolve a complete domain at most once and reject unusable address types without waiting for payload.
- HTTP admission MUST read credentials only from the valid header section.
- HTTP CONNECT MUST reject whitespace before the field colon with 400.
- HTTP CONNECT MUST return a readable 4xx when fields exceed the 64 KiB limit.
- HTTP CONNECT MUST distinguish local routing denial from an upstream failure.
- The UI MUST describe the effective configured proxy port behavior and preserve an explicit port when enabling Mixed.
- The debug CLI MUST list Mixed, HTTP-only and Transparent in help.

## Capabilities

### New Capabilities

- `shared-proxy-listener`: Admission, status codes and operator controls on the shared proxy port.

### Modified Capabilities

- None.

## Impact

Native proxy runtime, session parser, adapter, command help, Android settings copy and tests. No new dependency, schema or external service.
