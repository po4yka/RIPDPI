# dpi31-http-integrity Specification

## Purpose
Preserve meaningful HTTP request bytes during opt-in mutation and report mutation outcomes without changing TCP chain disposition semantics.

## Requirements

### Requirement: REQ-DPI31-UA — Preserve meaningful request data

Method EOL MUST preserve significant User-Agent value bytes and all body bytes. It MUST leave the input unchanged when length-preserving mutation would require deletion of significant bytes.

#### Scenario: No disposable User-Agent whitespace

- **WHEN** Method EOL receives a complete request whose User-Agent lacks two trailing SP or HTAB bytes
- **THEN** the modifier leaves the request unchanged

#### Scenario: Disposable User-Agent whitespace

- **WHEN** Method EOL receives a complete request with enough trailing User-Agent optional whitespace
- **THEN** significant User-Agent bytes and the full body remain unchanged
- **AND** only optional whitespace can be reclaimed

### Requirement: REQ-DPI31-PREFIX — Decline repeated prefix insertion

Method EOL MUST leave an already-prefixed request unchanged rather than add another prefix that exceeds the current parser contract.

#### Scenario: Request already has a leading empty line

- **WHEN** a supported request has a nonzero method-start offset before Method EOL
- **THEN** Method EOL leaves the request unchanged
- **AND** it does not discard the current protocol metadata through repeated prefix insertion

### Requirement: REQ-DPI31-RECEIPT — Report HTTP mutation independently

Execution results MUST expose an HTTP mutation outcome independently of TCP chain disposition. Required receipt consumers MUST preserve the distinction between absent legacy or unknown evidence, explicitly unchanged bytes including a declined mutation, and changed bytes without disclosing request contents.

#### Scenario: Modifier changes bytes while send steps are inactive

- **WHEN** HTTP mutation changes bytes but no TCP send step or TLS prelude applies
- **THEN** the result records the HTTP change independently
- **AND** the existing chain ActivationSkipped semantics remain valid

#### Scenario: Legacy receipt omits the additive evidence

- **WHEN** a receipt does not contain HTTP mutation evidence
- **THEN** consumers preserve the absence as unknown evidence
- **AND** they do not infer that the modifier was unconfigured or that delivery succeeded

#### Scenario: Modifier makes no change or declines input

- **WHEN** HTTP mutation leaves bytes unchanged or declines an unsafe input
- **THEN** the result records unchanged bytes and distinguishes them from changed bytes
- **AND** it does not claim that wire delivery or external compatibility was verified

### Requirement: REQ-DPI31-BOUNDARY — Keep repair scope defensive

The repair MUST preserve current activation and privilege gates. It MUST NOT add protocol support, bypass techniques, required services, sensitive payload logs, or weaker validation.

#### Scenario: Unsupported or incomplete request

- **WHEN** the existing HTTP mutation gates do not accept the payload
- **THEN** the repair preserves the existing guarded path
- **AND** it does not activate a new mutation technique
