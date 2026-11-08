## Purpose

Preserve DNS destination ownership and truthful response metadata in the non-root native tunnel interceptor.

## ADDED Requirements

### Requirement: REQ-VPN05-ISSUED-OWNER — Preserve issued address ownership

The implementation MUST NOT assign an issued synthetic IP to a different mapping during the same native session. Live cache memory MUST remain bounded, active flow leases MUST survive eviction, and exhausted address space MUST fail closed without removing a live mapping.

#### Scenario: A flow starts after cache churn or generation reset

- **WHEN** a client uses an address from an earlier answer after eviction or reset
- **THEN** it resolves to its original host and real IP or is rejected, and never resolves to another owner

#### Scenario: The synthetic address space is exhausted

- **WHEN** a new mapping needs an address after all permitted addresses have been issued
- **THEN** allocation fails without aliasing a retired address or changing existing live mappings

### Requirement: REQ-VPN05-RESPONSE-LEASES — Preserve each successful answer mapping

The implementation MUST hold every A mapping used by a response until rewrite and serialization complete. Temporary leases MUST be released on success and failure without releasing existing flow leases.

#### Scenario: One available cache entry cannot hold two new answers

- **WHEN** an upstream response needs two distinct mappings but only one entry can be evicted
- **THEN** rewrite fails and the interceptor returns SERVFAIL instead of publishing an incomplete mapping set

#### Scenario: Repeated and cached records share active flow leases

- **WHEN** a rewrite contains repeated records or a mapping already held by an active flow
- **THEN** completion releases only its temporary leases and preserves the active flow lease

### Requirement: REQ-VPN05-STALE-ISOLATION — Isolate stale direct results

The implementation MUST reject stale direct results without deleting mappings accepted after the current generation was synchronized.

#### Scenario: A stale direct error follows a fresh encrypted response

- **WHEN** the direct lease is revoked, an encrypted response is accepted, and an older direct result arrives
- **THEN** the old request fails and the new encrypted mapping remains usable

### Requirement: REQ-VPN05-RESERVED-LISTENER — Reserve the interceptor address

The implementation MUST exclude the configured interceptor address from synthetic allocation and reject a live cache capacity that cannot fit in the remaining permitted address space.

#### Scenario: Allocation reaches the interceptor address

- **WHEN** sequential allocations pass the configured listener offset
- **THEN** the allocator skips that address and retains correct reverse mappings for other addresses

### Requirement: REQ-VPN05-SYNTHESIS-AD — Clear AD after content changes

The implementation MUST clear AD when A records are rewritten or AAAA records are removed. It MUST preserve AD on responses whose content is unchanged. This requirement does not add DNSSEC validation.

#### Scenario: An authenticated upstream answer is rewritten

- **WHEN** a response has AD and its A or AAAA content is changed
- **THEN** the returned response has AD cleared

#### Scenario: An authenticated negative or non-address response is unchanged

- **WHEN** a response has AD and no content changes are required
- **THEN** AD remains set
