## Context

The current Xray fixture has real native peers and separate-UID Android TUN probes.
Its XHTTP transport uses REALITY. Native AWG and Hysteria tests have narrower scope
than the full application VPN. The existing acceptance runner already binds source,
artifact hashes, test identities and cleanup.

## Goals / Non-Goals

- Goal: Add real P1 TLS, four-protocol payload completion and recovery, and replacement-server transactions.
- Non-goal: Claim physical power policy, Wi-Fi/cellular handover, filtered networks or public-provider success from a local emulator.
- Non-goal: Add a production dependency or change production AWG, JNI, bundle or migration contracts in this lane.

## Decisions

- Extend the existing peer, adapter and separate-UID probe surfaces. Keep production service and native bindings enabled.
- Run an owned nginx process with a generated local certificate in front of a plaintext XHTTP backend. Pass explicit trust to the test profile; do not disable certificate validation.
- Generate bounded payloads and verify length and SHA-256 at both client and destination. Keep the payload outside Binder result bundles and return only bounded measurements.
- Use run-specific transaction IDs and endpoint-specific receipts. Prove baseline, interruption and recovery separately; do not infer application health from service state.
- Use a device-address-scoped UDP gate for the direct LAN failure boundary. Keep loopback control separate from the private LAN data path.
- When there is no common LAN, use separate local USB scenarios with an authenticated test-only datagram bridge. Keep real VPN, native protocol and independent payload checks. Mark direct UDP, underlying-network bypass and network handover as unverified.
- Exercise migration through the existing application entry point. Keep the old data listener unavailable from the first failure through saved-reserve traffic, independent HTTPS mirror refresh and replacement verification. Retire owned peer processes only after the authenticated receipt and separate-UID replacement transfer.
- Consume the AWG owner's schema-v2 range/I-program fixtures and the security owner's verified libXray artifact. Their production files have separate owners.

## Contracts and ownership

- Android instrumentation owns new transaction tests and the test-only separate-UID probe contract.
- Go fixture changes stay under scripts/fixtures/xray-provider-peer. nginx discovery and owned lifecycle stay in the acceptance harness.
- Adapter scenario/check catalogs and generated manifest have one writer in this lane. Regenerate the manifest through its generator.
- No production persistence migration is introduced. Existing migration delivery, selection acknowledgement, native protection and no-bypass contracts remain unchanged.
- Exact owned paths are recorded in the portfolio task before implementation.

## Risks / Trade-offs

- Local TLS could mask trust defects: retain positive trust and independent wrong-trust rejection.
- Short successful probes can conceal stalled transfers: require complete bodies, deadlines and digest checks.
- Reused receipts can conceal wrong routing: bind each transaction to the run and selected endpoint and compare counters before and after.
- JVM checks cannot prove Android routing: require a leased emulator or explicitly authorized physical serial with genuine native artifacts and separate-UID traffic. Physical runs refuse unrelated app installations before mutation.
- Multiple heavy builds can exhaust memory or disk: serialize through build-gate, keep worker counts at most four, and check capacity before preparation.

## Migration Plan

Land executable harness changes after failing regression cases and independent review.
Run Python/Go fixture checks, Kotlin compilation and static analysis, then selected
complete Android scenarios on a leased ARM64 emulator or the authorized physical device. Revalidate generated manifest,
task contracts and source identity on the integrated tree. Rollback reverts only the
new test harness slice; existing scenarios and application state contracts remain.
Physical and external acceptance stay open until those environments are exercised.
