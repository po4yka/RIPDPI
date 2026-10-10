## Purpose

Expose local device and default-data SIM evidence without confusing it with provider restrictions.

## ADDED Requirements

### Requirement: REQ-SIM-COLLECT — Collect scoped local evidence

The implementation MUST collect airplane mode, Data Saver, background and power restrictions, default-data SIM readiness, mobile-data enablement, data allowance, roaming policy and available service/data state. It MUST work without root and without adding mandatory permissions. Unsupported, denied, unavailable and unknown results MUST remain distinct. SIM observations MUST use an explicit default-data subscription and MUST discard observations if that selection changes during capture.

#### Scenario: Wi-Fi or VPN with a locked mobile SIM

- **WHEN** the active path is Wi-Fi or VPN and the default-data SIM requires a PIN
- **THEN** the SIM state remains visible as mobile-only context, without blaming it for the active path.

#### Scenario: Missing access or changing subscription

- **WHEN** an API is denied, unsupported, fails or the selected subscription changes
- **THEN** collection succeeds with unavailable evidence and does not substitute another SIM or a false disabled value.

### Requirement: REQ-SIM-EXPLAIN — Explain scope and limits

The implementation MUST show localized evidence and derived local conditions in current, live and historical diagnostics. Explanations MUST distinguish observed settings from the cause of a scan failure. Data Saver exemption, roaming disabled while not roaming, and Wi-Fi with airplane mode MUST NOT become a confirmed connectivity failure. Balance, tariff exhaustion, SIM registration restrictions and provider allowlists MUST remain unverified.

#### Scenario: Restricted background access

- **WHEN** Data Saver restricts this application in the background
- **THEN** the explanation states its background/metered scope and does not claim all foreground traffic is blocked.

### Requirement: REQ-SIM-PRIVACY — Preserve identifier-free compatible evidence

The implementation MUST persist and export only fixed categorical values and bounded capture time for the new evidence. It MUST NOT collect or export phone numbers, subscription IDs, IMSI, ICCID, IMEI, cell location or APN credentials. Historical contexts without the optional field MUST decode and retain their prior export shape.

#### Scenario: Legacy and redacted export

- **WHEN** an old report is opened or a new context is exported
- **THEN** missing evidence remains absent and new evidence contains only the approved categorical fields, without subscriber identifiers.
