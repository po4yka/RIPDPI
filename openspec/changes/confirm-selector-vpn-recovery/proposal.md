# Confirm selector reload and recovery on the applied VPN

Task ID: `VPN-1795264616970883`

## Why

The selector starts its cooldown when it writes the desired member. The service can reject that request or apply it while a handover is in progress. A healthy isolated reserve also gives no evidence that the applied VPN restored traffic.

## What Changes

- Confirm the requested member using the running service's applied revision.
- Reconcile a rejected automatic choice against the settled running revision.
- Confirm traffic recovery through the authenticated VPN route and keep the cooldown tied to the actual switch.

## Capabilities

### New Capabilities

- `selector-reload-recovery` — Confirm application and traffic recovery after an automatic selector move.

### Modified Capabilities

- None.

## Impact

Automatic selector reload, payload failover, and local promotion evidence.
