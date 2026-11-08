# Change: Preserve tunnel DNS mapping ownership and response integrity

Task ID: `DNS-1794142176893534`

## Why

VPN-05 can route an issued synthetic address to a different domain, lose records within one response, delete fresh mappings on a stale result, allocate its own listener address, and retain AD after synthesis. These defects affect non-root VPN DNS clients.

## What Changes

- Keep issued addresses bound to one owner for the native session; fail closed when the address space is exhausted.
- Hold temporary leases for all A records until a complete rewrite finishes.
- Discard stale results without clearing fresh mappings.
- Reserve the configured DNS listener address.
- Clear AD when DNS content changes and preserve it when content is unchanged.
- Correct the response-cache diagram and the direct-failure contract documentation.
- No breaking configuration, JNI, storage, or dependency changes.

## Capabilities

### New Capabilities

- `tunnel-dns-mapping-safety`: Issued-address ownership, response leases, reserved addresses, and synthesis integrity.

### Modified Capabilities

- None.

## Impact

- Rust `ripdpi-tunnel-core` and DNS architecture documentation.
- No Kotlin runtime change, new dependency, backend, root requirement, or wire schema migration.
