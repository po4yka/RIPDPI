# ROADMAP: DPI Bypass Modernization

> Next-stage roadmap derived from the 2026 external technical review.
> This roadmap is intentionally separate from the completed audit roadmap and
> the earlier technique-expansion checklist. It focuses on architecture,
> measurement, and product hardening needed to keep RIPDPI effective on modern
> Android networks.

## Design Direction

The review's main conclusions are the planning baseline for this roadmap:

- Keep parser-aware, spec-valid first-flight shaping as the primary bypass path.
- Treat root as an emitter capability upgrade, not a separate strategy system.
- Demote non-root TTL tricks, generic fake packets, and malformed tactics from
  the production critical path.
- Make QUIC Initial shaping a first-class subsystem rather than a bag of
  packet mutations.
- Stop treating encrypted DNS as literal ground truth. Use it as a
  confidence-scored signal with multi-oracle validation.
- Replace single-domain elimination and context-free learning with
  network-aware evaluation and adaptation.

## Status Legend

- [ ] Not started
- [~] In progress
- [x] Complete
- [lab] Keep for diagnostics or research, not for production defaults

## Priority Order

1. Capability hygiene and de-risking
2. Unified first-flight IR and planner refactor
3. QUIC Initial shaping subsystem
4. DNS oracle and resolver-policy hardening
5. TLS shaping, browser-family templates, and ECH integration
6. Strategy evaluation and learning redesign
7. Root/non-root emitter rationalization
8. Android networking hardening
9. Measurement, lab tooling, and rollout gates

## Workstream 1: Capability Hygiene And De-Risking

**Status:** [ ] Not started
**Priority:** P0
**Why now:** The current runtime still allows capability failures to degrade
silently into normal writes. That makes diagnostics look like bypass failures
instead of emitter limitations and poisons adaptive learning.

**Primary areas:**
- `native/rust/crates/ripdpi-runtime/src/platform/linux.rs`
- `native/rust/crates/ripdpi-runtime/src/platform/mod.rs`
- `native/rust/crates/ripdpi-monitor/`
- `core/diagnostics/`

**Tasks**

- [ ] Introduce an explicit runtime capability snapshot for every connection
  path:
  - TTL write support
  - raw TCP fake-send support
  - raw UDP / fragmentation support
  - replacement-socket support
  - root-helper availability
  - VPN protect callback availability
  - network binding support
- [ ] Propagate capability failures as structured results instead of silently
  falling back to unmodified sends.
- [ ] Reclassify non-root TTL-dependent tactics as opportunistic only.
- [ ] Mark capability-based skips distinctly in diagnostics, strategy probes,
  and learning telemetry.
- [ ] Remove capability-blind winner promotion from quick scans and home
  analysis when the tactic was never actually emitted as designed.
- [ ] Add regression tests proving TTL failure, protect failure, and raw-path
  unavailability do not collapse into a generic "network blocked" outcome.

**Production demotions to make in this phase**

- [ ] Move non-root `Disorder` and generic `Fake` out of the primary candidate
  set unless capability checks pass first.
- [ ] Move fake-RST-heavy flows to rooted or lab-only candidates by default.
- [ ] Demote fixed duplicate `HostFake` behavior from default candidate sets.

**Done when**

- Every candidate execution records whether the intended emitter path was used.
- TTL or raw-path failures appear as explicit capability outcomes.
- Strategy learning ignores failed-emitter runs when ranking tactics.

## Workstream 2: Unified First-Flight IR And Planner Refactor

**Status:** [ ] Not started
**Priority:** P0
**Why now:** TLS prelude mutation, TCP step planning, and QUIC packet shaping
currently live in separate planners. That makes cross-layer strategies harder
to express and encourages feature-specific special cases.

**Primary areas:**
- `native/rust/crates/ripdpi-desync/`
- `native/rust/crates/ripdpi-config/`
- `native/rust/crates/ripdpi-packets/`
- `native/rust/crates/ripdpi-runtime/`

**Target architecture**

- Semantic first-flight IR:
  - SNI / host data
  - ALPN
  - supported versions / groups / key shares
  - GREASE state
  - ECH / ECH-GREASE state
  - TLS record boundaries
  - QUIC CRYPTO frame layout
  - desired datagram / segment boundaries
- Shaping pass:
  - extension permutation
  - padding and size targets
  - key-share / supported-group shaping
  - ALPN-aware templates
  - record / CRYPTO split decisions
- Emission pass:
  - TLS records
  - QUIC CRYPTO frames
  - TCP segment plan
  - UDP datagram plan
- Capability lowering pass:
  - non-root safe emission
  - rooted exact emission
  - lab-only raw paths

**Tasks**

- [ ] Define a shared first-flight IR crate or module boundary.
- [ ] Add parsers that normalize HTTP, TLS ClientHello, and QUIC Initial into
  the same high-level representation.
