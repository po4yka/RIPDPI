# Change: Support AWG 3.1 header ranges and signature packets

Task ID: `TRN-1792776081374051`

## Why

The client accepts scalar headers and fixed hexadecimal junk packets. It cannot use the header ranges and signature programs available in the reviewed AWG 3.1 source candidate.

## What Changes

- BREAKING: AWG headers use canonical decimal strings. The explicit wire profile is `awg-3.1-ranges-v1`. Signature packets use the documented tag grammar.
- Import, store, share and activate the profile without dropping its parameters.
- Generate fresh signature packets and select a header from each range while keeping existing Noise and cookie authentication.
- Keep S3 and S4 zero. Other optional AWG 3 packet modes are outside this profile.

## Capabilities

### New Capabilities

- `transport/awg-range-signatures`: validated range headers and generated signature packets across client contracts and the native data plane.

### Modified Capabilities

- None.

## Impact

- Kotlin data, engine API, service activation and AWG editor; native warp core and AWG interop fixtures.
- Deployment contract mirrors and cohort fingerprints change with the producer. No new production dependency is required.
