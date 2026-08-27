# TRN-1786264762917775: Make the AmneziaWG profile UI establish a real tunnel (standalone AWG transport)

## Objective

Make the AmneziaWG profile UI establish a real tunnel (standalone AWG transport)

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] TRN-1786264762919403 Native generic AmneziaWG runtime (AmneziaWgRuntime) reusing the #feature !high @item:TRN-1786264762917775
- [x] TRN-1786264762919688 Data-plane proof: a real two-peer NoiseIKpsk2 handshake completes with #feature !high @item:TRN-1786264762917775
- [x] TRN-1786264762919682 JNI cdylib bridge ripdpi-amneziawg-android (RipDpiAmneziaWgNativeBindings), #feature !high @item:TRN-1786264762917775
- [x] TRN-1786264762919373 Kotlin binding contract layer: ResolvedRipDpiAmneziaWgConfig DTO + #feature !high @item:TRN-1786264762917775
- [x] TRN-1786264762919279 AmneziaWG profile persistence + selection. A dedicated AWG profile store #feature !high @item:TRN-1786264762917775
- [x] TRN-1786264762919408 Service wiring: AmneziaWgRuntimeSupervisor + composition coordinator #feature !high @item:TRN-1786264762917775
- [x] TRN-1786264762919506 UI connect path: AmneziaWgProfileViewModel.onSave()/onConnect() → #feature !high @item:TRN-1786264762917775
- [x] TRN-1786264762919526 On-device / loopback-fixture interop smoke test against a real AmneziaWG #feature !high @item:TRN-1786264762917775

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
