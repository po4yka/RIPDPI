# Change: Repair DIA-09 TCP fat-header diagnostics

Task ID: `DGN-1794734095979878`

## Why

The TCP fat-header probe can send HTTP to HTTPS endpoints, report unsent data as sent, and infer a cutoff from ordinary I/O failures. Retry, cancellation, and report mapping can obscure the evidence.

## What Changes

Give catalog targets a usable HTTP mode. Preserve application byte counts and error stages. Limit retries and candidate comparisons. Check HTTP authority and response framing. Align results, observations, and report summaries.

## Capabilities

### New Capabilities

- Add `diagnostics/tcp-fat-header` requirements for measurement and reporting.

## Impact

The network probe, target catalog, native report and Kotlin projection change. A probe may now return an inconclusive result where the previous version claimed a cutoff. The request and report JSON shape stays the same.
