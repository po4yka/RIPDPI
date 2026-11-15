## Context

Mieru and Shadowsocks use the same network-time crate, with different freshness windows and timestamp precision.

## Decisions

- Allocate the clock with the relay factory. Keep bounded correction from validated observations within that instance. Remove endpoint hashing and the recovery quorum.
- Recreate the factory when the relay restarts. Start from the corrected system time. Keep incoming wire validation before calibration and do not retry user traffic.
- Mieru uses a successful in-tunnel reply bound to its CONNECT. Shadowsocks 2022 TCP can use a reply after request-salt binding. UDP still requires session identity and packet window checks.
- One immutable monotonic clock snapshot serves a live UDP association. Use it to retire old windows. An accepted reply can refine the profile clock for future connections.
- Discard observations captured before an accepted observation. Keep one monotonic epoch through read failures and use checked protocol arithmetic.

## Verification

Use regression tests in the owning crates. Run the source gates on the combined tree and review the time, authentication, and replay state transitions. Keep host checks separate from device sleep and external peer acceptance.
