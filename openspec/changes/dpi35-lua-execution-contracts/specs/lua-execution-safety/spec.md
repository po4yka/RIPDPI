## Purpose

Keep sandbox Lua execution consistent across user configuration, live flows and actual payload handling.

## ADDED Requirements

### Requirement: REQ-LUA-TEXT — Text-only sandbox input

The sandbox MUST reject binary chunks at every external load and validation entry point.

#### Scenario: Text-only sandbox input

- **WHEN** the relevant Lua operation runs with its supported input
- **THEN** A text script is accepted and a trusted valid precompiled chunk is rejected before execution.

### Requirement: REQ-LUA-MODIFY — Observable payload modification

A supported MODIFY result MUST emit its changed payload, while PASS preserves the original. Packet TCP changes MUST preserve supported transport constraints.

#### Scenario: Observable payload modification

- **WHEN** the relevant Lua operation runs with its supported input
- **THEN** A TCP or UDP payload changes from abcd to WXYZ and independently decoded output contains WXYZ.

### Requirement: REQ-LUA-FLOW — Stable live-flow lifetime

The engine MUST retain at most 1024 admitted connection states. On capacity overflow it MUST bypass new flows until engine replacement. It MUST NOT forget admitted state or activate bypassed flows midway.

#### Scenario: Stable live-flow lifetime

- **WHEN** the relevant Lua operation runs with its supported input
- **THEN** After more than 2048 other flow IDs, an admitted flow continues its count without repeating first-packet output.

### Requirement: REQ-LUA-DEPENDENCIES — Complete bundled script input

UI-generated configuration MUST include the selected bundled script and its required libraries in load order.

#### Scenario: Complete bundled script input

- **WHEN** the relevant Lua operation runs with its supported input
- **THEN** Selecting zapret-antidpi.lua saves zapret-lib.lua before zapret-antidpi.lua and a fresh runtime executes the selected function.

### Requirement: REQ-LUA-ACTIVATION — Visible activation failure

Invalid Lua configuration MUST fail TUN activation before readiness. Lua assets MUST be present for the chosen socket or TUN owner. Runtime on_fail policies MUST remain unchanged.

#### Scenario: Visible activation failure

- **WHEN** the relevant Lua operation runs with its supported input
- **THEN** A missing or invalid script returns an activation error rather than removing the rule and passing traffic.

### Requirement: REQ-LUA-CLOCK — Compatible clock results

The sandbox clock_gettime function MUST return seconds and nanoseconds as separate integers.

#### Scenario: Compatible clock results

- **WHEN** the relevant Lua operation runs with its supported input
- **THEN** The result has arity two, seconds match the host clock and nanoseconds are in the range zero to 999999999.
