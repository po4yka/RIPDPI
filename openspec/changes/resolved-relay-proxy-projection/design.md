## Context

The normal SharedProxyRuntimeStack branch waits for UpstreamRelaySupervisor.start but ignores the resolved profile settings. The race branch already projects its winner and bound endpoint. The native proxy rejects a required UDP path when the incoming upstreamRelay.udpEnabled field is false; its guard and Hysteria datagram implementation are correct.

## Goals / Non-Goals

- Goal: use one checked projection of the ready relay for normal and race proxy startup.
- Goal: retain UDP admission, readiness cleanup, selector identity, and egress precedence.
- Non-goal: change native capabilities, transport authentication, persistence, wire schemas, dependencies, or device acceptance harnesses.

## Decisions

- Return a small immutable StartedRelayRuntime from ordinary supervisor start. It contains resolved kind, profile ID, UDP policy, and the bound LocalProxyEndpoint. Build it from the exact started slot, whose consumed configuration already records resolved identity.
- Resolve the actual listener after readiness for every relay slot inside the existing guarded readiness block. Failures use the existing cleanup path. Requested ports are not proof of the bound listener.
- Format subprocess listener telemetry with the existing java.net.URI authority formatter so IPv4, hostnames, and IPv6 round-trip through the shared endpoint parser. The endpoint parser removes the parsed IPv6 brackets before projecting the raw host into proxy JSON. The subprocess readiness probe confirms the emitted host and port before reporting ready.
- Use one projection helper for normal descriptors and race descriptors. Keep the race result and callbacks intact. Check all projected identity, UDP, and endpoint fields before proxy start.
- Share the VLESS transport/flow UDP predicate between imported-profile mapping and service admission. The security owner implements this in core/data model and data mapping plus RelayKindDescriptor; retain all unsupported sub-mode refusals.
- Place the shared exact-kind predicate and the full profile predicate in runtime-state RelayUdpCapabilities.kt. Keep the explicit UDP-disabled veto and inspect raw Reality TCP transport and Vision flow. Service descriptors derive their UDP property from the shared kind predicate instead of duplicate literals; update the two app failover consumers to import the shared profile predicate.
- Engine preference normalization must preserve the resolved UDP flag for every supported kind. A shared pure kind/profile capability policy must agree with imported mapping and service admission. It must reject unsupported raw Reality sub-modes before normalization supplies any defaults.
- Keep runtime handles and credentials in the supervisor. The descriptor is service-private and contains no credentials. Do not reload stores after startup or infer capability from the raw preference flag.

## Contracts and ownership

- core/data model and data mapping share a pure transport capability predicate with core/service. The security owner owns these files, engine relay preference normalization, the two app failover predicate consumers, and their tests.
- core/service owns the startup return type and shared renderer checks. Existing callers may ignore the new return value; every proxy-producing caller must consume it.
- No Rust crate, JNI schema, protobuf, storage, dependency, or locale changes.
- The relay-runtime owner edits the two startup classes, the subprocess telemetry projector, service tests, and docs/architecture/CONFIG_CONTRACTS.md. The Android owner retains app tests, acceptance scripts, and device use. Integration and hosted verification remain with the parent owner.

## Risks / Trade-offs

- A runtime that reports ready without a listener will now fail normal startup. This is required to avoid connecting the proxy to an unverified endpoint; tests must cover cleanup and runtime telemetry contracts.
- New projection can expose assumptions in selector or fake-runtime tests. Preserve captured identity and fix inaccurate test telemetry rather than weakening production checks.
- Rendering failure occurs after a relay starts. Keep supervisor ownership so the existing caller cleanup can stop and join it.

## Migration Plan

No migration is required. Roll back the source change as one unit if required. The owned physical run on fea412b9ef4165f3d4a97651ce7c0e9033de1d25 observed the normal-projection failure. Run the complete service JVM suite and affected static checks through build-gate with at most four workers. Complete independent root and security review before commit. Repeat physical Hysteria and migration acceptance after integration; local tests do not establish device or upstream-network acceptance.
