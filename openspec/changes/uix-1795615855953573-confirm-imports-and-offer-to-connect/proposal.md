# Change: Confirm imports and offer to connect to the new server

Task ID: `UIX-1795615855953573`

## Why

After an import the user knows what was added and can connect to it in one step; a failed import tells them why.

## What Changes

- A successful link, QR, or single-link file import shows the imported server name and offers Select and Connect; a RIPDPI bundle file import is tracked by UIX-1795663496674824.
- An empty clipboard offers a field to paste or type the link.
- The subscription confirm step shows the fetched name and server count before Add.
- A failed subscription import names the cause (network, HTTP status, format) and uses the word subscription.

## Capabilities

### New Capabilities

- `uix-1795615855953573-confirm-imports-and-offer-to-connect`: Confirm imports and offer to connect to the new server

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Exact code, contracts, and gates are constrained by the linked task and design.
