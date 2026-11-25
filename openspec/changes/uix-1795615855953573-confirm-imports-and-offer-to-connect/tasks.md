# UIX-1795615855953573: Confirm imports and offer to connect to the new server

## Objective

After an import the user knows what was added and can connect to it in one step; a failed import tells them why.

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md`.
- [ ] UIX-1795615856525622 A successful link, QR, or file import shows the imported server names and offers Select and Connect. #feature @item:UIX-1795615855953573
- [x] UIX-1795615856992293 An empty clipboard offers a field to paste or type the link. #feature @item:UIX-1795615855953573
- [x] UIX-1795615857443258 The subscription confirm step shows the fetched name and server count before Add. #feature @item:UIX-1795615855953573
- [x] UIX-1795615857865767 A failed subscription import names the cause (network, HTTP status, format) and uses the word subscription. #feature @item:UIX-1795615855953573
