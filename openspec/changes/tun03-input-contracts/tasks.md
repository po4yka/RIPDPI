# TRN-1794384012167321

## Objective

Preserve supported AWG profiles and reject malformed or unsupported input before activation.

## Ownership

URI worker owns AmneziaWgUriCodec.kt and its core/data and app import regressions. Key worker owns AmneziaWgV3Parameters.kt and model tests. Compatibility worker owns AwgProfileRepository.kt and Room tests, then AwgActivationRequest.kt and AmneziaWgRuntimeConfigResolver.kt with their tests. The coordinator owns planning, combined validation, review, and integration.

## Execution

- [ ] TRN-1794384205371297 Preserve IPv6 hosts through AWG import #bug !high @item:TRN-1794384012167321
- [ ] TRN-1794384209766294 Validate canonical header protection keys #bug !high @item:TRN-1794384012167321
- [ ] TRN-1794384211583615 Reject malformed and duplicate URI query fields #bug !high @item:TRN-1794384012167321
- [ ] TRN-1794384214181311 Preserve incompatible nested wire settings #bug !high @item:TRN-1794384012167321
- [ ] TRN-1794384216356554 Reject unsupported carrier tokens #bug !high @item:TRN-1794384012167321
- [ ] TRN-1794384218607663 Review and validate the combined changes #bug !high @item:TRN-1794384012167321

## Verification

Run affected model, runtime-state, data Room, service resolver, and app import unit tests; Kotlin static analysis; just ci-gates; task validation; architecture health; and locked Cargo metadata. Independent read-only review must pass before behavior commits. Device, binary artifact, and deployment acceptance are outside this Kotlin boundary repair.
