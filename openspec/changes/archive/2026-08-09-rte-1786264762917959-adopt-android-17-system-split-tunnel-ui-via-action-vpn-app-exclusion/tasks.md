# RTE-1786264762917959: Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS

## Objective

Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] RTE-1786264762918511 Settings screen on Android 17+ fires ACTIONVPNAPPEXCLUSIONSETTINGS to delegate to OS UI. The split-tunnel screen shows a "managed by system" card whose button fires the intent (the verified compileSdk=37 value android.settings.VPNAPPEXCLUS… #feature @item:RTE-1786264762917959
- [x] RTE-1786264762918333 Android < 17 fallback retains in-app exclusion UI. The in-app editor is shown on < 17 and whenever the system screen does not resolve on the device (graceful degradation, no dead button) #feature @item:RTE-1786264762917959
- RTE-1786264762918504 DROPPED: Exclusions verified to persist across VPN reconnects (OS-managed state). DEVICE-GATED — persistence is OS-owned and only observable on a real Android 17 device #feature @item:RTE-1786264762917959
- [x] RTE-1786264762918806 Manifest declares supported intent for system discovery. CORRECTED: Android 17 defines no app-side manifest declaration for this — ACTIONVPNAPPEXCLUSIONSETTINGS is a system Settings action the app fires (via startActivity), not one a third… #feature @item:RTE-1786264762917959

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
