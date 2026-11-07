# Change: Use the official OkHttp coroutine adapter

Task ID: `DGN-1794036571954184`

## Why

HTTP probes must cancel calls and release responses when cancellation wins after the callback but before coroutine delivery. The 2026-11-07 audit identifies this as finding 4 at source revision 202f970.

## What Changes

- Use okhttp-coroutines Call.executeAsync with response.use in the repeated probe adapters. Keep response-body processing on an appropriate IO dispatcher.
- A successful probe consumes and closes its response while keeping its existing payload verdict.
- Cancellation after the callback but before continuation delivery closes the undelivered response and cancels the call.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep call deadlines, EventListener, status and payload checks, socket routing, and cancellation propagation. Do not replace specialized probe payloads.

## Capabilities

### New Capabilities

- `standard-components/use-official-okhttp-coroutine-adapter`: HTTP probes must cancel calls and release responses when cancellation wins after the callback but before coroutine delivery.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/replay/DefaultProbeReplayService.kt`
- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/StrategyProbeService.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/RelayCapabilityProbe.kt`
- `core/service/src/main/kotlin/com/poyka/ripdpi/services/QualityHttpPayloadProbe.kt`

Add the official okhttp-coroutines module at the version pinned for OkHttp in gradle/libs.versions.toml. Verify publication, licenses, transitive dependencies, and Android compatibility. One writer owns the version catalog and affected build files. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
