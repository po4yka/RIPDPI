# ADR-004: Emitter Tier Taxonomy

**Status:** Proposed | 2026-04-18
**Deciders:** RIPDPI maintainers

## Context

RIPDPI currently mixes three different concerns in one place:

- high-level tactic taxonomy (`split`, `hostfake`, `seqovl`, `ipfrag2`, QUIC
  layout steps)
- capability gating (`ttl_write`, raw TCP, TCP_REPAIR, root helper)
- research-only versus production-ready candidate selection

That creates two problems. First, rooted and non-root execution look like
different tactic universes even when they are the same semantic tactic with a
different emitter realization. Second, weak-evidence variants such as fake-RST
experiments or broad IPv6 fragmentation permutations leak into the normal
candidate pool without an explicit label.

## Decision

Adopt three explicit emitter tiers and classify tactic kinds against them:

- `non_root_production`
- `rooted_production`
- `lab_diagnostics_only`

The tier is a property of the tactic or candidate, not of the current device
state. Runtime capability checks determine whether the exact emitter path is
available, but they do not change the tactic taxonomy itself.

Step-kind classification:

- TCP `Split`, `SynData`, `Disorder`, `Fake`, `FakeSplit`, `FakeDisorder`,
  `HostFake`, `Oob`, `Disoob`, `TlsRec`, `TlsRandRec`:
  `non_root_production`
- TCP `SeqOverlap`, `MultiDisorder`, `IpFrag2`:
  `rooted_production`
- TCP `FakeRst`:
  `lab_diagnostics_only`
- UDP QUIC layout steps stay `non_root_production`
- UDP `IpFrag2Udp`:
  `rooted_production`

Candidate-level overrides may demote specific parameterizations to
`lab_diagnostics_only` even when the underlying step kind is production-worthy.
Examples:

- fake TCP flag experiments (`SYN|FIN`, `PSH|URG`)
- broad IPv6 fragmentation permutations
- full-matrix-only alt-order and activation-window experiments

## Rationale

This keeps the planner semantic. A rooted device widens emitter precision, but
it should not invent a separate strategy vocabulary. Production rooted tactics
remain a narrow set with current evidence behind them, while lab-only variants
stay available for research and diagnostics without affecting default
recommendations.

The tier model also gives diagnostics a stable way to explain why a tactic was
skipped or downgraded:

- rooted production tier unavailable
- lab-only tier unavailable
- exact rooted emitter downgraded to an approximate fallback

## Consequences

Positive:

- candidate generation can keep production pools narrower and push weak-evidence
  variants into full-matrix or lab-only suites
- diagnostics can label rooted-only or downgraded execution explicitly
- future module extraction has a stable seam for emitter/capability ownership

Negative:

- candidate builders need an explicit override table for research-only variants
- recommendation/report contracts gain more emitter metadata and therefore more
  compatibility surface

## Owner

`native/rust/crates/ripdpi-monitor/src/candidates.rs`
