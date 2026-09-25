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
- Block DoQ Save while VPN DNS routes through SOCKS5. The native tunnel rejects DoQ over that transport, including split DNS.
- Validate ODoH HTTPS URL, target fields, supported config wire bytes, and freshness before mutation.

## Contracts and ownership

App DNS UI, mapper, actions, ViewModel, tests and DNS-specific locale keys belong to DNS editor lane. Locale files and generated board are serialized at integration. Protobuf fields 288-296 and native mapping stay unchanged.

## Risks / Trade-offs

- Manually supplied ODoH config bytes can expire while the editor is open. The settings action revalidates on Save.
- Direct DoQ is supported by the native resolver, but the current VPN DNS plan selects SOCKS5 for its split DNS policy. The UI retains the DoQ editor and explains the activation limit.

## Migration Plan

No migration. Existing saved DoQ and ODoH fields are projected into the new forms. Rollback restores the previous UI while persisted data remains compatible. Verify targeted unit/Compose tests, app lint, and task contract validation.
