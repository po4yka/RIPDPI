# Change: Repair remaining managed transport contracts

Task ID: `RLY-1794486295363382`

## Why

TUN-10 exposed generic relay use of transports that only carry a Tor bridge link. TUN-07 repaired obfs4 and the shared control parser. Snowflake and WebTunnel selection, WebTunnel startup errors, and lifecycle boundaries still need repair.

## What Changes

- BREAKING: new selection omits standalone Snowflake and WebTunnel. Legacy activation fails with Tor guidance; stored fields and Tor bridge support remain intact.
- WebTunnel selects version 1 from a PT version list and reports startup errors through the PT protocol.
- Managed startup honors its deadline, separates diagnostics from control output and stops through a bounded stdin-close sequence.
- Record that Lyrebird is a Tor link transport, without claiming generic TCP half-close support.

## Capabilities

### New Capabilities

- `tor-pt-activation`: supported Tor bridge activation and managed helper lifecycle boundaries.

### Modified Capabilities

- None.

## Impact

App relay selection, locale exports, service runtime validation and process ownership, and the Rust WebTunnel helper. No new dependency, wire schema, JNI or storage migration.
