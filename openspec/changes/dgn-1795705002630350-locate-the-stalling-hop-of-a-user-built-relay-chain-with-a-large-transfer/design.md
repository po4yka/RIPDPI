## Context

Portfolio task `DGN-1795705002630350` owns this change. For a relay chain that the user built on the client, diagnostics shows the first hop where a large transfer stops.

## Goals / Non-Goals

- Goal: deliver `Locate the stalling hop of a user-built relay chain with a large transfer` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Trigger: the onboarding connection test is the only connection test in the code base (see `DGN-1795704789266331`). When its volume step ends as `Stalled` and the running configuration's effective selection is a `chain_relay` profile, `OnboardingVolumeCheck` asks `RelayChainStallLocator` for the stalling hop. A passed or rejected transfer does not start the locator, so a healthy chain costs nothing extra.
- Hop prefixes: a client-built chain is the ordered profile list entry, `chainMiddleProfileIds`, exit. Prefix 1 is the entry hop profile alone, with its own stored credentials. Prefix k (k >= 2) is a transient copy of the chain profile whose middle list is hops 2..k-1 and whose exit is hop k; the canonical chain resolver then loads each hop by profile ID. Every middle hop is a valid exit kind (`isSupportedChainNonEntryHop`), so every prefix resolves with the existing validation. The locator measures prefixes in order and stops at the first one that does not move the full payload; that prefix's last hop is the stalling hop, and the byte count is the count received through that prefix.
- Measurement: each prefix runs in the existing ephemeral relay owner `CandidateRelayPayloadProbe` (never activates a stored profile) through the new `measureVolume`, which calls `CandidateHttpPayloadProbe.probeControl` with the same `PayloadProbeControl` and timeout as the connection test. No second transfer engine is added. The control is the largest one in the policy; every control is at least 32 KiB, above the TCP-freeze threshold.
- Verdict per prefix: `Succeeded` means the hop passed. `TimedOut` or `Failed` at the Ready stage is a stop at that hop with 0 bytes, because the hop never came up and no byte moved through it. At the Http stage it is a stop only when the received count is known and smaller than the control size; a full body that fails verification, or a count lost to the outer deadline, is not a volume stop. Those cases, a rejected configuration, an unsupported kind, a changed environment and a pending cleanup are `Unmeasured`: the locator does not blame a hop for a measurement it could not run, and the test keeps the whole-chain stall message.
- Cost: the locator runs only after a whole-chain stall, at most one prefix per hop (the wire config carries up to four hops), each bounded by the relay ready deadline, the control timeout plus a fixed slack, and the cleanup deadline.
- Controls for a chain: a user-built chain has no selector group, so it uses the selected group's payload policy when one exists and otherwise the first imported group that publishes one. The controls are operator-published verification targets, independent of the exit, and are contacted only through the chain under test. With no published controls the test stays "volume not tested", as before.
- Server-side cascade (ADR 0009): any non-chain profile, including one endpoint that cascades behind the server, is `SingleEndpoint`. The locator measures nothing for it and the test reports the single whole-path stall, with no per-hop claim.
- Inline legacy chains: a chain whose entry or exit uses inline server fields instead of a profile reference has no standalone hop profile for prefix 1, so it is `Unmeasured`.
- Report: `OnboardingVolumeOutcome.Stalled` carries an optional `hop`; the failure reason names the hop and the byte count with the new string `onboarding_validation_failed_volume_hop` (10 locales), and `transferredBytes` is the count at the stop.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
