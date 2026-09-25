## Context

The current encrypted DNS Compose form has a DoH fallback branch. App UI state omits persisted ODoH fields, although DataStore, Kotlin active settings, service mapping, and native tunnel already carry them.

## Goals / Non-Goals

- Goal: DoQ and ODoH forms read their endpoint data without protocol loss; ODoH saves only valid fresh configs.
- Goal: Protocol selection does not restart a running service until a supported resolver is saved.
- Non-goal: Change native DNS behavior, persisted schema, or bundled resolver catalog.

## Decisions

- Reuse existing common host/port/bootstrap and text-field components for DoQ.
- Give ODoH its own fields for proxy URL and operator, target host/path and operator, config source/bytes and timestamps, matching the existing tunnel contract.
- Keep custom protocol selection in Compose draft state. Save is the only path that writes a custom resolver.
- Allow DoQ Save only for Proxy mode when no VPN is running. Block home VPN activation with saved DoQ because the native tunnel rejects DoQ over SOCKS5, including split DNS.
- Validate ODoH HTTPS URL, target fields, supported config wire bytes, and freshness before mutation.

## Contracts and ownership

App DNS UI, mapper, actions, ViewModel, tests and DNS-specific locale keys belong to DNS editor lane. Locale files and generated board are serialized at integration. Protobuf fields 288-296 and native mapping stay unchanged.

## Risks / Trade-offs

- Manually supplied ODoH config bytes can expire while the editor is open. The settings action revalidates on Save.
- Proxy runtime supports direct DoQ. The current VPN DNS plan selects SOCKS5 for split DNS, so DoQ cannot start in VPN mode. The native tunnel rejects unsupported activation from non-home entry points.

## Migration Plan

No migration. Existing saved DoQ and ODoH fields are projected into the new forms. Rollback restores the previous UI while persisted data remains compatible. Verify targeted unit/Compose tests, app lint, and task contract validation.
