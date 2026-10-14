## Purpose

Provide accurate Brazilian Portuguese copy and app-language presentation while retained user state and raw diagnostic evidence remain stable.

## ADDED Requirements

### Requirement: REQ-PTBR-COPY — Correct audited copy and action names

The implementation MUST correct the 14 confirmed active pt-BR keys and align the two related Private DNS captions. It MUST describe the configured-length noise prelude as sent once, preserve geosite full: and actual option names, distinguish cache misses and retries from failures and total attempts, and name the Resume now and Quick Connect controls accurately. Glossary locale counts MUST match the registered ten locales.

#### Scenario: Correct technical and recovery copy

- **WHEN** pt-BR controls, measurements, recovery text or the connected Simple tile are shown
- **THEN** each audited key preserves its source meaning and names the actual translated control, without changes to protocol values or native behavior.

### Requirement: REQ-PTBR-CONTEXT — Read History captions in the selected language

The implementation MUST resolve all 13 affected History captions through the selected app locale rather than an unmodified application context.

#### Scenario: Different system and app languages

- **WHEN** the system uses English on API 32 and the app uses pt-BR
- **THEN** a newly built History row and detail show Portuguese captions, including the remembered-policy badge, while raw values remain exact.

### Requirement: REQ-PTBR-RETAINED — Refresh retained presentation on locale changes

The implementation MUST refresh Diagnostics, History and subscription presentation when the locale changes without a new repository, telemetry or control emission. It MUST reproject selected details from retained raw data and preserve selected IDs, filters, sensitive-detail visibility and loaded results.

#### Scenario: Retained language round trip

- **WHEN** one retained ViewModel and its selected detail remain active with frozen source data during EN to pt-BR to EN
- **THEN** projected text follows each selected locale without a reload, a new data emission, selection loss or raw-data mutation.

### Requirement: REQ-PTBR-NETWORK — Localize known generated network summaries

The implementation MUST localize the known generated Unknown and Unknown network summaries only at the presentation boundary, using the existing unknown-fingerprint marker. It MUST preserve other names, known-fingerprint lookalikes, stored records and exports.

#### Scenario: Existing generated fallback

- **WHEN** a Detection History entry has fingerprint unknown and one of the two exact generated fallback strings
- **THEN** its human caption follows the app locale while the stored entry remains unchanged.

#### Scenario: Real name or other summary

- **WHEN** a record has a known fingerprint or a summary outside the two reserved fallback strings
- **THEN** the summary remains exact, including a known-fingerprint network named Unknown.

### Requirement: REQ-PTBR-PARITY — Preserve locale and verification contracts

The implementation MUST preserve all ten locale resource sets, format arguments, native language names, option order, storage and wire contracts. Any new key MUST be translated in every locale in the same commit. It MUST retain independent local, hosted CI, render and device evidence.

#### Scenario: Combined-tree validation

- **WHEN** the combined branch is checked before publication
- **THEN** resource contracts, regression tests, app and service lint, static analysis, Portuguese renders and screenshot comparison pass without baseline widening or unapproved golden changes.
