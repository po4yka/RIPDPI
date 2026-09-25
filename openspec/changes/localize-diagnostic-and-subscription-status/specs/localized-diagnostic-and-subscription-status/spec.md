## Purpose

Diagnostic status cards and subscription failover screens must present their controls and state in the app's selected language.

## ADDED Requirements

### Requirement: REQ-LOCALIZED-DIAGNOSTIC-STATUS — Localized diagnostic states

The app MUST show translated Idle, Running, Complete, and Failed status labels for DNS integrity and domain reachability tools in every supported app locale.

#### Scenario: Diagnostic state changes

- **WHEN** either tool enters any of its four states while the app uses a non-English locale
- **THEN** the card shows the status label translated for that locale without changing the tool's state or action availability

### Requirement: REQ-LOCALIZED-SUBSCRIPTION-FAILOVER — Localized subscription failover copy

The app MUST show translated settings description, title, summary title, timeline title and empty text, and no-server title and body in Arabic, German, Spanish, Persian, French, Russian, and Simplified Chinese.

#### Scenario: No subscription server is available

- **WHEN** a user opens subscription failover in one of the affected locales with no observed server
- **THEN** the screen shows translated titles and empty-state text in that locale