- [ ] Migrate existing `TlsRec`, `TlsRandRec`, split, fake, and QUIC prelude
  logic onto the new IR.
- [ ] Move terminal-step and emitter-specific restrictions out of semantic
  planning and into lowering.
- [ ] Preserve existing JSON / DSL / strategy-pack compatibility while adding a
  richer internal representation.
- [ ] Add golden fixtures for:
  - TLS record fragmentation
  - ALPN changes
  - QUIC CRYPTO frame splits
  - ECH / GREASE-preserving rewrites
- [ ] Document the new planner stages and invariants.

**Done when**

- TLS and QUIC first-flight tactics share one planning pipeline.
- Root vs non-root differences are handled in lowering, not in tactic design.
- Existing production tactics can be expressed without planner-specific hacks.

## Workstream 3: QUIC Initial Shaping Subsystem

**Status:** [ ] Not started
**Priority:** P1
**Why now:** The current QUIC logic has useful primitives but does not yet own
the full wire image of the Initial flight. Modern QUIC-aware DPI hinges on how
Initial packets, CRYPTO frames, padding, and version behavior are laid out.

**Primary areas:**
- `native/rust/crates/ripdpi-desync/src/plan_udp.rs`
- `native/rust/crates/ripdpi-runtime/src/runtime/udp.rs`
- `native/rust/crates/ripdpi-packets/`
- diagnostics candidate generation and runner logic

**Tasks**

- [ ] Implement a real QUIC Initial packetizer with control over:
  - CRYPTO offsets
  - frame ordering
  - PADDING placement
  - datagram count
  - coalescing
  - 1200-byte expansion rules
  - v1 / v2 selection
  - version-negotiation decoys
- [ ] Rework `DummyPrepend`, `QuicCryptoSplit`, `QuicSniSplit`, and
  `QuicMultiInitialRealistic` to emit through the packetizer rather than by
  hand-built packet families.
- [ ] Add realistic browser-like QUIC profiles instead of generic fake bursts.
- [ ] Demote or remove low-evidence QUIC tactics from production defaults:
  - `FakeBurst`
  - `QuicCidChurn`
  - `QuicPacketNumberGap`
- [ ] Add server capability and acceptance probes for:
  - QUIC version support
  - QUIC v2 support
  - compatible version negotiation behavior
  - handshake acceptance under split/coalesced Initial layouts
- [ ] Extend diagnostics exports so QUIC winners record the exact Initial
  layout family that succeeded.

**Done when**

- QUIC tactics are described as Initial-layout strategies rather than ad hoc
  packet mutations.
- Diagnostics can tell which QUIC layout dimensions mattered.
- Weak QUIC mutations are no longer part of the production critical path.

## Workstream 4: DNS Oracle And Resolver-Policy Hardening

**Status:** [ ] Not started
**Priority:** P1
**Why now:** The current system treats encrypted DNS too much like a ground
truth oracle. That risks false positives on CDN-heavy targets and makes oracle
failures feed bad data back into strategy selection.

**Primary areas:**
- `native/rust/crates/ripdpi-dns-resolver/`
- `native/rust/crates/ripdpi-monitor/src/strategy.rs`
- `native/rust/crates/ripdpi-monitor/src/dns_analysis.rs`
- tunnel DNS intercept and diagnostics surfaces

**Tasks**

- [ ] Replace single-oracle logic with multi-oracle confidence scoring.
- [ ] Key resolver health and fallback memory by network identity instead of a
  global process-wide ordering.
- [ ] Track ranking separately for:
  - Wi-Fi vs cellular
  - IPv4 vs IPv6
  - resolver operator
  - transport protocol
- [ ] Add RRset-aware comparison classes:
  - exact match
  - compatible divergence
  - suspicious divergence
  - likely sinkhole
  - NXDOMAIN mismatch
  - oracle unavailable
- [ ] Score oracle health before using an encrypted result as a diagnostic
  reference.
- [ ] Quarantine oracle failures so they do not retrain the strategy system.
- [ ] Add DDR / HTTPS / SVCB-driven resolver discovery where practical.
- [ ] Use hedged queries only for bootstrap, failover, and diagnostics rather
  than as the default resolution path.
- [ ] Add tests for CDN variance, IPv4/IPv6 asymmetry, partial overlap, and
  multi-resolver disagreement.

**Done when**

- `dns_substitution` is no longer emitted from a simple no-overlap rule.
- Resolver ranking is network-scoped.
- Oracle failures are visible and do not pollute strategy recommendations.

## Workstream 5: TLS Shaping, Browser-Family Templates, And ECH

**Status:** [ ] Not started
**Priority:** P1
**Why now:** The strongest long-term TLS evasions are still the spec-valid,
parser-aware ones, but they need to look coherent. Randomized fake bytes are
not enough against modern fingerprinting.

