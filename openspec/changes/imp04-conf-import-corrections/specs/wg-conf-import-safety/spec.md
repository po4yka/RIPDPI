## ADDED Requirements

### Requirement: REQ-WG-CONF-ROUTES — Preserve explicit import policy

Conf import MUST preserve all list entries. Standalone activation MUST require an explicit usable route from a conf. Omitted URI routes MUST keep their existing defaults.

#### Scenario: Repeated and missing routes

- **WHEN** a conf has repeat entries or has no allowed routes
- **THEN** entries are accumulated and a profile without usable routes cannot be saved by Connect.

### Requirement: REQ-WG-CONF-ACTIVATION — Check before save

The editor MUST validate the activation request before save. A conf MUST select UDP and clear the previous WS URL. The last keepalive MUST replace both previous bounds.

#### Scenario: Replace a draft

- **WHEN** a user imports a conf over a WS draft and Connect sees invalid data
- **THEN** the preview uses UDP and the invalid request cannot replace saved data.

### Requirement: REQ-WG-CONF-INPUT — Use one safe input policy

The importer MUST use the same section and comment grammar on all paths, enforce the byte limit, perform one full parse, and report failures without credential text. It MUST report ignored option names and must never run host scripts.

#### Scenario: Commented and ignored settings

- **WHEN** a supported profile has a BOM, a comment after a section and ignored settings
- **THEN** each entry point recognizes it and the preview identifies the ignored options without values.

### Requirement: REQ-WG-CONF-DIALECT — Detect supported extensions and reject ambiguity

A recognized AWG field or keepalive range MUST identify the AWG dialect. A second Interface MUST fail. An empty optional v3 range or header key MUST mean unset.

#### Scenario: An AWG range belongs to a peer

- **WHEN** a profile contains an AWG keepalive range and no Interface extension
- **THEN** the model identifies AWG and preserves the interval.
