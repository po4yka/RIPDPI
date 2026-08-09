# RST-1786264762917181: Add upstream HTTP and SOCKS5 proxy override for diagnostic probes

## Objective

Add upstream HTTP and SOCKS5 proxy override for diagnostic probes

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [ ] RST-1786264762917568 Accept per-run authenticated SOCKS5 and HTTP upstream proxy URLs #feature !low @item:RST-1786264762917181
- [ ] RST-1786264762917733 When set, every TCP-based probe (TLS reachability, TCP 16-20KB, HTTP injection) routes through the proxy. DNS UDP probes are skipped or fall back to DoH-via-proxy and are flagged as such #feature !low @item:RST-1786264762917181
- [ ] RST-1786264762917069 Diagnostics summary clearly labels the result as proxy-routed and never persists a transparent verdict from a proxy-routed run into the per-network policy store #feature !low @item:RST-1786264762917181
- [ ] RST-1786264762917443 Proxy URL is treated as a credential: never logged at any level, never written to export bundles, redacted in summary #feature !low @item:RST-1786264762917181
- [ ] RST-1786264762917607 Setting is per-run via the diagnostics screen; no global default #feature !low @item:RST-1786264762917181

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
