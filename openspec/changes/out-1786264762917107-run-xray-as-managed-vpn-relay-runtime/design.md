## Context

`OUT-1786264762917107` completes the existing Android Xray provider path. Durable profile selection, config rendering and TUN-to-local-inbound integration already exist. The current default build uses a throwing bridge; fake tests conceal ignored Go protection errors and unsafe cleanup ownership.

## Goals / Non-Goals

- Run the existing pinned libXray in the VPN service with actual protected sockets, local listener readiness, bounded lifecycle waits and supervised exits.
- Keep non-root operation, offline profile ownership, secret-free telemetry and existing explicit provider-selection generation rules.
- Do not change profile editing, server contracts, dependency pins, physical devices or deployments.

## Decisions

- Keep the existing in-process gomobile runtime. A process-wide single native worker owns one session lease from before the first native side effect through confirmed cleanup. Coroutine cancellation stops waiting, not the native operation. Timed-out or rejected cleanup retains ownership and prohibits a replacement runtime; deferred completion is generation-bound.
- Register one forwarding protection adapter per native bridge, with a revocable current service owner. Revoke on service destruction. Denial, missing owner and callback failure fail closed. Patch both pinned libXray and xray-core where upstream currently discards callback failures; never close a Go-owned descriptor from Kotlin.
- Resolve only the relay endpoint through the existing eligible-underlay service resolver before native start. Preserve implicit TLS/REALITY SNI and XHTTP Host in the transient numeric-address configuration; never modify the durable profile or its traffic DNS policy. The process-owned lane admits one detached lookup with a bounded caller wait; a timed-out lookup retains admission until it returns. Deny any remaining Go system DNS socket before I/O and restore the resolver only after confirmed native stop.
- Probe the configured loopback SOCKS5 listener with bounded I/O before TUN handoff; a process-state boolean alone is insufficient.
- Retain runtime/session ownership on failed stop. Distinguish native ownership from Android service destruction: destruction revokes the service callback while the process owner keeps unresolved cleanup. Observe runtime exit in the existing service telemetry/supervisor path and bind failures to the owning session.
- Report local readiness separately from outbound reachability. Do not infer a healthy remote path from a listening socket or expose config/native error contents in telemetry.
- Build the pinned, patched AAR through the repository script, verify its provenance, API and ABI payloads, and wire real artifacts into shipping CI. Tests may use explicit offline fakes but cannot establish native acceptance.

## Risks / Trade-offs

- A stuck native call cannot safely be killed in-process. The bounded caller returns a pending/failed outcome and retains the single worker/lease; no accumulating threads or unsafe overlap are allowed. Android process death remains the recovery boundary for a permanently hung native call.
- Upstream callbacks are global and append-only. One forwarding adapter and lease ownership prevent stale VpnService references or duplicate callbacks across restart.
- AAR/toolchain downloads and existing repository CI failures may limit validation; neither may be hidden by checksum bypasses, skipped checks or enlarged baselines.

## Migration Plan

Implement in the recorded isolated worktrees with regression RED/GREEN cycles. Integrate native build, runtime and service consumers on one combined tree. Verify actual Android loopback traffic, stop/rebind/restart, denial and failure paths. Run local gates and exact-SHA hosted CI; archive only when required evidence is complete.
