# Change: Modernize native VLESS REALITY compatibility

Task ID: `TRN-1793300518162274`

## Why

The native client needs a real upstream oracle, modern Xray configuration input, and one admission policy across import, editing, and activation. A rejected update must preserve the last stored profiles and settings.

## What Changes

- Run the production native client against an unmodified, pinned Xray peer on loopback in the existing interop suite.
- Accept flat VLESS settings and the REALITY password alias. Reject ambiguous or unsupported input before persistence.
- Share native VLESS transport, security, flow, XHTTP mode, and UDP admission rules across Kotlin entry points.
- Reject lossy subscription updates and retain previous durable state.
- No wire, JNI, protobuf, or storage schema changes. Previously accepted combinations that cannot run will fail earlier.

## Capabilities

### New Capabilities

- `native-vless-client-admission`: lossless configuration input, shared capabilities, and upstream verification.

### Modified Capabilities

- None.

## Impact

- Native VLESS tests and the existing pinned outbound interop runner.
- Runtime-state parsers and profile validation, subscription update persistence, service admission, and editor validation.
- No new production dependency, server feature, or release artifact.
