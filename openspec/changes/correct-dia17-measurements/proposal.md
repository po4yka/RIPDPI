# Correct DIA-17 measurements and evidence

Task ID: `DGN-1794747077897183`

## Why

The DIA-17 audit found active requests with the network switch off, missing samples reported as OK, timing evidence described as local VPN binding, sparse-sample confidence errors, DNS time mixed into TCP timing, blocking cancellation, and a hidden PTR dependency.

## What Changes

- Respect the master network switch and preserve incomplete or skipped results in UI and exports.
- Keep timing observations separate from local VPN facts. Require usable cohorts before RTT review.
- Separate DNS from TCP connect time and bound active process/socket ownership.
- Use numeric output for supported ping profiles without breaking toybox.

## Capabilities

### New Capabilities

- `diagnostics/icmp-timing-evidence`: bounded measurements and truthful evidence for DIA-17.

### Modified Capabilities

- None. This repair adds a normative owner for the existing Kotlin detection path.

## Impact

Affects core:detection and the Detection UI in app. Adds defaulted internal result fields. No JNI, protobuf, dependency, native protocol, or storage migration. Export status becomes explicit for incomplete measurements. No new ICMP UI mode, external target, or IPv6 mode.
