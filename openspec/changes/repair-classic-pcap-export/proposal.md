# Repair classic PCAP capture and export

Task ID: `DGN-1794755342253604`

## Why

AUX-01 found a missing export action, a disconnected consent switch, hidden writer termination and drops, malformed header acceptance, incorrect redaction lengths, storage outside the stated limit, incomplete export cleanup, unchecked writer fields, and listing failures that appear empty.

## What Changes

- Require capture consent and an explicit recording request. Stop on consent withdrawal.
- Preserve capture lengths, report writer outcomes, and bound private storage.
- Export redacted captures through the Android document picker. Discard incomplete export work and report failures.
- BREAKING: Reject malformed or incomplete input in the PCAP reader. Return original length as unsigned data in the Android reader.

## Capabilities

### New Capabilities

- `diagnostics/classic-pcap`: Validated raw IP capture, private retention, inspection, and explicit redacted export.

### Modified Capabilities

- None.

## Impact

Changes native PCAP and tunnel capture code, the capture JNI calls, Android capture/export UI, locale resources, and diagnostics retention. Adds no dependency, remote endpoint, or capture file format.
