# standard-components/parse-import-deep-links-with-uri Specification

## Purpose
Import links must separate query from fragment and accept only complete HTTPS subscription targets. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## Requirements

### Requirement: REQ-IMPORT-DEEP-LINKS-WITH-URI-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: The outer #label is not part of the URL extracted from ripdpi://import?sub=https%3A%2F%2Fexample.com%2Fsub#label.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** The outer #label is not part of the URL extracted from ripdpi://import?sub=https%3A%2F%2Fexample.com%2Fsub#label.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** An incomplete target https://, a non-HTTPS target, and malformed escapes return typed errors without navigation or network access.

### Requirement: REQ-IMPORT-DEEP-LINKS-WITH-URI-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep sub before url, first-value duplicate handling, typed encoding errors, subscription versus one-shot intent, and Hiddify nested URL forms.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** A SingBox name #A+B remains A+B; existing provider link fixtures and import intent still pass.
- **AND** Keep sub before url, first-value duplicate handling, typed encoding errors, subscription versus one-shot intent, and Hiddify nested URL forms.
