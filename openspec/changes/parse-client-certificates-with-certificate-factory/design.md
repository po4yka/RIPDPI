## Context

Audit finding 3 (attachment 3) at 202f970 is owned by portfolio task AND-1794036563799026. Read its current callers and tests before implementation; the audit does not authorize unrelated cleanup.

## Goals / Non-Goals

- Goal: Certificate import must accept valid DER and PEM bytes without first requiring UTF-8 text.
- Non-goal: Change unrelated protocols, stored identities, UI text, lifecycle behavior, or the conditional audit proposals.

## Decisions

- Pass bounded bytes to the platform X.509 CertificateFactory. Remove certificate regex and manual Base64 input decoding; keep PEM output normalization.
- Use only existing platform or workspace libraries. Check the direct module dependency before editing.
- Extend MasqueClientCredentialImporterTest with independent DER and PEM certificate fixtures, multiple certificates, malformed bytes, empty input, and size boundaries.

## Contracts and ownership

- `app/src/main/kotlin/com/poyka/ripdpi/security/MasqueClientCredentialImporter.kt`

Keep the 256 KiB input limit, explicit empty or invalid certificate rejection, certificate order, and existing private-key and PKCS12 import contracts.

The user directed work on main on 2026-11-07. Work directly on main and commit each completed atomic unit. This instruction overrides worktree-isolation guidance for this programme. Do not push, publish, or deploy without separate authorization.

One writer at a time owns native/rust/Cargo.toml, native/rust/Cargo.lock, the fuzz lockfile when affected, gradle/libs.versions.toml, API snapshots, and architecture outputs. Regenerate owned outputs through their tools; do not relax baselines or bless changed golden files. The epic defines the recommended queue; shared-file order is a scheduling constraint, not a semantic dependency.

The owning writer updates all callers and tests in this slice. Prerequisites: None. Respect the epic priority and shared-file queue.

## Risks / Trade-offs

- Standard parsers can accept or reject different input forms: enforce the task's explicit contract with independent boundary vectors.
- Cancellation and persistence can lose resources or state: exercise the stated failure case, not only round trips.
- Dependency changes can affect size, licensing, and Android support: review actual direct and transitive edges before adoption. No baseline relaxation or unrelated compatibility layer is permitted.

## Migration Plan

First reproduce the specified uncovered behavior or establish a passing contract baseline for mechanical cases. Then replace only the owning infrastructure, keep existing stored and wire formats, and run the gates below. No persisted-data or schema migration is planned. If that assumption fails, revise this plan before editing that contract. Roll back the owning implementation commit if required, without deleting user data.

- `./gradlew :app:testGithubFullDebugUnitTest --tests "*MasqueClientCredentialImporterTest"`
- `./gradlew staticAnalysis`
- `just ci-gates`

Run the named targeted checks first. Run staticAnalysis for Kotlin cross-module or security changes and just ci-gates for affected source contracts. Native dependency changes also require locked metadata and cargo-deny. Obtain the repository-required independent review; add async, JNI, or unsafe review only when the actual diff meets those rules. Record exact commands, results, and the implementation SHA. Commit only the finished task slice on main.
