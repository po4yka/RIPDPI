## Context

Task SVC-1791566464861507 follows the clean c25730eca baseline failure. A durable link refresh receipt precedes the Xray restart and second builder establishment. Stored policy hashes are not the original monitor event pair. The fingerprint contains a transient direct DNS underlay generation in addition to physical fields. The own app UID is excluded from the VPN route, while the default callback can observe the VPN.

## Goals / Non-Goals

- Goal: Keep transient lease acquisition or loss separate from physical handover classification.
- Goal: Preserve real physical recovery, including a change between two valid underlay generations.
- Non-goal: Change the scope hash recipe, routing policy, native DNS binding, or stored contracts.

## Decisions

- Capture a privacy-safe changed-field bitmap and token presence at the original monitor comparison. Do not log field values or raw network identities. Freeze and record the diagnostic source and APK hashes.
- Prove the field transition through the actual binder, authority, snapshot source, fingerprint provider, and unchanged classifier before the behavior fix. Control callback scheduling in an integration regression. Preserve the distinction between this reproduced mechanism and the historical runtime event, whose original field pair was not recorded.
- Keep the existing observer and classifier boundary. Compare physical fields independently of token acquisition or loss. A change between two valid generations remains eligible for the existing link refresh event. The two actual binder integration regressions fail only at the final no-handover assertion; physical network equality, physical fingerprint equality, and token presence assertions pass. Independent review confirmed this reproduction before the comparison change.
- Preserve validation, captive portal, metering, DNS, transport, disconnect, and real link recovery behavior. Do not compare only scope hashes and do not remove the transient generation from direct DNS lease processing.

## Contracts and ownership

- Android writer owns :core:service handover observation, privacy-safe diagnostics, and unit tests.
- Coordinator owns specification artifacts, task lifecycle, combined gates, and publication.
- Reviewer audits the original event evidence and the comparison boundary.
- No Rust, JNI, protobuf, public interface, persistence, locale, dependency, lockfile, or baseline changes are planned.

## Risks / Trade-offs

- Equal physical fingerprints can cover distinct physical leases. Retain changes between two valid generations and the existing physical state classifications.
- An absent token can accompany real disconnection. Exclude only token acquisition or loss when the other physical inputs remain equal; keep physical loss events.
- A successful repeat alone can hide the original interruption. Retain the failure, changed-field evidence, and real baseline, outage, and recovery receipts.
- Natural diagnostic repeats did not capture the original false-event field pair. Do not claim that the historical runtime field was directly measured; the real callback chain and deterministic production-code regression support the mechanism.

## Migration Plan

No data migration is required. Roll back the isolated service commit if needed. Run full :core:service:testDebugUnitTest, :core:service:lintDebug, detekt, staticAnalysis, architecture health, and locked Cargo metadata. Rebuild genuine Android artifacts, run the full Android profile, a full Xray repeat, and VM-routed Xray, then verify all completed reports. Recheck the full 42-scenario catalog on the clean combined source and observe remote CI on published main.
