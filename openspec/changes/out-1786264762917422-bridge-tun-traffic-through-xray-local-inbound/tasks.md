# OUT-1786264762917422: Bridge TUN traffic through Xray local inbound

## Objective

Bridge TUN traffic through Xray local inbound

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] OUT-1786264762918956 VPN startup can select Xray as the tunnel's upstream local endpoint. — XrayTunnelHandoff resolves the upstream from VpnProviderKind (Native keeps tun2socks; Xray points the tunnel at 127.0.0.1:localInboundPort); covered by XrayTunnelHandof… #feature !high @item:OUT-1786264762917422
- [x] OUT-1786264762918267 Xray outbound sockets and DNS are protected so provider traffic does not loop into the TUN fd. — protect-first ordering in RipDpiXrayRuntime; DNS ownership pinned to the tunnel; proven by XrayProtectFdContractTest and XrayDnsLoopRegression… #feature !high @item:OUT-1786264762917422
- [x] OUT-1786264762918785 Existing tunnel telemetry remains available when the upstream endpoint is Xray instead of RIPDPI-native proxy. — XrayProviderOrchestrator drives the ManagedTunnel seam unchanged; orchestrator tests assert the tunnel lifecycle is preserved… #feature !high @item:OUT-1786264762917422
- [x] OUT-1786264762918495 Network handover restarts both Xray and tunnel when the local inbound or provider route changes. — route-change dual-restart (tunnel stopped before Xray) covered by XrayProviderOrchestratorTest / XrayServiceLifecycleMatrixTest #feature !high @item:OUT-1786264762917422
- [ ] OUT-1786264762918700 A local/device smoke test proves traffic exits through the Xray outbound. — documented in docs/contributor/xray-tun-bridge-smoke.md but UNVERIFIED IN CI. OPEN: requires gomobile/libXray + NDK29 native engine + device + live server; the smo… #feature !high @item:OUT-1786264762917422

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
