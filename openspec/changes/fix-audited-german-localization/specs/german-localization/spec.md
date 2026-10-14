## Purpose

German users can read controls, privacy disclosures and diagnostic summaries without losing technical meaning or raw evidence.

## ADDED Requirements

### Requirement: REQ-DE-RESOURCES — Correct German captions

The app MUST correct the 107 active and five unused confirmed German audit entries while preserving format arguments and technical identifiers.

#### Scenario: Privacy and recovery text

- **WHEN** a German user reads export privacy, support-link warnings or pause recovery
- **THEN** the text explains sensitive-field removal and the actual recovery action in German.

### Requirement: REQ-DE-DYNAMIC — Localize diagnostic presentation

The app MUST localize known Telegram status badges and readable share-summary prose. Unknown statuses and raw evidence MUST remain intact.

#### Scenario: Known and unknown results

- **WHEN** a Telegram result has a known status or an unknown custom status
- **THEN** the known summary caption is localized and the custom value and expanded evidence remain exact.

### Requirement: REQ-DE-MEASUREMENTS — Respect locale and throughput units

The app MUST format Telegram and sparkline numbers with the selected app locale. Telegram throughput MUST convert native bytes per second into displayed bit-rate units without integer overflow.

#### Scenario: Language switch and rate boundaries

- **WHEN** the selected app language changes from English to German and a transfer reports 1000000 bytes per second
- **THEN** the displayed decimal separator changes and the rate is 8.0 Mbps in English and 8,0 Mbps in German, with byte and bit units consistent below thresholds.

### Requirement: REQ-DE-PARITY — Preserve all locale contracts

Every new resource key MUST be present in all ten locales in the same commit. Stored values, wire schemas, native producers and protocols MUST remain unchanged.

#### Scenario: Combined validation

- **WHEN** resource, behavior, lint and repository gates run on the combined tree
- **THEN** locale parity and format contracts pass and untranslated German controls do not return.
