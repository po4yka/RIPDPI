## ADDED Requirements

### Requirement: REQ-XUDP-CTRL-01 Valid control frames preserve the association

The Single XUDP decoder SHALL accept KEEP without data for session 0 and KeepAlive with any session ID, with or without data. It SHALL read extra length and data only when the data option is set. End SHALL terminate the association. Unknown data session IDs, option bits, statuses, and malformed lengths SHALL fail closed.

#### Scenario: A control frame precedes a datagram

- **GIVEN** a valid control frame followed by a data KEEP frame
- **WHEN** the carrier delivers fragmented or combined bytes
- **THEN** the client returns the unchanged datagram and retains the association

### Requirement: REQ-XUDP-TARGET-01 Invalid targets do not reach the carrier

XUDP SHALL reject domains outside the pinned Xray address contract before enqueue, initial-target publication, or NEW/KEEP state change. Rejection SHALL return InvalidInput and retain the association. IP addresses and ASCII letters, digits, hyphen, dot, and underscore domains within the existing length budget SHALL remain accepted. Invalid explicit downlink domains SHALL fail closed.

#### Scenario: A rejected target occurs between accepted datagrams

- **GIVEN** a working XUDP association
- **WHEN** a caller submits a domain with an invalid character and then a valid target
- **THEN** only the valid datagrams reach the wire and their NEW/KEEP order remains correct
