## Context

The app and the tunnel service configure Xray with a local inbound port. The direct TUN handoff has no production caller and throws if selected.

## Decisions

- Configure the Xray provider with its loopback port. Resolve the upstream to the fixed loopback host and keep DNS ownership with the managed tunnel.
- Keep upstream validation before runtime startup. Reuse the existing port range, DNS ownership check, and socket protection.
- Keep the reported loopback label in Xray diagnostics. Only that known label can enter the redacted summary.

## Verification

Run the provider, upstream, DNS, and diagnostics JVM tests and the repository source gates. Replace the unreachable handoff test with an invalid port test of the same startup guard.
