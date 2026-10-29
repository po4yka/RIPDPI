# Change: Retire the traffic-shape VoIP camouflage spec

Task ID: `RST-1793267609889013`

## Why

The normative spec `add-constant-rate-traffic-shaping-voip-camouflage` requires the `ripdpi-traffic-shape` crate and Kotlin `TrafficShapeConfig`. Both were deleted (dd81a2831183d69bf0280e4d8ac7a7e85dbf85b9 and 6cba60ff66e8bac21b323084d4304351072452e6). No consumer existed. Both endpoints must implement the codec, and no server peer exists; the archived design made activation for existing relays a non-goal.

## What Changes

- Remove all five requirements of the capability `add-constant-rate-traffic-shaping-voip-camouflage`. The archive sync deletes the normative spec.

## Capabilities

### Removed Capabilities

- `add-constant-rate-traffic-shaping-voip-camouflage`

## Impact

Specification only. No code, wire, storage or JNI contract changes: the code is already gone.
