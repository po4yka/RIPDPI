## Context

Portfolio task `DGN-1795704789266331` owns this change. A connection test that reports Passed has moved a payload larger than the TCP-freeze threshold (every control is at least 32 KiB) and checked it byte-exact, not only a handshake or a small request.

## Goals / Non-Goals

- Goal: deliver `Require a volume-moving transfer for the connection test verdict` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Threshold: no separate freeze-threshold constant or filter exists. `PayloadFailoverPolicy` already enforces `MinPayloadBytes = 32_768` on every `PayloadProbeControl`, which is above any TCP-freeze threshold (the diagnostics fixtures use 16_384 and the runtime `freeze_min_bytes` is 512). Every published control is therefore large enough, and the test tries every control in the policy, largest first, passing when any one verifies (the same semantics as `probePayload`).
- Failure classes: when no control verifies, an attempt that timed out or received some but not all bytes gives `Stalled(max receivedBytes)`; any other failure (wrong status, redirect, size or hash mismatch on a full body, connection refused before a body) gives `Rejected`, which has its own failure message and records no byte count.
- Policy lookup: the active group id comes from `AppliedRuntimeConfigurationSource` (`effectiveSelection.selectorGroupId` of the running configuration for the selected mode). The policy is `ProxyGroupRepository.list()` -> group `failover.payloadPolicy`, the same sources the selector prober uses.
- Transfer engine: the onboarding runner calls `CandidateHttpPayloadProbe.probeControl`, which runs the existing strict `OkHttpRelayTcpProbe` and `consumeVerifiedPayload` (exact length and SHA-256). No second downloader is added. `consumeVerifiedPayload` gains an optional byte callback, and the probe result and `CandidateHttpPayloadEvidence` gain `receivedBytes`, the count where the transfer stopped. Existing callers keep their defaults.
- Route: the probe goes through the same SOCKS route as the connectivity probe (VPN tunnel route or the proxy endpoint). The only URL contacted is the control URL, so no third-party host is added.
- Verdict model: the onboarding test is the only connection test in the code base, so no saved-server check shares it. `OnboardingValidationResult.Success` carries `volumeTested`. A failure uses the new `OnboardingValidationStep.Volume` and `transferredBytes`.
- Volume not tested: no group or no policy gives Success with `volumeTested = false`. `noDomain` (`route.resolveNamesLocally`) also gives `volumeTested = false`: this is an explicit exception to criterion 3, because the SOCKS endpoint then cannot resolve the control host and resolving it in the app would send the lookup outside the tunnel. The summary row shows "Connected, volume not tested", not Passed.
- Strings: three onboarding strings in all 10 locales (`onboarding_validation_failed_volume`, `onboarding_validation_failed_volume_rejected`, `onboarding_done_test_volume_untested`).
- Progress UI: the Volume step shares the Connectivity stage in the scene and signal line. A Volume failure shows the Tunnel and DNS rows complete and the Connectivity row failed.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