**Primary areas:**
- `native/rust/crates/ripdpi-desync/`
- `native/rust/crates/ripdpi-packets/`
- resolver bootstrap and HTTPS/SVCB handling
- Android TLS / ECH integration points

**Tasks**

- [ ] Introduce browser-family TLS templates:
  - Chrome Android-like
  - Chrome desktop-like
  - Firefox-like
  - ECH-capable variants
- [ ] Add first-class controls for:
  - extension order / permutation families
  - GREASE placement
  - supported-groups shaping
  - key-share shaping
  - ALPN-aware handshake templates
  - record-size choreography
- [ ] Add controlled HelloRetryRequest-oriented tactics where server behavior
  makes them useful.
- [ ] Integrate ECH / ECH-GREASE planning with DNS bootstrap instead of treating
  ECH as a separate afterthought.
- [ ] Add Android-specific ECH integration research and implementation tasks:
  - API availability checks
  - configuration bootstrap
  - policy gating
  - fallback behavior
- [ ] Detect and surface proxy-mode interactions that suppress browser-native
  ECH so the product can compensate or warn appropriately.
- [ ] Replace generic fake packet families with coherent client-profile
  families; keep purely random fake data only for lab use.
- [ ] Build a server-acceptance corpus across major CDNs and stacks for every
  new TLS template family.

**Done when**

- TLS tactics can be described as coherent client templates plus shaping
  choices.
- ECH is planned together with DNS bootstrap and ALPN.
- Random fake-client generation is no longer a production default.

## Workstream 6: Strategy Evaluation And Learning Redesign

**Status:** [ ] Not started
**Priority:** P1
**Why now:** One-domain elimination and context-light learning are too coarse
for a system whose success varies by network, route, host class, and transport.

**Primary areas:**
- `native/rust/crates/ripdpi-monitor/src/engine/runners/strategy.rs`
- `native/rust/crates/ripdpi-runtime/src/strategy_evolver.rs`
- runtime policy and learned-host machinery
- diagnostics summaries and exports

**Tasks**

- [ ] Replace the one-domain qualifier round with stratified pilot evaluation.
- [ ] Define target buckets for evaluation:
  - CDN / hosting family
  - TLS vs QUIC
  - ALPN class
  - ECH-capable vs non-ECH
  - domestic vs foreign reachability set
- [ ] Keep niche-winner pools instead of permanently pruning tactics that lose
  early on one class of target.
- [ ] Expand reward signals beyond binary success:
  - success rate
  - added RTT / latency tax
  - acceptance stability
  - failure variance
  - energy / cost budget
  - detectability / anomaly budget
- [ ] Replace the current flat session-level exploration model with a
  hierarchical contextual bandit:
  - choose strategy family first
  - choose family parameters second
  - reset or forget on network change
- [ ] Feed network identity, transport capability, ALPN, ECH availability,
  resolver health, and root/non-root state into the learning context.
- [ ] Ensure failed emitter paths are excluded from tactic scoring.
- [ ] Version exported diagnostics so evaluation methodology changes do not
  confuse old and new reports.

**Done when**

- Winners are chosen from stratified evidence, not one target.
- Learning is network-aware and capability-aware.
- Reports explain why a tactic won, not just that it won.

## Workstream 7: Root/Non-Root Emitter Rationalization

**Status:** [ ] Not started
**Priority:** P2
**Why now:** Root should widen the set of exact emitters, not create a separate
strategy universe. The production rooted set should also be much smaller than
the research set.

**Primary areas:**
- `native/rust/crates/ripdpi-runtime/src/platform/`
- `native/rust/crates/ripdpi-root-helper/`
- candidate generation
- advanced settings / diagnostics labeling

**Tasks**

- [ ] Define three emitter tiers:
  - non-root production
  - rooted production
  - lab / diagnostics only
- [ ] Audit every existing tactic into one of those tiers.
- [ ] Keep rooted production focused on current-evidence techniques:
  - exact fragmentation
  - exact packet ordering
  - exact flag control where proven useful
- [ ] Move exotic or weak-evidence tactics to lab-only:
  - fake-RST-heavy experiments
  - generic weird-flag combinations without recent wins
  - broad fragmentation variants without active measurements
- [ ] Make the planner choose one tactic description and let the emitter tier
  determine whether the exact realization is available.
- [ ] Add explicit UI / diagnostics labeling when a tactic required root or was
  downgraded from exact to approximate emission.

**Done when**

- Root changes emission precision, not the high-level strategy taxonomy.
- Production rooted tactics have current evidence behind them.
- Lab-only tactics remain available for research without polluting defaults.

## Workstream 8: Android Networking Hardening

**Status:** [ ] Not started
**Priority:** P2
**Why now:** Android's supported networking model gives explicit tools for
network binding, bypass, and VPN upstream management. The product should model
those state changes directly instead of relying on implicit behavior.

