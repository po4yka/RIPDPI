## Context

`VpnEncryptedDnsFailoverController` observes cumulative native DNS counters and
requires two consecutive failure events before selecting another encrypted
resolver, except for a small set of catastrophic bootstrap errors. Timeout text
is currently non-catastrophic and is also classified as a persistable blocked
path reason. The diagnostic sequence therefore paid two full timeouts per path
and remembered five transiently timed-out paths as blocked.

## Goals / Non-Goals

- Goal: bound each encrypted resolver's bootstrap timeout cost to one failed
  query before trying the next encrypted candidate.
- Goal: retain timeout-only paths for reconsideration in later sessions.
- Goal: preserve strict encrypted-only DNS and existing post-bootstrap failure
  smoothing.
- Non-goal: change native resolver request deadlines, candidate ordering,
  plaintext DNS policy, or resolver health scoring.
- Non-goal: add parallel resolver racing or new persisted fields.

## Decisions

- Add one centralized timeout classifier and treat it as eager only while
  `queriesSincePathStart <= EagerFailoverMaxQueries`. This reuses the existing
  bootstrap boundary and avoids resolver churn after a path has served traffic.
- Remove timeout from persistable blocked-path reasons. The current session
  still records the path in `attemptedPathKeys`, so failover cannot loop back to
  it before the session resets.
- Keep SNI, certificate, and TLS failures persistable because they are stronger
  path-specific evidence than a timeout.
- Keep `FailoverThreshold = 2` for non-bootstrap timeouts and unclassified
  failures. A global threshold reduction was rejected because it would make an
  established resolver switch after one isolated loss.

## Contracts and ownership

- Kotlin ownership: `core/service`, specifically
  `VpnEncryptedDnsFailoverController` and its unit tests.
- Rust crates: none.
- Public/wire contracts: none.
- JNI and protobuf contracts: none.
- Serialized shared files: none; existing blocked-path records remain readable.
- Security invariant: failover candidates continue to come exclusively from
  `buildEncryptedDnsCandidatePlan`; no plaintext fallback is introduced.

## Risks / Trade-offs

- A transient first-query timeout can switch away from a resolver that would
  succeed on retry. Mitigation: the path is not persisted as blocked and is
  eligible again in a later session.
- Faster sequential failover can exhaust all encrypted candidates sooner during
  a network-wide outage. Mitigation: exhaustion remains fail-closed and never
  installs plaintext DNS.
- Text-based error classification can miss alternate timeout wording.
  Mitigation: cover both `timeout` and `timed out`, matching existing telemetry.

## Migration Plan

No migration is required. Deploy the controller policy with the application;
existing network preference and blocked-path records keep their current schema.
Rollback is a normal revert of the implementation commit. Validation gates are
the focused RED/GREEN controller regression, the full `core/service` unit suite,
`staticAnalysis`, task/OpenSpec strict validation, and hosted CI after push.
Physical-device timing is useful follow-up evidence but is not required to prove
the deterministic controller state transition.
