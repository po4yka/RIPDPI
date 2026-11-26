# UIX-1795615852980919: Manage all servers in one list

## Objective

The user sees, adds, selects, and manages every saved server in one list, with one name for servers and another for DPI presets.

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md`.
- [x] UIX-1795615853511465 The Add menu offers every relay kind that has an editor in `RelayKindDescriptors`, plus AmneziaWG. #feature !high @item:UIX-1795615852980919
- [x] UIX-1795615853931821 Saved AmneziaWG profiles appear in the same Servers list as relay profiles. #feature !high @item:UIX-1795615852980919
- [x] UIX-1795615854385350 Each relay server has rename, duplicate, share, and delete actions, each AmneziaWG server has rename, duplicate, and delete actions, and long-press opens these actions; AmneziaWG share is tracked in UIX-1795661310559744. #feature !high @item:UIX-1795615852980919
- [x] UIX-1795615854842462 The Connection sheet, Add server menu, server editors, and share action use one term for servers and a different term for DPI presets in all ten locales; the remaining strings are tracked in UIX-1795661374629256. #feature !high @item:UIX-1795615852980919
- [x] UIX-1795615855310994 Selecting a server does not change the DPI preset without telling the user. #feature !high @item:UIX-1795615852980919
- [x] UIX-1795615855772763 The Connection screen keeps the selected Relay view when the user returns to it. #feature !high @item:UIX-1795615852980919
