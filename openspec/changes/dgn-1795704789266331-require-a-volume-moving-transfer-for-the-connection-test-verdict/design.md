## Context

Portfolio task `DGN-1795704789266331` owns this change. A connection test that reports Passed has moved a payload larger than the TCP-freeze threshold and checked it byte-exact, not only a handshake or a small request.

## Goals / Non-Goals

- Goal: deliver `Require a volume-moving transfer for the connection test verdict` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Threshold: `TcpFreezeThresholdBytes = 16_384` is defined once in `PayloadFailoverPolicy.kt`. It equals the `freezeThresholdBytes` value the diagnostics fixtures already use, and sits above the 512-byte runtime `freeze_min_bytes`. Every operator payload control is 32 KiB to 1 MiB (`MinPayloadBytes`), so any published control exceeds the threshold; the test picks the largest control above it.
- Policy lookup: the active group id comes from `AppliedRuntimeConfigurationSource` (`effectiveSelection.selectorGroupId` of the running configuration for the selected mode). The policy is `ProxyGroupRepository.list()` -> group `failover.payloadPolicy`, the same sources the selector prober uses.
- Transfer engine: the onboarding runner calls `CandidateHttpPayloadProbe.probeControl`, which runs the existing strict `OkHttpRelayTcpProbe` and `consumeVerifiedPayload` (exact length and SHA-256). No second downloader is added. `consumeVerifiedPayload` gains an optional byte callback, and the probe result and `CandidateHttpPayloadEvidence` gain `receivedBytes`, the count where the transfer stopped. Existing callers keep their defaults.
- Route: the probe goes through the same SOCKS route as the connectivity probe (VPN tunnel route or the proxy endpoint). The only URL contacted is the control URL, so no third-party host is added.
- Verdict model: the onboarding test is the only connection test in the code base, so no saved-server check shares it. `OnboardingValidationResult.Success` carries `volumeTested`. A failure uses the new `OnboardingValidationStep.Volume` and `transferredBytes`.
- Volume not tested: no group, no policy, no control above the threshold, or `noDomain` (the SOCKS endpoint then cannot resolve the control host) gives Success with `volumeTested = false`. The summary row shows a distinct string, not Passed.
- Strings: two new onboarding strings are added to all 10 locales (`onboarding_validation_failed_volume`, `onboarding_done_test_volume_untested`).
- Progress UI: the Volume step shares the Connectivity stage in the scene and signal line, so progress geometry and screenshots do not change.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
