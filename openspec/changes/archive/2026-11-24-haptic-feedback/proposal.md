# Change: Control haptic feedback and deliver actual results once

Task ID: `UIX-1792259264757428`

## Why

App users cannot disable app haptics separately. VPN consent currently signals success while connection is pending, and Detection result rendering can replay feedback. Feedback must describe a completed action and remain quiet when a user opts out.

## What Changes

- Add a persisted System default / Off preference in Full and Simple.
- Emit connection success from the actual result of a current user attempt.
- Deliver Detection results through consumed events instead of result rendering.
- Keep errors, cancellation, restored results and lifecycle changes distinct.
- No breaking change. Missing stored values retain system behavior.

## Capabilities

### New Capabilities

- `ui/haptic-feedback`: persisted user control and once-only actual-result feedback.

### Modified Capabilities

- None.

## Impact

- App settings protobuf and JSON backup receive one additive boolean field.
- App settings UI, startup theme propagation, connection presentation and Detection result delivery change.
- No production dependency, native, wire, service lifecycle or network behavior change.
