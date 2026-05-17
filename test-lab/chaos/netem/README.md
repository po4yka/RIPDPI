# Linux netem

Run these scripts inside a Linux VM that routes Android/device traffic through the VM. macOS remains the host for the default MVP lab, but UDP/QUIC packet loss, reordering, corruption, and IPv6 blackhole scenarios need Linux `tc`.

Set `NETEM_DEV` when the routed interface is not `eth0`.

## Readiness

Before using this scenario, confirm that the Linux VM or router namespace is actually on the Android device's traffic path:

```bash
ip route
sysctl net.ipv4.ip_forward
tc qdisc show dev "${NETEM_DEV:-eth0}"
```

On the macOS development host, the repository preflight remains read-only and will report this row as blocked:

```bash
test-lab/scripts/check-feature-gap-readiness.sh
```

## Packet-Loss Scenario

1. Start or reuse the local lab and install the current debug build from the macOS host:

   ```bash
   test-lab/scripts/restart-lab.sh --profile device
   test-lab/scripts/adb-install-debug.sh
   ```

2. From the Linux VM/router, apply packet loss to the routed interface:

   ```bash
   cd /path/to/RIPDPI
   NETEM_DEV=eth0 test-lab/chaos/netem/apply-loss.sh 10%
   tc qdisc show dev eth0
   ```

3. From the macOS host, run VPN and diagnostics probes against the routed path:

   ```bash
   test-lab/scripts/run-vpn-e2e.sh \
     --profile device \
     --skip-start \
     --skip-install \
     --out-dir test-lab/artifacts/netem-vpn-loss-$(date +%Y%m%d-%H%M%S)

   test-lab/scripts/adb-run-probe.sh \
     --profile device \
     --mode diagnostics \
     --timeout-ms 7000 \
     --out-dir test-lab/artifacts/netem-diagnostics-loss-$(date +%Y%m%d-%H%M%S)
   ```

4. Clear the network fault and capture the cleared state from the Linux VM:

   ```bash
   NETEM_DEV=eth0 test-lab/chaos/netem/clear.sh
   tc qdisc show dev eth0
   ```

5. Record the run in `docs/feature-test-manual-evidence-template.md` under `Routed Linux Netem`. Include the topology note, `tc qdisc` before/after output, probe JSON paths, and whether the app reports a degraded or failed verdict instead of stale success.

## QUIC-Drop Scenario

Use this when validating UDP/QUIC failure handling. The default drop port is `9443`; pass a different port if the lab exposes QUIC elsewhere.

```bash
NETEM_DEV=eth0 test-lab/chaos/netem/apply-quic-drop.sh 9443
iptables -S | grep 9443

test-lab/scripts/adb-run-probe.sh \
  --profile device \
  --mode diagnostics \
  --timeout-ms 7000 \
  --out-dir test-lab/artifacts/netem-quic-drop-$(date +%Y%m%d-%H%M%S)

NETEM_DEV=eth0 test-lab/chaos/netem/clear.sh
```
