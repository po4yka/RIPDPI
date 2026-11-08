# Change: Repair Transparent TCP ingress contracts

Task ID: `PRX-1794125405826480`

## Why

The LOC-07 audit found that Transparent ignores the IPv6 destination switch, drops scoped IPv6 address data, and rejects separate local services as self loops. A pending direct connect can also exceed the runtime drain window. These failures make accepted settings and socket ownership unreliable.

## What Changes

- Enforce the existing IPv6 destination setting for Transparent.
- Retain the scope ID returned by Linux original-destination lookup.
- Reject actual listener targets while allowing separate local listeners.
- Cancel pending TCP connect when runtime shutdown begins.
- Keep authentication, address restrictions, raw TCP bytes and platform errors intact.
- No breaking schema or public wire change is planned. Previously ignored IPv6 restrictions become effective.

## Capabilities

### New Capabilities

- `transparent-tcp-ingress`: Destination admission, socket address fidelity and shutdown for raw TCP ingress.

### Modified Capabilities

- None. The shared listener implementation must retain its existing normative authentication and error behavior.

## Impact

- Rust crates: ripdpi-proxy-runtime, ripdpi-proxy-runtime-adapter and ripdpi-privileged-ops.
- No Kotlin, JNI, protobuf, persisted schema, dependency or baseline change.
- System redirect rules and Android device acceptance remain separate deployment evidence.
