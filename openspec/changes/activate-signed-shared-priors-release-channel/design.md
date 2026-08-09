## Context

The repository already owns the complete consumption path: `ripdpi-shared-priors` validates manifests and payloads, JNI applies accepted priors, `SharedPriorsCatalogNetwork` downloads bounded bodies, and `SharedPriorsRefreshWorker` schedules refresh. Production activation is blocked by an all-zero Rust constant and unset Android build configuration. The private Ed25519 signing key must remain in owner-controlled release infrastructure.

## Goals / Non-Goals

- Goal: activate one auditable, fail-secure, owner-published shared-priors consumption channel in production artifacts.
- Goal: bind completion evidence to the exact source SHA, public configuration, signed fixture, and shipped artifact.
- Non-goal: add uploads, telemetry, user identity, a backend service, key generation, or signing-key custody to this repository.
- Non-goal: accept unsigned, dynamically supplied, or user-overridden verification keys.

## Decisions

- Keep the Ed25519 verification key as public source/build configuration and keep the private key entirely outside Git and Android artifacts. A shared secret would provide no public-verification benefit and would be extractable from the APK.
- Allow release URLs to enter through the existing Gradle release-configuration boundary, but validate HTTPS, host allowlisting, and non-empty values before scheduling. Runtime arbitrary endpoints are rejected because they would change the trust root.
- Preserve the existing atomic native registry replacement: parsing and signature/hash validation complete before the global store is written.
- Add exact-artifact inspection and a signed release fixture as release gates. Unit tests alone cannot prove the shipped APK contains the approved trust root.
- Treat absent configuration as a blocked/inert capability, not a fallback to unsigned or bundled stale priors.

## Contracts and ownership

- `native/rust/crates/ripdpi-shared-priors`: embedded public key, manifest verification, fail-secure registry tests.
- `native/rust/crates/ripdpi-android` and `core/engine`: existing JNI bridge; no signature or schema change is expected.
- `core/service`: release BuildConfig inputs, URL validation, bounded fetch, refresh status, and tests.
- Gradle/release CI: public configuration injection and exact APK inspection. Secrets are limited to the out-of-repository signing operation; only public outputs enter builds.
- Serialized shared files: Rust/Gradle dependency locks are not expected to change. If release configuration schema changes, its tests and documentation land in the same serialized lane.

## Risks / Trade-offs

- Wrong public key bricks refresh → validate key length/non-zero value locally, compare against an owner receipt, and exercise a matching signed fixture before shipment.
- Compromised publication endpoint serves stale content → retain signature, version, payload hash, and monotonic/cooldown checks; HTTPS is transport defense, not the trust root.
- Configuration leaks a private key → accept only public key material in build inputs and scan the exact artifact/repository for signing-key formats.
- Release endpoint outage → keep the last accepted priors and leave core offline strategy selection operational.
- Fixture success diverges from the exact APK → extract/inspect the release constants and run the acceptance flow against the exact source SHA and artifact.

## Migration Plan

1. Owner establishes the external signing identity and publication locations and records the public key fingerprint.
2. Land public-key and URL validation plus positive/negative local fixtures while the release channel remains disabled by default.
3. Configure hosted CI/release builds with the approved public values and verify the exact APK contains them and no private material.
4. Publish a signed manifest/payload fixture, run a production-path acceptance refresh, and record the exact SHA/artifact/deployment receipt.
5. Roll back by removing/rotating the configured public values in a new release; existing artifacts continue to fail closed or retain their last accepted store. A key rotation is a new reviewed contract change, not an untrusted runtime update.
