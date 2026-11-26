# Change: Manage all servers in one list

Task ID: `UIX-1795615852980919`

## Why

The user sees, adds, selects, and manages every saved server in one list, with one name for servers and another for DPI presets.

## What Changes

- The Add menu offers every relay kind that has an editor in `RelayKindDescriptors`, plus AmneziaWG.
- Saved AmneziaWG profiles appear in the same Servers list as relay profiles.
- Each relay server has rename, duplicate, share, and delete actions, each AmneziaWG server has rename, duplicate, and delete actions, and long-press opens these actions; AmneziaWG share is tracked in UIX-1795661310559744.
- The Connection sheet, Add server menu, server editors, and share action use one term for servers and a different term for DPI presets in all ten locales; the remaining strings are tracked in UIX-1795661374629256.
- Selecting a server does not change the DPI preset without telling the user.
- The Connection screen keeps the selected Relay view when the user returns to it.

## Capabilities

### New Capabilities

- `uix-1795615852980919-manage-all-servers-in-one-list`: Manage all servers in one list

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Exact code, contracts, and gates are constrained by the linked task and design.
