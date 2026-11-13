# Change: Close the remaining Disoob safety gaps

Task ID: `ENG-1794557091175687`

## Why

The DPI-09 audit found inaccurate packet evidence and platform claims. Current
main already fixes OOB stream integrity, offset admission and generic urgent
projection. TTL restore failure, address-family selection and a send-stage
success at an expired deadline can still misreport successful execution.

## What Changes

- Stop a send when its original TTL cannot be restored.
- Apply TTL or Hop Limit only to the connected socket family.
- Return a timeout while bytes remain unsent at the stage deadline.
- Check actual Disoob bytes and sequence positions in local packet smoke.
- Document the Linux and Android platform boundary.
- No configuration schema, JNI, protobuf or storage change is required.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `oob-payload-integrity`: TTL failures and packet evidence must be accurate.

## Impact

- Rust crates ripdpi-desync-runtime, ripdpi-privileged-ops and ripdpi-cli.
- Local packet-smoke tests and native proxy-engine documentation.
- Existing configuration remains valid. A stalled or failed send can now return
  an error where it previously reported success. No new dependency is needed.
