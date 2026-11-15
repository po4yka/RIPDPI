## Purpose

Capture raw IPv4 and IPv6 packets on the active VPN and allow private inspection and redacted export using classic PCAP.

## ADDED Requirements

### Requirement: REQ-AUX01-F1 — Explicit Android export

The capture viewer MUST allow explicit export of a selected completed private capture with the Android document picker. It MUST export redacted bytes.

#### Scenario: Explicit Android export

- **WHEN** A user selects a destination
- **THEN** The destination contains a readable redacted PCAP.

### Requirement: REQ-AUX01-F2 — Capture permission

Recording MUST require the enabled capture setting and an explicit manual or diagnostic run request. Withdrawal MUST stop active recording.

#### Scenario: Capture permission

- **WHEN** The capture setting is off or is turned off
- **THEN** A start has no capture side effect and any existing recording stops.

### Requirement: REQ-AUX01-F3 — Writer completion

The capture controller MUST publish writer capacity and failure outcomes and account for packet loss. The writer MUST close packet admission when it finishes.

#### Scenario: Writer completion

- **WHEN** The writer reaches the last segment or fails
- **THEN** The UI leaves recording state and the completion preserves loss information.

### Requirement: REQ-AUX01-F4 — Redaction metadata

Redaction MUST preserve source snaplen, original packet lengths, captured lengths, and timestamps.

#### Scenario: Redaction metadata

- **WHEN** Redaction reads a valid truncated IP record
- **THEN** The original length and the captured length remain distinct in the output.

### Requirement: REQ-AUX01-F5 — Reader validation

Both readers MUST validate classic PCAP 2.4 little-endian file and record headers for LINKTYPE_RAW, unsigned lengths, and microsecond timestamps before allocating packet storage.

#### Scenario: Reader validation

- **WHEN** A record exceeds the snapshot length or the supported capture bound
- **THEN** The reader rejects the record before allocation.

### Requirement: REQ-AUX01-F6 — Private retention

Managed private capture storage MUST enforce three packet file slots and a 64 MiB packet data budget, including active segments. Cleanup MUST remove inactive expired captures and orphaned staging artifacts under the documented policy.

#### Scenario: Private retention

- **WHEN** The app starts a capture with a full directory
- **THEN** The storage owner reserves space by pruning inactive data before recording.

### Requirement: REQ-AUX01-F7 — Failed export cleanup

An incomplete source MUST fail preparation before any external document is created. The app MUST attempt provider rollback after output failure or cancellation, and report rollback failures.

#### Scenario: Failed export cleanup

- **WHEN** The stream ends inside a packet record or the provider fails to finish the copy
- **THEN** The app reports an incomplete source or output failure and discards its staging data.

### Requirement: REQ-AUX01-F8 — Writer representability and limits

The writer MUST reject unrepresentable timestamps and lengths before emitting the record. Rotation MUST stay within the segment byte limit.

#### Scenario: Writer representability and limits

- **WHEN** A record would pass the current segment limit
- **THEN** The writer rotates before writing a complete record or accounts for its loss if no segment can hold it.

### Requirement: REQ-AUX01-F9 — Listing status

Listing MUST distinguish an empty capture directory from filesystem failure.

#### Scenario: Listing status

- **WHEN** The capture directory cannot be listed
- **THEN** The capture list displays a recoverable listing error.
