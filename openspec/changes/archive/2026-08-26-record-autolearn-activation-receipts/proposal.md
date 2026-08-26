# Change: Record authoritative Autolearn activation receipts

Task ID: `DGN-1787230878672684`

## Why

A successful proxy start already obtains an authoritative native telemetry snapshot after readiness, but the snapshot is discarded after resolving the local listener. The service can therefore publish `Connected` while the shared telemetry store still contains its `NoData` idle placeholder. Diagnostics captured in that interval cannot distinguish persisted settings, the resolved session request, and the effective native Autolearn state.

Future diagnostics need same-runtime evidence that states what the user persisted, what policy resolution requested, and what the ready native runtime actually activated. The evidence must survive short sessions and remain privacy-safe.

## What Changes

- Publish the authoritative proxy telemetry snapshot obtained after native readiness before the service becomes `Connected`.
- Record one durable, privacy-safe Autolearn activation receipt for every successful initial start and runtime replacement, correlated by runtime ID, mode, policy signature, and capture time.
- Report persisted, resolved/requested, and native-effective enabled states together with the resolution source and a coarse activation outcome.
- Preserve explicit `NoData` or `EngineError` semantics when an authoritative effective snapshot is unavailable; never synthesize `disabled` from an idle placeholder.
- Include the receipt in existing native-session event exports without exposing host names, store paths, raw network identifiers, or command-line contents.
- No breaking public wire, JNI, protobuf, Room, or archive-schema change is intended; the receipt uses the existing native-session event envelope.

## Capabilities

### New Capabilities

- `diagnostics/autolearn-activation-evidence`: Authoritative, runtime-correlated evidence for configured, resolved, and effective Autolearn activation.

### Modified Capabilities

- None.

## Impact

- `core/service`: proxy/VPN startup orchestration, telemetry publication, policy-source classification, and durable receipt recording.
- `core/diagnostics-data`: existing artifact-store interface and native-session event envelope are consumed without schema expansion.
- `core/diagnostics`: archive/event export and regression coverage must prove receipt retention and privacy-safe rendering.
- Service startup ordering changes so the first `Connected` observation has an authoritative proxy snapshot.
