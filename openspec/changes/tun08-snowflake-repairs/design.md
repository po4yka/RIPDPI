## Context

Task RLY-1794491312461911 repairs TUN-08 on base 3e01ef8b. Main already fixes standalone activation, managed negotiation, endpoints, stderr and shutdown. See proposal.md.

## Goals / Non-Goals

- Goal: repair source/DTLS support and early rendezvous validation.
- Non-goal: add a raw Snowflake relay, VPN support, a native Rust port, public network probes or new profile identities.

## Decisions

- Use the official Snowflake client as a separate source; current Lyrebird does not forward the required setting even at its latest revision. Preserve Lyrebird for obfs4. Select an immutable verified upstream SHA and its required Go toolchain.
- Validate rendezvous in the shared settings validator used by app and service. Require a valid HTTP(S) broker or complete nonconflicting SQS options. Preserve explicit AMP support, do not invent credentials, and do not treat absent ICE as a guaranteed connection failure.
- Set Snowflake process defaults to `-covertdtls-config mimic`; preserve explicit per-connection overrides. Broker uTLS remains an explicit bridge option. Do not rewrite bridge strings or claim the dead standalone argument builder is the active policy.
- Keep one commit per remaining defect; cite existing commits for already repaired findings.

## Contracts and ownership

- Source worker owns native/pluggable-transports/sources.json, source tests, ADR and any narrow Snowflake Tor process default arguments needed to make the DTLS policy effective.
- Validation worker owns core/data/settings TorBridgeValidation and owning settings/app/service regression tests.
- Coordinator owns portfolio/OpenSpec and evidence. All writers use isolated worktrees. Manifest updates are serialized. No Cargo.lock, libs.versions.toml, schema, locale, golden or baseline changes.

## Risks / Trade-offs

- Separate helper increases package size and toolchain download needs; keep obfs4 unchanged and record source/ABI build evidence.
- Saved incomplete bridge profiles stop validating; show the existing invalid-profile error and preserve records.
- Host method negotiation does not establish Android or external compatibility. Record gaps instead of substituting stubs.

## Migration Plan

No storage migration. Integrate only reviewed commits with owning regressions and combined source gates. Rollback can revert the source or validator commit independently; do not restore a claim that the old helper supports DTLS mimic.

## Validation

- Owning TorBridgeValidation, ConnectionTorValidation and UpstreamRelayRuntimeConfigResolver tests, including new RED/GREEN cases.
- Existing managed PT and standalone activation regressions.
- Official source host build, method/EOF and DTLS-setting checks; source Android ABI build where local toolchain permits.
- Relevant build tests, staticAnalysis, just ci-gates and locked cargo metadata before integration.
- Independent pr-reviewer; no physical device, root or external transport probes.
