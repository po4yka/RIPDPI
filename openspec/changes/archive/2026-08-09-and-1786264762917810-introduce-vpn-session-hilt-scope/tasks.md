# AND-1786264762917810: Introduce a VPN-session Hilt scope to reset per-session service state

## Objective

Introduce a VPN-session Hilt scope to reset per-session service state

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- AND-1786264762918435 DROPPED: PR enumerates which singletons moved to session scope and why each qualifies #feature @item:AND-1786264762917810
- AND-1786264762918454 DROPPED: Migrated objects get a fresh instance per VPN session; old-session state is gone on restart #feature @item:AND-1786264762917810
- AND-1786264762918578 DROPPED: Session-restart test confirms no cross-session state bleed (e.g., telemetry observers do not receive prior-session events) #feature @item:AND-1786264762917810
- AND-1786264762918001 DROPPED: /gradlew :core:service:testDebugUnitTest --locked green; no Hilt graph errors #feature @item:AND-1786264762917810

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
