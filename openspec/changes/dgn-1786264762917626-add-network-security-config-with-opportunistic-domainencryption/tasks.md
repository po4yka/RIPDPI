# DGN-1786264762917626: Add network-security-config with opportunistic domainEncryption

## Objective

Add network-security-config with opportunistic domainEncryption

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] DGN-1786264762917935 Config file exists with the base domainEncryption block on the Android-17+ resource path #feature @item:DGN-1786264762917626
- [x] DGN-1786264762917885 Manifest references the config via android:networkSecurityConfig="@xml/networksecurityconfig" #feature @item:DGN-1786264762917626
- [x] DGN-1786264762917233 App still builds on minSdk targets below Android 17; the new attribute is ignored harmlessly on older versions #feature @item:DGN-1786264762917626
- [ ] DGN-1786264762917841 Instrumented test on Android 17 confirms ECH is attempted when DNS surfaces an ECH config #feature @item:DGN-1786264762917626

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
