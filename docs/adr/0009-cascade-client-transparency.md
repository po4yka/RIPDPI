# ADR 0009: Cascade Client Transparency

> Status: Accepted (client side unconditional; deploy-side provisioning remains gated). Decision date: 2026-07-10.

## Context

A companion deploy-side decision, tracked in the `ripdpi-vpn-deploy` repository as `docs/RU-CASCADE-DECISION.md`, proposes an RU-jurisdiction-hosted entry node whose purpose is riding domestic allow-listing for a temporary, opt-in class of deployment. Traffic that reaches that entry node is split server-side: some of it stays on a domestic path, the rest continues through a foreign-hosted tunnel leg. The entry node is a single endpoint from the client's point of view; the domestic/foreign split happens entirely behind it, on infrastructure the client never addresses directly.

RIPDPI already has a settled precedent for exactly this shape of decision. `docs/architecture/README.md` (Ownership Boundaries) records: "Kotlin dispatches `consume_existing` versus `publish_local_origin`. Rust relay-core remains transport-only and intentionally does not branch on tunnel mode." That is, when an operator-side choice changes what happens behind a tunnel endpoint, the relay-core client stays out of the decision entirely — it treats the endpoint as opaque. The cascade proposal is the same shape of decision one layer further down: an operator-side split of what happens behind a single entry endpoint, not a new thing the client needs to know how to reach or negotiate.

RIPDPI also already has a relay kind that composes multiple hops from the client side: `RelayKind::ChainRelay` (`native/rust/crates/ripdpi-relay-core/src/config/kind.rs`, builder at `native/rust/crates/ripdpi-relay-core/src/backend/builder/builders/chain_relay.rs`). `ChainRelay` exists because the client is explicitly aware of each hop, configures each hop's transport, and dials them in sequence — see the composed-fixture regression coverage in `native/rust/crates/ripdpi-relay-core/src/tests.rs` (for example `chain_relay_vless_second_hop_failure_surfaces_recognizable_error`), which drives two independently started loopback fixtures through a client-composed chain and asserts on a chained failure surfacing correctly to the caller. That test answers "does RIPDPI's own client-side hop-chaining work correctly end to end." It does not answer, and must not be read as answering, "does the client stay transparent when an operator splits traffic behind an endpoint it does not know is split." Those are different questions with different fixtures, because `ChainRelay` is client-driven multi-hop and cascade transparency is the client seeing exactly one hop.

RIPDPI's transport contract already carries at least one typed-but-unconsumed diagnostic slot: `docs/server-integration.md` documents a `topology` field with `split_hop_egress` and `hysteria_realm` sub-fields, surfaced on `SingBoxParseResult.Success.topology` and present in the contract schema and golden fixtures (`core/data/src/test/resources/contract/ripdpi-bundle.schema.json`, `ripdpi-bundle.golden-full.json`). The open task `docs/tasks/issues/wire-hysteria-realm-stun-nat-traversal.md` records that no code anywhere in `ripdpi-hysteria2` consumes `hysteria_realm` — it parses and round-trips but has no diagnostics or runtime reader. That is the concrete shape of failure this ADR wants to avoid repeating for a cascade-specific topology field: a schema slot that ships before any consumer exists tends to stay dormant indefinitely.

Separately, RIPDPI's per-package routing already gives the client a routing axis of its own: which installed apps have their traffic tunneled at all (per-package inclusion/exclusion, referenced in `docs/architecture/README.md` under the adaptive-runtime and desync sections and implemented via the process-based per-package routing work). That axis answers "does this app's traffic enter the tunnel." The proposed server-side GeoIP split answers a completely different question — "once traffic has entered the tunnel through the single entry endpoint, which egress path does it take." The two axes must not be conflated or double-applied: client-side exclusion decides participation, server-side split decides egress, and neither one is a substitute for or an input into the other from the client's perspective.

## Decision

**The RIPDPI Android client requires zero schema or code change to support the proposed cascade.** The client connects to one opaque entry endpoint through whichever relay kind that endpoint already speaks; the domestic/foreign split is a server-side property of what happens behind that endpoint, invisible to and unaddressed by the client. This is recorded as the second instance of the already-accepted "transparent operator-side multi-hop behind one endpoint" precedent, alongside the Cloudflare Tunnel `consume_existing`/`publish_local_origin` precedent in `docs/architecture/README.md` (Ownership Boundaries).

Specifically:

1. **No new `RelayKind`, no capability flag, no transport-descriptor branch.** The transport-only dispatch invariant that already governs Cloudflare Tunnel mode extends unchanged to the cascade: relay-core dispatches on transport (what protocol the client speaks to the entry endpoint), never on what an operator does with that traffic once it is behind the endpoint. Adding a cascade-aware branch anywhere in `RelayKind`, `RELAY_TRANSPORT_REGISTRATIONS`, or the backend builders would violate that invariant for no client-visible benefit.

2. **Client-side app-exclusion and server-side GeoIP-split are orthogonal routing axes.** Per-package tunnel participation stays a client-only decision; egress path selection behind the entry endpoint stays a server-only decision. Neither axis reads or influences the other, and no code path may apply both a client-side geo-routing decision and a server-side one to the same flow.

