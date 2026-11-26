# Change: Split selective-availability targets into domestic, foreign datacenter and CDN classes

Task ID: `DGN-1795704974460888`

## Why

Freeze diagnostics can show whether connection_freeze results cluster by destination class and by destination AS, because the selective-availability targets carry a class.

## What Changes

- Each selective-availability target carries one class: domestic hosting, foreign datacenter or CDN.
- Results and redacted exports keep the target class and the destination AS number, and no destination address of a user network.
- The connection_freeze classification together with the transfer volume comparison can be grouped by class and by destination AS in one report.
- A response with a wrong status or a wrong byte count is reported as a server response, not as a freeze, because the check compares exact bytes or hash.

## Capabilities

### New Capabilities

- `dgn-1795704974460888-split-selective-availability-targets-into-domestic-foreign-datacenter-and-cdn-classes`: Split selective-availability targets into domestic, foreign datacenter and CDN classes

### Modified Capabilities

- None.

## Impact

- Portfolio area: `diagnostics`.
- Filed at proposal level only. Design and implementation decisions are open until the task starts.
