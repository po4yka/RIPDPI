# Change: Correct Kotlin DNS diagnostic evidence and HTTPS lookups

Task ID: `DGN-1794740137965522`

## Why

Kotlin diagnostics can show false DNS agreement, lost ECH results, or a provider page without a matching observation. The HTTP readers can keep reading after cancellation or consume a reply before they check its size and type.

## What Changes

- Report the outcome and source of each HTTPS lookup. Use the selected service binding in the ECH test.
- Limit and cancel exchanges. Validate the vendor JSON response before it becomes evidence.
- Request EDNS for EDE measurements. Preserve the address family and apply effective DNS TTL values.
- Report exact agreement, partial evidence, and common errors separately.
- Use ID zero on binary DoH queries. Describe the separate UDP sizing option.

## Capabilities

### New Capabilities

- `kotlin-dns-diagnostics` defines the diagnostic measurement and evidence requirements.

### Modified Capabilities

None.

## Impact

Kotlin DNS adapters, ECH readiness, DNS and suite UI, and the internal Kotlin-to-native ECH request. This change adds no resolver or transport to the runtime product.
