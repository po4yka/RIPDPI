## Context

The deployment emits a standard URL-test group. RIPDPI imports it into persisted
selector policy, starts ephemeral protected relays for measurements and commits
selection through a generation-fenced reload coordinator. The existing bounded
HTTP verifier accepts empty successful responses. Reuse this runtime ownership.

## Goals / Non-Goals

- Goal: opt-in payload-aware automatic failover with a fully consumed, bounded contract.
- Goal: preserve the live encrypted path and existing manual/network-generation fences.
- Non-goal: claim ordinary sing-box HEAD checks validate payloads, deploy controls, change VPN capture or add a daemon.

## Decisions

- Parse `ripdpi.payload_failover` into a typed persisted policy attached to the matching selector. Require all fields, schema version 1, exactly two distinct HTTPS origins, expected bytes 32768–65536 and lowercase SHA-256.
- Keep unsupported custom properties out of URL-test outbounds. Absence of the extension means the existing ordinary policy; a malformed present extension refuses the import before alternate-format/AWG fallbacks or any profile persistence.
- Reuse candidate relay startup, VPN protection, SOCKS endpoint and cleanup. Structured HTTP requests require status 200, identity encoding, full EOF, exact bytes and digest. Either control can establish health, avoiding a single control outage as a false path failure. No result is labeled censorship.
- Keep per-run health counters scoped to the current network, group and policy. Require failure/recovery thresholds, cooldown and bounded exponential backoff. Use monotonic time, cancellation and a bounded whole-pass deadline. Do not select direct or clear the live encrypted selection on total failure.
- Probe observations are ephemeral; restart deliberately discards health confidence and remeasures. Imported policy and chosen selector remain in existing durable stores. No new raw network identity persistence.

## Contracts and ownership

- Runtime-state owns the import and serialized `SelectorFailover` payload policy; service owns strict HTTP verification; app owns decision state and its lifecycle.
- The deployment repository owns the source JSON schema; this change mirrors it exactly and verifies a coordinated fixture. This writer owns all client edits and the mirror in an isolated worktree.
- No Rust crate, JNI, protobuf, locale or external backend change is required.
- Gates: complete affected module tests, static analysis, architecture health, task contract validation and a real local HTTP fixture exercising body truncation and content mismatches. Hosted CI remains separate from local evidence; physical filtered-network acceptance remains unclaimed.

## Risks / Trade-offs

- Additional traffic and battery cost: bounded bodies, timeout, interval, backoff and pass budget; no unbounded parallel candidate runtimes.
- Control outages and incorrect content: independent origins, exact authenticated body checks and no censorship attribution.
- Stale measurements during reload/network changes: retain the existing scope/selection generation fences and reset state after changes.
- Older clients cannot apply the new opt-in policy. Deployment must issue it only to qualified clients; ordinary official profiles remain unchanged.

## Migration Plan

Existing persisted selector policies have no payload extension and retain their
meaning. Reimporting a qualified bundle installs the complete new policy. To
remove it, reissue and import a bundle without the extension. Runtime capability
does not authorize deployment or populate placeholder public controls.

### Bounded candidate traversal

A pass checks the active eligible member first. Success ends the pass immediately.
Failure permits one reserve probe in rotating declared order. A successful reserve
is observed again on the next pass until the recovery threshold is reached; a
failed reserve advances the cursor. Only observed results update counters. A whole
pass deadline is two candidate budgets, each containing two control timeouts plus
5 seconds readiness and 5 seconds cleanup (140 seconds maximum at the policy's
30-second control ceiling), followed by at most the existing 5-second cancellation
cleanup wait. Deadline cancellation discards the incomplete round and its
consecutive evidence, then advances the cursor without inventing failures for
unobserved candidates. Outage backoff starts only after each eligible reserve has
actually failed in the current scope; a healthy observation clears that coverage.

### Shared probe capacity

Whole payload rounds must acquire shared selector probe capacity before starting the pass deadline. Contending groups wait cancellably without consuming their measurement budget; existing scope and selection fences still discard obsolete results. Serialized rounds retain bounded candidate runtime ownership and permit each failed active exit to test its reserve.
