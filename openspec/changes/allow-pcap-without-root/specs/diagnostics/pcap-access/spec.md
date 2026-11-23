## Purpose

Make local packet capture and review available on Android devices that run RIPDPI without root access.

## ADDED Requirements

### Requirement: REQ-PCAP-ACCESS — Record and view without root

The app MUST allow recording on an active VPN after separate capture consent and an explicit capture request, regardless of root mode. The app MUST stop recording when the user revokes consent. The app MUST allow users to view completed captures with the VPN disconnected.

#### Scenario: Capture on a VPN with root mode off

- **WHEN** the VPN is active, the user has granted capture consent, and the user requests a recording
- **THEN** the app records packets from the VPN tunnel with root mode off

#### Scenario: Consent or VPN is absent

- **WHEN** the user requests a recording without capture consent or an active VPN
- **THEN** the app rejects the request without creating a capture

#### Scenario: Consent is withdrawn

- **WHEN** the user turns off capture consent during a recording
- **THEN** the app stops the recording

#### Scenario: View a completed capture after disconnect

- **WHEN** the user opens a completed capture with root mode off and the VPN disconnected
- **THEN** the app shows the packets from that capture
