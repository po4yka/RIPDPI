## ADDED Requirements

### Requirement: REQ-ORDERED-SIZE — Validate a complete ordered batch

The ordered sender MUST validate every complete packet against the negotiated MSS and actual route MTU before it sends the first packet. It MUST account for added TCP options without counting normal timestamps twice. It MUST preserve real bytes and the selected sequence, flags, fake role and IPv4 ID policy. An oversized shape that cannot preserve this contract MUST return an explicit error before emission.

#### Scenario: A later span exceeds the packet limit

- **GIVEN** an ordered batch has a short first span and a 4096-byte later span with MSS1460 and MTU1500
- **WHEN** the sender prepares the batch
- **THEN** every packet fits both limits or the complete batch is rejected before the first send

### Requirement: REQ-ORDERED-DISPATCH — Preserve selected exact modes

A non-default FakeSplit or FakeDisorder order or sequence mode MUST select the ordered executor even when fake retransmission is unavailable. A rejected exact mode MUST NOT send real prefix bytes through the generic executor.

#### Scenario: An unsupported host receives a custom order

- **GIVEN** the host has no fake retransmission support and a nonterminal fake split selects a custom order
- **WHEN** the runtime executes the step
- **THEN** the ordered sender reports unsupported before any bytes from that step reach the peer
