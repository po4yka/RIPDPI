# OUT-1786264762917829: Add Xray provider regression matrix

## Objective

Add Xray provider regression matrix

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] OUT-1786264762918939 Config golden tests cover VLESS/REALITY, XHTTP, invalid combinations, and redaction. — XrayConfigRendererTest, XrayProfileRedactorTest, XrayRedactionRegressionTest (:core:data:catalog, green offline) #feature @item:OUT-1786264762917829
- [x] OUT-1786264762918174 Service tests cover Xray startup failure, readiness timeout, stop, restart, and handover behavior. — XrayServiceLifecycleMatrixTest (one named test per edge) + RipDpiXrayRuntimeTest (:core:engine-api, green offline) #feature @item:OUT-1786264762917829
- [x] OUT-1786264762918618 Protect-fd tests prove Xray dialer/listener sockets use the Android VPN protection path. — XrayProtectFdContractTest: a socket-simulating fake bridge asserts protect strictly precedes connect, a denied protect aborts the socket, and the lo… #feature @item:OUT-1786264762917829
- [x] OUT-1786264762918727 DNS-loop regression proves provider bootstrap DNS does not re-enter TUN. — XrayDnsLoopRegressionTest: DNS ownership pinned to the tunnel, split XrayDns not constructible for the bridged topology, SetTunFd topology refused (green offline) #feature @item:OUT-1786264762917829
- OUT-1786264762918071 DROPPED: Device/emulator smoke test verifies active VPN traffic exits through the Xray outbound path. — documented in docs/contributor/xray-tun-bridge-smoke.md / xray-regression-matrix.md but UNVERIFIED IN CI. OPEN: requires gomobile/libXray + NDK2… #feature @item:OUT-1786264762917829
- [x] OUT-1786264762918955 CI or documented manual lanes identify which Xray tests need network, emulator, or private fixture dependencies. — docs/contributor/xray-regression-matrix.md indexes the whole surface and splits CI-offline lanes from device/emulator, live-… #feature @item:OUT-1786264762917829

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
