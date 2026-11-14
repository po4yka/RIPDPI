## ADDED Requirements

### Requirement: REQ-HOSTFAKE-DEFAULT — Describe the existing default layout

The hostfake ordering contract MUST state that order0 with duplicate mode sends the whole fake host, real host parts, then the whole fake host again. Both fake offsets MUST be zero. With a resolved midhost, sequential mode MAY change the fake geometry to two regions. The implementation MUST preserve the existing layouts.

#### Scenario: A host has a middle split

- **GIVEN** a real host ABCDE and split2
- **WHEN** order0 uses duplicate mode
- **THEN** the layout is fake-whole, AB, CDE, fake-whole with offsets0,0,2,0
