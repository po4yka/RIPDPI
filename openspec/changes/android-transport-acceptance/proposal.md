# Change: Verify Android transport payloads and migration

Task ID: `TST-1792776319490914`

## Why

The existing Android acceptance lane checks REALITY TCP and REALITY XHTTP.
It does not exercise the deployed nginx TLS XHTTP frontend, full-app Hysteria2
or AmneziaWG paths, sustained payloads, or replacement-server receipts.
Native interop and direct host controls cannot establish these Android outcomes.

## What Changes

- Add authenticated P1 nginx TLS traffic to the existing owned peer harness.
- Check DNS, download, upload, sustained traffic and recovery through each real app VPN transport.
- Verify actual replacement-server traffic before migration retirement.
- Keep physical-device, filtered-network and public-provider outcomes separate.
- No production API or storage break is planned. Scenario evidence gains explicit transport and transaction checks.

## Capabilities

### New Capabilities

- `android-transport-transactions`: real app transport payload and migration acceptance.

### Modified Capabilities

- None.

## Impact

Android instrumentation, separate-UID traffic probes, local peer fixtures,
acceptance adapters and strict scenario checks. No production dependency is added.
