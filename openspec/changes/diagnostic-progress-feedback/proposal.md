# Change: Smooth diagnostic progress and clarify phase changes

Task ID: `UIX-1792259259850628`

## Why

Measured progress jumps between snapshots. Phase chips change without finite
feedback and do not expose an explicit localized phase state. Frame animation
must not make TalkBack announce intermediate or repeated values.

## What Changes

- Smooth forward measured progress in one scan and keep resets honest.
- Give each phase stable accessible state and restrained visual feedback.
- Separate current-value semantics from animation and phase announcements.
- Verify real TalkBack, disabled animations, RTL and large text on an emulator.
- No breaking change.

## Capabilities

### New Capabilities

- `ui/diagnostic-progress-feedback`: honest measured motion and accessible phase feedback.

### Modified Capabilities

- None.

## Impact

- App presentation, local/device tests and task evidence only. No production
  dependency, service, native, JNI, protobuf or storage change is planned.
