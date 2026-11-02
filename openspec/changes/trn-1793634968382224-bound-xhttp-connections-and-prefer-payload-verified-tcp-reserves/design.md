## Context

The native XHTTP pool already enforces a carrier limit. Imported profiles do not retain that limit. The payload selector already verifies controls and measures one reserve per pass.

## Goals / Non-Goals

- Goal: preserve a typed pool limit and recover through TCP after UDP payload failure.
- Non-goal: new dependencies, public control services, automatic fingerprint rotation, deployment, or device mutation.

## Decisions

- Accept only xmux maxConnections 1–4 plus zero hMaxRequestTimes, hMaxReusableSecs and cMaxReuseTimes in stream-up. Use one optional connection-limit field internally and the existing native pool.
- Prioritize TCP only when an active UDP profile fails health verification. Keep quality challenger order when the active member is healthy.
- Share one XHTTP client across outer sessions. A cancelled stream establishment stops the chosen carrier; per-carrier Hyper task ownership closes its socket before pool capacity is reused. Completed request tasks are pruned during carrier reuse.
- Keep payload-failover schema version 2. No new policy flags are needed.

## Contracts and ownership

- One writer owns client subscription models, relay storage, Kotlin wire DTOs, Rust relay configuration/builders and XHTTP carrier ownership and selector tests in this worktree.
- The deployment repository owns producer changes and its canonical paired fixture. This client vendors that fixture.
- No protobuf, Cargo.lock, locale, registry or golden blessing changes are required.

## Risks / Trade-offs

- Cross-layer field loss: assert import, persistence, runtime rendering and real native carrier bounds.
- Candidate starvation: keep failed-reserve rotation and retain all-failure backoff.
- Independent network paths are not inferred from protocol differences.

## Migration Plan

New optional fields retain untuned defaults. Invalid explicit tuning refuses. No live rollout is included. Validate data/runtime/service and selector tests plus locked Rust relay and XHTTP tests through build-gate; run the required static analysis and independent read-only review before commit.
