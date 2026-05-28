# Xray VPN Provider Architecture

**Created:** 2026-05-16 **Epic:** [[Epic - Xray provider mode]] **Plan reference:** [[ripdpi-android-xray-provider-plan-2026-04-24]] **Status:** model-only design — `:core:data:runtime-state` contains the provider enums/topology model, while the libXray adapter, local-inbound bridge, profile renderer, telemetry, and UX remain backlog work under `docs/tasks/issues/epic-xray-provider-mode.md`.

---

## 1. Provider Kinds

`VpnProviderKind` (enum, `:core:data:runtime-state`) reserves the two provider kinds:

| Variant | Description |
|---------|-------------|
| `Native` | Existing RIPDPI Rust/WireGuard provider; managed directly by `:core:service`. |
| `Xray` | Planned embedded Xray-core provider; adapter/runtime work is not implemented yet. |

Both variants are governed by the same `VpnProviderState` machine in the model. `:core:service` does not yet select or run the Xray provider.

---

## 2. State Transitions

```
Stopped ──► Starting ──► Running ──► Stopping ──► Stopped
                │                                   ▲
                └───────────────────────────────────┘
                         (abort / setup error)
```

`VpnProviderState.canTransitionTo(next)` encodes the valid edges:

| From | To | Valid |
|------|----|-------|
| `Stopped` | `Starting` | yes |
| `Starting` | `Running` | yes |
| `Starting` | `Stopped` | yes (abort) |
| `Running` | `Stopping` | yes |
| `Stopping` | `Stopped` | yes |
| any | any other | no |

State is owned by the service layer. The provider adapter signals readiness or failure; it does not mutate state directly.

---

## 3. Tunnel Topology Decision

### 3.1 TunToLocalInbound (chosen default)

```
Android VpnService
      │  TUN fd (owned by native runner)
      │
  PacketReader ──► loopback:10808 ──► Xray inbound ──► Xray outbound ──► protected socket ──► Internet
```

The native runner reads IP packets from the TUN fd and forwards them to a local Xray SOCKS/HTTP inbound on `127.0.0.1:10808`. Xray-core processes and routes them, emitting traffic through sockets protected by `VpnService.protect()` in the existing socket-protection path.

**Tradeoffs:**

| | | |-|-| | Pro | No libXray ABI surface for fd hand-off; survives Xray version upgrades. | | Pro | Socket protection stays in one place (`:core:service` `SocketProtector`). | | Pro | DNS traffic naturally passes through the native DNS-loop guard. | | Con | One extra loopback copy per packet vs. direct fd hand-off. | | Con | Requires a live local inbound listener; port conflicts must be handled at startup. |

### 3.2 LibXraySetTunFd (rejected for now)

The TUN fd is passed directly to `libXray.SetTunFd(fd)`. Xray-core owns the packet loop.

**Rejected because:**
- Tightly coupled to the Go-bridge ABI (`github.com/xtls/libxray`), which changes across Xray releases.
- Socket protection requires a `ProtectCallback` bridge into `VpnService.protect()`, introducing a second JNI call path.
- DNS traffic emitted by Xray bypasses the native DNS-loop guard unless explicitly excluded via an Xray routing rule; easy to mis-configure.

Revisit when loopback overhead is measurably significant (> 5 % CPU or > 2 ms p99 latency regression) on a mid-range device.

---

## 4. Module Boundaries

| Module | Owner | Role |
|--------|-------|------|
| `:core:data:runtime-state` | data layer | `VpnProviderKind`, `VpnProviderState`, `XrayTunnelTopology`, `XrayProviderConfig` typed model. No Android deps; pure Kotlin. |
| `:core:service` | service layer | Android `VpnService` lifecycle; owns the TUN fd, socket protector, and DNS-loop guard. Xray-provider branching is not wired yet. |
| `:core:engine` | engine layer | Planned home for the Xray adapter interface + generated binding. Not present yet. |
| Xray adapter | engine layer | Planned wrapper around `libXray` Go-bridge JNI calls. Not present yet. |

Intended dependency direction for the future runtime is `:core:service` → `:core:engine` (interface only) → `libXray` JNI. The current implemented artifact is the `:core:data:runtime-state` model.

---

## 5. Cross-Cutting Semantics

### 5.1 Socket Protection

All sockets opened by Xray-core must be protected via `VpnService.protect()` to avoid routing loops. In the `TunToLocalInbound` topology this is automatic: Xray uses standard JVM sockets that can be protected at connect time by a registered `SocketFactory` wrapper. The adapter registers this wrapper before starting the local inbound.

### 5.2 DNS-Loop Avoidance

The native DNS-loop guard intercepts DNS queries before they re-enter the TUN and forwards them through the protected upstream resolver. In `TunToLocalInbound`, packets arriving at the local inbound are already past the TUN write path; Xray's outbound DNS queries go through protected sockets and are therefore loop-safe by construction.

If `LibXraySetTunFd` is adopted in a future revision, an explicit Xray routing rule (`geosite:private` or `ip:198.18.0.0/15` to direct) must be added to prevent DNS recursion.

### 5.3 Telemetry

The Xray adapter is expected to expose a future `XrayRuntimeSnapshot` analogous to `NativeRuntimeSnapshot`. `ServiceTelemetrySnapshot` gains an Xray field only when the adapter lands. Telemetry remains pull-only: `:core:service` should poll the adapter on a fixed cadence; the adapter should not push.

### 5.4 Readiness

The future adapter should signal readiness through a `StateFlow<XrayAdapterState>` (Stopped / Starting / Ready / Failed). `:core:service` should wait for `Ready` before marking `VpnProviderState.Running`. Timeout is 10 s; on expiry the service transitions `Starting → Stopped` (abort edge).

### 5.5 Stop Semantics

On `VpnProviderState.Running → Stopping`:
1. `:core:service` signals the future adapter to stop.
2. Adapter drains in-flight packets (best-effort, 2 s deadline).
3. Adapter closes the local inbound listener.
4. Adapter transitions to `Stopped`; `:core:service` transitions `Stopping → Stopped`.

Stop is idempotent: calling stop on an already-stopped adapter is a no-op.

---

## 6. Implementation Task Order

1. **This task** — typed model in `:core:data:runtime-state` (done).
2. `bridge-tun-traffic-through-xray-local-inbound` — wire the local inbound forwarding path in `:core:service`.
3. Xray adapter in `:core:engine` (future task).
4. `XrayRuntimeSnapshot` + telemetry integration (future task).

---

## 7. Non-Goals

- No live server endpoints, credentials, or sample Xray configs are stored in this document or in any file under `:core:data:runtime-state`.
- Protocol-level Xray config generation is out of scope for the architecture layer; it belongs in the adapter.
