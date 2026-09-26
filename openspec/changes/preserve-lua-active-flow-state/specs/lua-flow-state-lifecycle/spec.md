## Purpose

Keep Lua connection tables stable for active tunnel flows while bounding their number and releasing tables when the tunnel observes a flow's end.

## ADDED Requirements

### Requirement: REQ-LUA-FLOW-CAPACITY — Preserve admitted state at capacity

The Lua strategy engine MUST NOT remove an existing flow's state to admit another flow. When its state limit is reached, it MUST report an error for a new flow and leave previously admitted state intact. The registry MUST apply the configured `on_fail` policy to that error.

#### Scenario: More than 1024 active flows

- **GIVEN** 1024 distinct flows have Lua connection state
- **WHEN** a 1025th flow reaches the same Lua engine
- **THEN** the new flow follows `on_fail` and a later packet of the first flow sees its earlier state

### Requirement: REQ-LUA-FLOW-CLOSE — Reclaim completed flow state

The TUN integration MUST release Lua state for a flow when it observes that flow's termination. A later flow MUST be able to use the released capacity without inheriting the old state.

#### Scenario: TCP flow closes

- **GIVEN** a TCP flow has Lua connection state
- **WHEN** the TUN integration observes its end
- **THEN** its state is removed and a later flow can use the freed slot

#### Scenario: UDP association closes

- **GIVEN** an association-owned UDP flow has Lua connection state and its association has ended
- **WHEN** the TUN integration processes the close event
- **THEN** the ended flow's state is removed without removing Lua-consumed flows from the same source

#### Scenario: UDP packet has no association

- **GIVEN** a Lua-consumed or locally handled UDP packet has connection state but no owning association
- **WHEN** the configured UDP idle timeout passes without another packet
- **THEN** the idle state is removed, while state owned by a live association remains

### Requirement: REQ-LUA-FLOW-FAILURE — Preserve failure behavior

State-capacity failure MUST preserve the existing packet and strategy failure-policy semantics and MUST NOT silently turn an active flow into a new Lua connection.

#### Scenario: New flow exceeds capacity

- **GIVEN** the Lua state limit is full and the strategy uses fallback-plain on failure
- **WHEN** a new flow is evaluated
- **THEN** its original packet is forwarded and an already admitted flow keeps its state
