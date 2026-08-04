# Packet Capture

Use MacBook interface captures for physical devices and Docker/container logs for emulator runs. Do not run a second Android VPN packet-capture app while RIPDPI VPN mode is active.

Examples:

```bash
mkdir -p test-lab/capture
sudo tcpdump -i en0 -nn -s 0 -w test-lab/capture/phone-all.pcap host "${PHONE_IP}"
sudo tcpdump -i en0 -nn -s 0 -w test-lab/capture/dns.pcap host "${PHONE_IP}" and port "${RIPDPI_DNS_PORT:-1053}"
sudo tcpdump -i en0 -nn -s 0 -w test-lab/capture/quic.pcap host "${PHONE_IP}" and udp port 9443
```

Raw captures are private network evidence, never part of the default public
lab archive. Include them only with
`archive-artifacts.sh --retention-class private-raw-pcap`; that class is
local-only and expires after seven days. Use `purge-evidence.sh` to preview or
execute policy-managed cleanup.
