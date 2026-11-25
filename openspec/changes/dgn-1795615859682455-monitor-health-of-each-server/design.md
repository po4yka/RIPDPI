## Context

Portfolio task `DGN-1795615859682455` owns this change. The user can check whether each server works and see how the connected server performs.

Before this change, the Profiles screen measured relay, Xray, and selector-member rows with an HTTP check through a candidate relay, but AmneziaWG rows always read "Not checked". The Status screen showed traffic and a generic RTT from the connection-quality window, with no server attribution and no server latency. The AmneziaWG telemetry never reported an RTT. Diagnostics events with a relay profile token showed only the source and message.

## Goals / Non-Goals

- Goal: deliver `Monitor the health of each server` with observable evidence, using the existing probe and telemetry paths.
- Non-goal: a new monitoring subsystem, periodic background checks, or persisted per-server history.
- Non-goal: a check for the WebSocket carrier of AmneziaWG. The native endpoint probe speaks UDP only; WS-carrier rows report "cannot be checked here".

## Decisions

- AmneziaWG check reuses the WARP endpoint probe. `ripdpi-warp-core::probe_endpoint_with_platform` already sends an AmneziaWG-obfuscated WireGuard handshake initiation with the preshared key and returns the handshake RTT. Only a handshake answer counts as reachable, so a silent UDP port never passes. `AwgEndpointCheck` (`:core:service`) maps a saved `AwgActivationRequest` onto `WarpEndpointProbeNativeRequest` and takes the same `ActiveProtectSocketPathProvider.captureWarpProbe()` lease as the WARP scanner, so the probe socket follows the `VpnService.protect` invariant. The check never starts a tunnel and never changes the selection.
- Wire contract: `RipDpiWarpAmneziaConfig` gains `v3` with `@EncodeDefault(NEVER)`. Rust `WarpAmneziaConfig.v3` already exists with `#[serde(default)]`, so WARP config JSON is unchanged when `v3` is unset, and AWG 3.x profiles probe with their real header protection. No proto or schema version changes.
- Server latency on Status comes from runtime telemetry: the active egress snapshot (`relayTelemetry`, `awgTelemetry`, `warpTelemetry`) `upstreamRttMs`. The AmneziaWG runtime now reports boringtun's last handshake RTT (`Tunn::stats().4`) as `upstreamRttMs`; the field is skipped in JSON when unknown, so older decoders and goldens see no change. Kotlin `NativeRuntimeSnapshot.upstreamRttMs` already existed.
- Status presentation: when a server is connected, the measurement strip adds the server name, its latency, and the session uptime next to the existing traffic total. The server name is the existing `ActiveExit` name, which already avoids hosts and addresses.
- Diagnostics events: relay health-decision events already carry `relayProfileToken = sha256(profileId)`. `DiagnosticEvent` keeps that token as a `@Transient` field, and the app resolves it on device against saved selector members and AmneziaWG profiles (`ServerNameDirectory`). The watch list and event rows then show the server name next to the source. Names are never written to the database.
- Privacy: exports are unchanged. The archive redactor already projects `relayProfileToken` to a stable correlator and the CSV builder aliases it (`profile-N`). Because the name lives only in the transient UI model and `DiagnosticEvent` serializes without it, no server name, host, or key reaches an export. A test asserts that the archive projection of an event with a token never contains the server name.

## Risks / Trade-offs

- A server that answers the handshake can still fail to carry traffic (routing, MTU). The row text says "Handshake", not "Works".
- Handshake RTT on Status updates only on rekey (about every two minutes) for AmneziaWG. It is the last measured value, not a live sample.
- While an AmneziaWG check runs with no VPN session, a VPN start fails with the existing "endpoint check is still running" error, exactly as during a WARP scan. The 5 s probe timeout bounds that window.
- Standalone relay profiles without a selector member have no saved display name, so their events stay unnamed, matching the `ActiveExit` rule that never names a server by its host.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
