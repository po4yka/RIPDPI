## Purpose

Let users import any supported profile, subscription or WireGuard / AmneziaWG config from the QR scanner, the clipboard, the share sheet or a picked file, without leaking secrets or leaving broken state.

## ADDED Requirements

### Requirement: REQ-IMPORT-PARITY — One classifier for every surface

The QR scanner, Paste link and the share sheet MUST accept the same content: every proxy URI scheme the codec parses, subscription links, WireGuard / AmneziaWG `.conf` text and AmneziaVPN `vpn://` keys.

#### Scenario: Same text, any surface

- **WHEN** the same subscription link, `.conf` or key arrives by QR, clipboard or share sheet
- **THEN** each surface opens the same confirmation screen or editor.

### Requirement: REQ-IMPORT-SUBSCRIPTION — Subscription links keep their kind and leave no junk

A `/bootstrap/` link MUST keep the one-time kind. Official sing-box, NekoBox, Clash, v2rayNG and Hiddify subscription links MUST parse with their display name. A new group whose first fetch is not a subscription MUST be deleted; a transient failure MUST keep it for retry.

#### Scenario: A website QR code

- **WHEN** a user adds a link whose first fetch returns a web page
- **THEN** the import fails and no group remains for auto-update to poll.

### Requirement: REQ-IMPORT-CONF — Configs import or fail with a precise reason

The editor MUST accept AmneziaWG and plain WireGuard configs, use the first `[Peer]` and report how many it ignored, and MUST say whether a file was unreadable or its text invalid.

#### Scenario: AmneziaVPN multi-code key

- **WHEN** the scanner reads all codes of an AmneziaVPN QR series in any order
- **THEN** it shows progress and then opens the editor with the embedded config.

### Requirement: REQ-IMPORT-SECRETS — Secrets stay in process memory

Subscription URLs, profiles and `.conf` text MUST reach their screen only through the process-local pending store; navigation arguments, intents and saved state carry an opaque token. `toString` of payloads that carry a secret MUST be redacted.

#### Scenario: Share sheet hand-off

- **WHEN** a `.conf` is shared to RIPDPI
- **THEN** the forwarding intent holds only a token, and the editor claims the text once.