3. **The only near-term client-side work is a transparency regression test.** It must extend the existing composed loopback/chain-hop fixture pattern (the pattern used by the `ripdpi-relay-core` chain-relay tests, e.g. `chain_relay_vless_second_hop_failure_surfaces_recognizable_error`) with a generic, protocol-agnostic forward-only second hop standing in for "an operator-side split the client does not know about." This test proves the client's single-endpoint connect path is unaffected by what happens after the first hop; it must not reuse or repurpose `RelayKind::ChainRelay` as evidence of transparency, since `ChainRelay` is a client-aware multi-hop kind answering a different question, as established in Context above.

4. **Two follow-on capabilities are deferred behind explicit gates and are out of scope for this ADR:**
   - An optional topology-disclosure field, gated on shipping with a concrete diagnostics consumer plus a contract round-trip test in the same change — never landing as a schema slot with no reader, the way `hysteria_realm` did.
   - An optional diagnostic-verdict heuristic, gated on the deploy-side cascade role exiting its RESEARCH/EXCEPTION tier, default-off, and labeled in any UI or log surface as "heuristic, not confirmed diagnosis" rather than a definitive verdict.

## Rationale

- **Precedent match, not a new category.** The Cloudflare Tunnel mode split already established that Kotlin/deploy-side dispatch decisions about what happens behind an endpoint do not need a Rust relay-core branch. The cascade is structurally the same: an operator-side choice behind a single client-facing endpoint.
- **The transport-only invariant is the thing worth protecting.** Every relay kind RIPDPI has added stays exhaustively classified in `relay_dispatch_class` (`native/rust/crates/ripdpi-relay-core/src/tests/transport_registry.rs`) precisely so a new kind cannot be added silently. Branching on server-side topology inside that dispatch surface would be a structural regression of the same kind the exhaustiveness guard exists to prevent, even though it would compile.
- **Orthogonal axes stay legible only if they stay orthogonal in code, not just in this document.** Conflating "does this app tunnel" with "which path does tunneled traffic take" would make both harder to reason about and harder to test independently; keeping them apart lets the existing per-package routing test surface and any future cascade-egress test surface stay decoupled.
- **`hysteria_realm` is the cautionary example, not a hypothetical.** A typed topology field that ships without a consumer is not a small omission — the open task against it shows it has stayed unconsumed since it landed. The deferred topology-disclosure field must not repeat that pattern.
- **A heuristic before the deploy-side role has left RESEARCH/EXCEPTION tier would mislead users.** Surfacing a confidence signal about infrastructure that is itself still gated by a pending, unverified per-ASN attestation (see Approved Context below) would present speculative diagnostics as settled fact.

## Approved Context (Sign-off Record)

This section records the org-level sign-off this ADR builds on. It is a faithful summary, not a restatement of deploy-side operational detail, which stays out of scope for this document.

- **RU-jurisdiction hosting exception.** Hosting in RU jurisdiction is accepted specifically and only for a temporary, opt-in entry node whose purpose is riding domestic allow-listing. This is the project's first hosting-jurisdiction exception; it is opt-in only, never a default, and never placed in a Preferred or Acceptable hosting tier. The associated legal, data-retention, compulsion, and seizure exposure is accepted as a known, bounded tradeoff specific to a temporary node, not a general hosting posture change.
- **Per-ASN allowlist attestation gate.** An empirical per-ASN allowlist attestation is approved as a recurring, expiring, fail-closed precondition for provisioning. The live measurement itself has not been run and cannot be produced without a real RU vantage point and a real candidate host — both operational activities out of scope for this document. The attestation record therefore ships in a pending/unverified state that hard-blocks provisioning until an operator produces a real, dated, per-ASN attestation. A brand-based or assumed pass is explicitly rejected as the anti-pattern this gate exists to prevent. The deploy-side attestation framework and its current pending/unverified record are tracked in `ripdpi-vpn-deploy`'s `docs/CASCADE-ASN-ATTESTATION.md`.
- **Structural approval.** The deploy-side design uses a separate ingress/egress role pair rather than an extension of the existing split-hop roles; a dedicated EXCEPTION tier with isolated Terraform state and a literal, non-boolean opt-in; and a fail-closed tri-state classifier (domestic / foreign / dataset-unavailable) where a dataset-unavailable result hard-blocks serving with no operator override. The client stays fully transparent under this structure, which is the subject of this ADR.

### Caveats carried forward

- A single anecdotal, observation-grade report of an AS-identity mismatch between two distinct cloud-provider ASNs sits in tension with a separate, larger-sample measurement that shows continued allowlist presence. This motivates recurring per-ASN re-verification, not a one-time or brand-based sign-off.
- Escaping a foreign-datacenter connection-freeze failure mode via an RU-AS node is a trade into a different, already-anticipated enforcement path specific to a flagged domestic cloud AS. It is latent risk, not an unqualified improvement.
- Split-hop's directional invariants (which side must initiate, which side must not listen) defend a different threat model — dual-role flow correlation — and do not transfer to a cascade entry node, which is by definition client-facing and would invert split-hop's guarantee. This is why the deploy-side design uses a separate role pair rather than reusing split-hop's roles.
- Fail-closed behavior on empty or stale classifier data is a confidentiality and policy invariant, not an availability tradeoff. A passing happy-path test never substitutes for a required forced-empty-dataset test on the deploy side.
- IPv4-only operation and CGNAT-incompatibility are entry-node preconditions at the class level; they constrain which candidate hosts are eligible, independent of any specific host identity.