**Primary areas:**
- `core/service/`
- `core/engine/`
- Android VPN lifecycle and protect/socket binding integration

**Tasks**

- [ ] Treat `VpnService.protect()` failure and VPN-rights revocation as
  first-class runtime events.
- [ ] Audit and harden upstream socket binding:
  - `protect()` usage
  - `bindSocket()` / network binding where appropriate
  - `setUnderlyingNetworks()` updates on handover
- [ ] Add explicit handover-state transitions so strategy and resolver state can
  reset or revalidate on network change.
- [ ] Audit `allowBypass()` / address-family bypass behavior for leakage risks.
- [ ] Surface proxy-mode limitations that affect browser-native TLS/ECH
  behavior.
- [ ] Add Android integration tests for:
  - Wi-Fi to cellular handover
  - VPN revocation
  - upstream-network loss
  - protect callback unavailability
  - proxy-mode compatibility

**Done when**

- Android network changes are visible to the runtime and diagnostics.
- Proxy-mode and VPN-mode differences are explicit product concepts.
- Unsupported platform behavior no longer masquerades as censorship behavior.

## Workstream 9: Measurement, Lab Tooling, And Rollout Gates

**Status:** [ ] Not started
**Priority:** P2
**Why now:** New bypass work should ship only when backed by server acceptance,
device coverage, and detectability measurements. Otherwise the system becomes
more adaptive on paper and less predictable in practice.

**Primary areas:**
- diagnostics and export pipeline
- CI / nightly jobs
- local measurement tooling
- rooted lab flows

**Tasks**

- [ ] Build a server-acceptance corpus that covers:
  - major CDNs
  - different TLS stacks
  - QUIC-capable and non-QUIC endpoints
  - ECH-advertising and non-ECH domains
- [ ] Add a repeated test matrix over:
  - Wi-Fi and cellular
  - IPv4 and IPv6
  - rooted and non-rooted devices
  - proxy mode and VPN mode
- [ ] Add detectability-oriented metrics in addition to reachability metrics.
- [ ] Expand diagnostics exports so they include:
  - network identity bucket
  - emitter tier
  - capability snapshot
  - target bucket
  - acceptance / detectability metrics
- [ ] Add lab-only packet-capture and replay tooling where it materially helps
  evaluate emitters.
- [ ] Define rollout gates for new tactics:
  - acceptance threshold
  - latency budget
  - instability budget
  - detectability budget
  - Android compatibility budget

**Done when**

- Every new production tactic has acceptance and rollout evidence.
- Diagnostics reports contain enough context to reproduce a win or failure.
- Lab-only tools support research without expanding production complexity.

## Tactic Triage Matrix

### Keep And Strengthen

- [ ] TLS record fragmentation and record-boundary choreography
- [ ] QUIC pre-Initial dummy datagrams
- [ ] QUIC CRYPTO / SNI splitting
- [ ] version-negotiation and version-shaped QUIC tactics backed by capability
  checks
- [ ] encrypted DNS failover as a product feature, but with confidence scoring

### Demote From Production Defaults

- [ ] non-root TTL-based disorder as a primary strategy
- [ ] generic fake packet families
- [ ] fixed duplicate-host fake flows
- [ ] QUIC `FakeBurst`
- [ ] QUIC CID churn without named evidence
- [ ] QUIC packet-number gap tricks without named evidence

### Keep For Lab / Diagnostics Only

- [ ] malformed or low-acceptance TLS tricks
- [ ] fake-RST-heavy experiments
- [ ] exotic flag combinations without current field wins
- [ ] broad fragmentation permutations that lack acceptance data

## Sequencing Notes

- Workstream 1 must land before new tactic expansion, otherwise measurements
  remain untrustworthy.
- Workstream 2 should start before major TLS or QUIC feature additions so new
  work lands on the long-term planner architecture.
- Workstreams 3, 4, and 5 can overlap once the first-flight IR shape is stable.
- Workstream 6 depends on cleaner diagnostics from Workstreams 1 and 4.
- Workstream 8 should progress in parallel with Workstreams 1 and 4 because
  Android runtime state directly affects both emitter capabilities and resolver
  quality.
- Workstream 9 is continuous and should gate promotion from lab to production.

## First Implementation Slice

The first slice should be intentionally narrow:

1. Add explicit capability results and remove silent TTL degradation.
2. Reclassify TTL-dependent non-root tactics as opportunistic in diagnostics and
   candidate generation.
3. Replace DNS "no overlap = substitution" with richer outcome classes and
   oracle-health gating.
4. Define the first-flight IR boundary and migrate one representative TLS path
   and one QUIC path onto it.
5. Freeze addition of new production fake-packet variants until the IR and
   measurement gates are in place.

That slice reduces current risk without requiring a full rewrite upfront.
