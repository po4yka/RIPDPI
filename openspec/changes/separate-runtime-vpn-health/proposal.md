# Change: Separate runtime readiness from VPN connectivity

Task ID: `SVC-1786597927063162`

## Why

The service lifecycle reports `AppStatus.Running` after local proxy and tunnel processes start. Home currently converts that lifecycle fact directly into a connected presentation, even when captured Android path evidence says the VPN path is absent or unvalidated. Users therefore cannot distinguish a locally running runtime from a VPN data plane that has actually been validated.

## What Changes

- Preserve `AppStatus.Running` as the local runtime lifecycle state used for service control.
- Add a separate Home projection for VPN data-plane validation with working, checking, unavailable, and not-applicable states.
- Prevent the Home actuator from presenting a locked working VPN when captured evidence disproves VPN connectivity.
- Keep proxy mode and unavailable evidence epistemically neutral.

## Capabilities

### New Capabilities

- `runtime-vpn-health-projection`: Projects local runtime readiness and VPN data-plane validation as independent user-visible facts.

### Modified Capabilities

- None.

## Impact

- Affects the Android Home state projection and connection actuator presentation in `:app`.
- Consumes the existing privacy-safe `NetworkPathValidationEvidence` contract without changing JNI, protobuf, persistence, or native schemas.
- Adds localized user-facing status copy and regression coverage.
