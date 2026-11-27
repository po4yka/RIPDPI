## Context

Portfolio task `DGN-1795704952088416` owns this change. Diagnostics reports mobile allowlist mode as its own verdict, separate from a generic outage and from DPI blocking, and narrows the candidate transports for that regime.

The selective-availability matrix from `DGN-1795704974460888` already measures HTTPS reachability per target, with a cohort (`declared_available`, `domestic`, `global`, `user`), a hosting class (`domestic_hosting`, `foreign_datacenter`, `cdn`) and a destination AS for each target. Its summary already separates `matrix_selective` from `matrix_unavailable` and `matrix_mixed`. This change reuses that evidence and adds no new measurement path.

## Goals / Non-Goals

- Goal: one falsifiable verdict, `mobile_allowlist_mode`, computed from evidence that a single matrix scan already collects.
- Goal: a stable token contract that `DAT-1795704964813672` and the server task `SCR-1795705796387934` can consume.
- Non-goal: a new probe family, a new wire field or a schema version bump.
- Non-goal: a relay-kind table. The narrowing is a transport class, not a list of protocols or parameters.
- Non-goal: changes to the separate CIDR allowlist tool in the diagnostics tools section. It is a standalone check outside the scan report and stays as it is.

## Decisions

### Owner and dependency

The diagnostics engineer owns the verdict model. The rule lives in `ripdpi-diagnostics-classification` (`classification::mobile_allowlist`), next to the other pure classification rules, and the monitor engine adds its result to the report diagnoses when it builds the report. The verdict depends on the target classes and cohorts of the selective-availability matrix. A change to those class or cohort tokens must update this rule in the same change.

### Evidence source

- HTTPS evidence: the `selective_availability` rows and the `selective_availability_summary` row of the matrix.
- UDP evidence: the existing `quic_reachability` probe. The matrix profile (version 3) declares QUIC targets that are derived from its own CDN-class targets, so one manual matrix scan collects both legs on the same network. Catalog validation rejects a matrix QUIC target that is not a foreign-class matrix target.

### Rule

The report carries the `mobile_allowlist_mode` diagnosis only when all of these hold:

1. The matrix summary outcome is `matrix_selective`. This inherits the matrix rules: full coverage, at least two repetitions, no ambiguous HTTP, body or TLS rows, at least two available declared groups and at least two failed comparison groups.
2. Every `declared_available` target completed HTTPS on every attempt.
3. At least one `domestic` target, which is domestic hosting outside the declared permitted set, failed on every attempt with `matrix_target_transport_failed`, and no `domestic` target completed.
4. At least one non-user target of class `foreign_datacenter` or `cdn` exists, and every such target failed on every attempt with `matrix_target_transport_failed` at the `tcp` stage without an open TCP connection (`tcpStatus` is not `ok`).
5. At least one QUIC probe got no reply (`quic_timeout` or `quic_empty`), and no QUIC probe got any reply.

Condition 1 excludes a universal failure: when every target fails, the matrix summary is `matrix_unavailable`, so the verdict cannot appear. Condition 3 separates an allowlist from a block of foreign address ranges: under a block of foreign ranges, unlisted domestic hosting still completes. Condition 4 separates address-level filtering from DPI: a block that lets the TCP connection open and then fails the TLS handshake for a server name fails at the `tls` stage and does not match. A QUIC error that is not a silent drop (`quic_error`) counts as no UDP evidence. A foreign target that fails at DNS does not match either: the rule accepts only address-level evidence and takes a false negative there over a false positive.

### Falsifiability

Each condition is an observable field in the exported report. A field report contradicts the verdict when an unlisted domestic target completes, when a foreign-class target completes, when a foreign-class failure is at a stage other than `tcp`, when any QUIC reply arrives, or when a declared target fails. The default for a network is unknown: the absence of the diagnosis does not claim that the network is free of an allowlist, and no negative verdict is emitted.

### Contract

The diagnosis has code `mobile_allowlist_mode`, severity `warning` and no target. Its evidence lines are, in this order:

- `verdict=allowlist_mode`
- `permittedAvailable=<count of declared targets>`
- `domesticUnlistedFailed=<count of failed domestic targets>`
- `foreignAddressFailed=<count of foreign-class targets>`
- `foreignFailureStage=tcp`
- `udpSilent=<count>`
- `udpReplied=0`
- `narrowing=standard_https_to_permitted_destination`
- `excludedTransports=udp|direct_foreign_address`

The recommendation states the narrowing reason: use only transports that complete standard HTTPS over TCP toward a permitted destination. Consumers match on the code and the `key=value` tokens, never on the English summary or the English reason text.

### Visibility

- The live scan view, the session detail and the history detail render the diagnosis with a localized summary and recommendation; the evidence tokens show unchanged.
- The share summary and the archive summary emit each evidence token and the reason as a `mobileAllowlist <token>` line through the archive text redactor. The evidence holds counts and class tokens only, so no address of the user network is in it.

## Risks / Trade-offs

- The observations behind this regime come from one author. The rule therefore needs every leg to agree and never emits a negative verdict.
- The UDP leg is every `quic_reachability` row of the report. Only the matrix profile produces matrix rows, and catalog validation keeps its QUIC targets on foreign matrix hosts, so in practice the leg is the profile's own foreign QUIC probe. One silent probe is the whole UDP signal, so packet loss on that probe can satisfy condition 5; the HTTPS conditions must still all agree.
- The UDP leg probes a foreign CDN host. Where that host blocks UDP for its own reasons, the leg reads as silent; the verdict still needs the HTTPS legs to agree.
- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review. The matrix profile version moves from 2 to 3; the bundled catalog and its contract fixture are regenerated together.
