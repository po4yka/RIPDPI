## ADDED Requirements

### Requirement: REQ-DIA11-STOP Bound strategy UDP work

The engine SHALL stop new QUIC attempts at the stage deadline, scan deadline, or cancellation and SHALL record the corresponding execution disposition.

#### Scenario: Silence exhausts the QUIC stage

- **WHEN** a silent peer uses the stage budget and more targets remain
- **THEN** the engine shall interrupt the stage, preserve completed observations and reserve the remaining scan time for later work

### Requirement: REQ-DIA11-PIVOT Limit the QUIC recommendation to its evidence

The engine SHALL keep first-response evidence separate from confirmed transport viability and SHALL preserve TCP controls when first-response evidence is the only QUIC result.

#### Scenario: The peer returns Version Negotiation

- **WHEN** a v1 probe sees a valid Version Negotiation reply that offers only v2
- **THEN** it shall record the reply without a confirmed transport pivot or a skipped TCP control

### Requirement: REQ-DIA11-DNS Bound the DNS baseline

The DNS baseline SHALL use the stage budget for system and encrypted DNS and SHALL distinguish skipped, unavailable and partial evidence from a clean comparison.

#### Scenario: The request has no eligible DNS targets

- **WHEN** the baseline cannot compare an eligible target
- **THEN** it shall report the baseline as unavailable or not applicable

### Requirement: REQ-DIA11-UDP Correlate the reply during receive

The UDP strategy probe SHALL filter unrelated datagrams inside the receive window and keep an accepted reply tied to its request.

#### Scenario: A peer sends an unrelated reply before a correlated reply

- **WHEN** both datagrams arrive within the receive window
- **THEN** the correlated datagram shall supply the observation

### Requirement: REQ-DIA11-CHECKPOINT Retain completed candidate evidence

A partial checkpoint SHALL retain available candidate summaries and evidence without authorizing a recommendation or a network policy.

#### Scenario: A candidate completes before the next candidate stalls

- **WHEN** the caller can recover only the checkpoint
- **THEN** the checkpoint shall contain completed candidate results and their structured evidence

### Requirement: REQ-DIA11-COVERAGE Limit confidence to measured coverage

A tied set of candidates SHALL qualify for a high-confidence conclusion only when the measured coverage meets the confidence criteria.

#### Scenario: A small subset ties

- **WHEN** four of seventy planned candidates tie
- **THEN** the audit shall not report a high-confidence result for the matrix

### Requirement: REQ-DIA11-PERSIST Align background persistence evidence

The background policy producer SHALL have a reachable acceptance path for eligible, verified reports and SHALL reject incomplete or unverified reports. A manual audit SHALL retain its separate policy.

#### Scenario: A background quick scan completes with qualifying evidence

- **WHEN** eligible measured winners and controls meet the coverage and confidence checks
- **THEN** the existing memory setting and scope gates shall determine whether the policy is stored

### Requirement: REQ-DIA11-CONTROLS Require usable concurrency controls

The classifier SHALL confirm a concurrency interaction only with a usable pre-check and the corresponding post-check.

#### Scenario: Post-checks are skipped

- **WHEN** a burst fails on two targets and their post-checks are skipped
- **THEN** those bursts shall remain observations without supporting a confirmed interaction

### Requirement: REQ-DIA11-OWNER Respect probe route ownership

A strategy run SHALL keep active-path evidence separate from isolated candidate evidence. It SHALL omit direct concurrency probes in an in-path plan.

#### Scenario: An in-path strategy request has concurrency targets

- **WHEN** the request owns an active route
- **THEN** the active-path observation shall use that route and the direct concurrency stage shall be absent

### Requirement: REQ-DIA11-ACCOUNT Describe planned and executed work consistently

The engine SHALL distinguish candidate inventory, bounded work, and skipped execution. It SHALL record the recommendation stage and reject incomplete configured-only task pairs.

#### Scenario: A capped plan finishes

- **WHEN** the engine completes the selected work within the candidate cap
- **THEN** it shall report a completed capped scan and keep omitted inventory separate from deadline skips
