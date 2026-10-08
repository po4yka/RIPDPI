# Change: Preserve imported AWG network policy

Task ID: `DAT-1791479988786516`

## Why

The subscription mapper drops DNS and AllowedIPs. Imported profiles use app DNS and an IPv4 default route instead of the supplied policy. Refresh also preserves old policy even when the server provides an update.

## What Changes

- Import explicit DNS servers and IPv4/IPv6 routes without changing them.
- Select interface addresses by address family, independent of list order.
- Apply supplied policy on refresh and preserve saved policy when a field is absent.
- Keep explicit empty routes invalid instead of substituting a default route.
- No breaking native or stored activation-request schema changes.

## Capabilities

### New Capabilities

- `awg-network-policy-import`: DNS, route, and address-family preservation during import and refresh.

### Modified Capabilities

- None.

## Impact

- `:core:data:runtime-state` subscription models and mappers; `:core:data` import metadata and repository.
- `:core:service` derives routes and DNS for configured families; Simple seeder regression uses the unchanged CI bundle.
