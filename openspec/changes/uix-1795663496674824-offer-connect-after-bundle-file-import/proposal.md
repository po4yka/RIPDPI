# Change: Offer Select and Connect after importing a RIPDPI bundle file

Task ID: `UIX-1795663496674824`

## Why

After the user imports a RIPDPI bundle file, the app names the imported servers and lets the user select one and connect to it in one step.

## What Changes

- After a RIPDPI bundle file import succeeds, the result screen lists the imported server names.
- The result screen offers Select and Connect for an imported server; Select makes it the active server through the same selection path as the server list, and Connect also starts the connection when nothing runs.
- A running service follows the new selection without a second start.
- New text uses the term server, and new strings exist in all ten locales.

## Capabilities

### New Capabilities

- `uix-1795663496674824-offer-connect-after-bundle-file-import`: Offer Select and Connect after importing a RIPDPI bundle file

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Exact code, contracts, and gates are constrained by the linked task and design.