## Alternatives Considered

### Add a new `RelayKind` for cascade-aware entry nodes

Rejected. There is nothing for the client to be aware of: the split happens entirely behind the endpoint. A new kind would violate the transport-only dispatch invariant for a distinction the client cannot observe and does not need to act on.

### Add a capability flag or transport-descriptor branch keyed on cascade presence

Rejected for the same reason as a new `RelayKind`. A capability flag would require the client to learn about server-side topology it has no way to verify and no use for, and would create exactly the kind of transport-descriptor branch the Cloudflare Tunnel precedent already ruled out for topologically similar decisions.

### Ship the topology-disclosure field now, ahead of a consumer

Rejected. This is the `hysteria_realm` failure mode repeated on purpose. The field is deferred until it ships in the same change as a concrete diagnostics consumer and a contract round-trip test.

### Ship the diagnostic-verdict heuristic now, ahead of deploy-side tier graduation

Rejected. Surfacing any confidence signal about the cascade path while the underlying attestation is still pending/unverified and the deploy-side role is still in RESEARCH/EXCEPTION tier would present speculation as diagnosis. Deferred until the deploy-side gate clears, and default-off with explicit heuristic labeling even after that.

### Reuse `RelayKind::ChainRelay`'s test fixtures as transparency evidence without a new test

Rejected. `ChainRelay`'s existing coverage proves client-composed multi-hop dialing works; it says nothing about whether the client stays inert when a *single* endpoint's behind-the-scenes routing changes. A dedicated transparency regression test is required precisely because the existing fixtures answer a different question.

## Consequences

- No `RelayKind` variant, capability flag, transport descriptor, or backend builder changes as a result of the cascade proposal. The exhaustive dispatch classification in `relay_dispatch_class` (`native/rust/crates/ripdpi-relay-core/src/tests/transport_registry.rs`) is untouched.
- Per-package tunnel-participation routing and any future server-side egress split remain independent, non-interacting decisions; no code path applies both to the same flow.
- The client-side deliverable for this ADR is limited to one new regression test extending the composed loopback/chain-hop fixture pattern with a generic forward-only second hop, asserting the client's single-endpoint connect path is unaffected by behind-the-endpoint routing changes.
- The topology-disclosure field and the diagnostic-verdict heuristic remain explicitly out of scope until their respective gates clear; neither is implied to exist by this ADR.
- The blocking preconditions for the cascade — RU-hosting posture sign-off and the per-ASN attestation gate — live entirely on the deploy side and are tracked in `ripdpi-vpn-deploy`'s `docs/RU-CASCADE-DECISION.md`. None of them require or imply a client change; this ADR should be cited wherever that deploy-side document or its derived tasks need to state the client-side impact.
- Cross-repo task tracking for any follow-on work (the transparency regression test, and later the gated topology-disclosure and heuristic work) should use the `ripdpi-improvements` unified task schema's `linked_task` field to cross-reference the corresponding `ripdpi-vpn-deploy` issue, consistent with how other cross-repo proposals are tracked under `docs/tasks/issues/`.

## Revisit Trigger

Revisit this ADR if: the deploy-side design stops presenting the cascade as a single opaque client-facing endpoint (for example, if the client were ever asked to select or negotiate an egress path itself); the per-ASN attestation gate is removed or weakened to allow a non-empirical pass; the deploy-side cascade role graduates out of RESEARCH/EXCEPTION tier, which is the trigger for reconsidering the deferred diagnostic-verdict heuristic; a concrete diagnostics consumer is proposed for a topology-disclosure field, which is the trigger for reconsidering that deferred field; or client-side evidence emerges that a behind-the-endpoint operator split is in fact observable or distinguishable by the client, which would undermine the transparency premise this ADR relies on.

## Implementation Sketch

No production code changes in this ADR. The only concrete near-term work is the transparency regression test described in Decision point 3: extend the composed loopback/chain-hop fixture pattern already used by the `ripdpi-relay-core` chain-relay tests with a generic, protocol-agnostic forward-only second hop, and assert that the client's connect path through a single configured entry endpoint is unaffected by what that forward-only hop does. The test must not construct or depend on `RelayKind::ChainRelay` to make its point, since that kind is client-aware multi-hop and would evidence the wrong property.

The two deferred capabilities are intentionally left unsketched here beyond their gates, stated in Decision point 4: a topology-disclosure field must not be designed until it ships together with a concrete diagnostics consumer and a contract round-trip test; a diagnostic-verdict heuristic must not be designed until the deploy-side cascade role has exited RESEARCH/EXCEPTION tier, and even then must default off and label itself as a heuristic rather than a confirmed diagnosis.
