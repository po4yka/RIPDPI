## Purpose

Let the strategy registry continue past a step that cannot handle the current packet without changing the meaning of a successfully handled no-op.

## ADDED Requirements

### Requirement: REQ-STRATEGY-STEP-SKIP — Continue after a skipped step

A matching strategy that cannot apply to the current packet or runtime MUST report `Skipped`. The registry MUST restore the plan to its pre-step state and try the next matching step, regardless of the skipped step's failure policy.

#### Scenario: Capability or packet prevents a built-in step

- **GIVEN** an IPv6 or UDP step matches and a later split step is configured
- **WHEN** the first step cannot apply because its capability or packet precondition is absent
- **THEN** the later split step supplies the final action and no action from the skipped step remains

### Requirement: REQ-STRATEGY-STEP-HANDLED — Preserve terminal handling

A strategy that handles the current packet MUST report `Applied` even if it deliberately emits no packet action. The registry MUST stop at that step and use its verdict.

#### Scenario: Successful Lua no-op

- **GIVEN** a Lua step returns successfully without a packet action and a later split step is configured
- **WHEN** the registry executes the chain
- **THEN** the Lua step remains terminal and the split step does not run

#### Scenario: Explicit Lua verdict

- **WHEN** Lua returns an explicit pass, modify, or drop verdict without an action
- **THEN** the registry preserves that verdict and does not run a later step

### Requirement: REQ-STRATEGY-STEP-ERROR — Keep failure policies separate

The registry MUST apply `on_fail` only to errors. A skipped step MUST NOT trigger fallback-plain or drop failure policy.

#### Scenario: Skipped step configured to drop on failure

- **GIVEN** a step has `on_fail: drop` and a later applicable step exists
- **WHEN** the first step reports `Skipped`
- **THEN** the later step runs instead of dropping the packet
