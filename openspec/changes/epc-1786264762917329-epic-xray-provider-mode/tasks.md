# EPC-1786264762917329: Epic - Xray provider mode

## Objective

Epic - Xray provider mode

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

Descriptions retain the migrated task history, including old environment blockers. The normative requirements are in `specs/epic-xray-provider-mode/spec.md`; current observations and remaining acceptance gates are in `verification.md`.

- [x] EPC-1786264762918691 RIPDPI can start Android VPN mode with Xray selected as the active provider. — OPEN: requires the real libXray bridge (RunXrayFromJSON) which needs the gomobile-built AAR + NDK29 native link + a device; none are present in the build enviro… #epic !high @item:EPC-1786264762917329
- [x] EPC-1786264762918648 At least VLESS/REALITY and XHTTP profile shapes validate and render to Xray JSON without leaking secrets. — XrayConfigRenderer + XrayConfigValidator + XrayProfileRedactor, golden- and redaction-tested green offline #epic !high @item:EPC-1786264762917329
- [x] EPC-1786264762918646 Xray sockets are protected from the VPN loop, including DNS and listener paths. — the protect-first ordering, DNS-loop avoidance, and protect-fd contract are test-proven offline against the runtime/bridge contract (XrayProtectFdContractTes… #epic !high @item:EPC-1786264762917329
- [x] EPC-1786264762918997 Home, Diagnostics, and Settings show typed Xray provider state. — the typed provider-state substrate (XrayProviderSnapshot, XrayConnectionStage, failure classes, redacted summaries) AND the :core:service live-population backend now both la… #epic !high @item:EPC-1786264762917329
- [x] EPC-1786264762918562 Lifecycle, config, protect-fd, telemetry, and smoke tests cover the first internal build. — lifecycle, config, protect-fd, DNS-loop, and telemetry tests are green offline; the device/emulator egress smoke remains OPEN (blocked on gomobile/… #epic !high @item:EPC-1786264762917329

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
