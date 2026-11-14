# Change: Preserve DPI-36 packet error boundaries

Task ID: `RST-1794684264866031`

## Why

A failed fake-RST cleanup can leave a TCP socket in repair mode while the runtime continues writing. UDP fragment errors lose their safe fallback boundary across the root helper. The MD5 documentation also claims an unsupported fragment combination.

## What Changes

- Stop a TCP flow when fake-RST cleanup cannot restore the socket.
- Preserve safe UDP fragment fallback through the helper without retry after an uncertain send.
- Correct the documented MD5 scope for IP fragmentation.
- No new packet mode, dependency, public signature, or breaking schema change.

## Capabilities

### New Capabilities

- `packet-send-error-safety`: preserve packet and socket-state error boundaries.

### Modified Capabilities

None.

## Impact

Rust privileged operations, root helper, runtime platform, desync adapter and executor; additive metadata in existing helper error responses; protocol documentation. Kotlin, JNI, protobuf and packet bytes do not change.
