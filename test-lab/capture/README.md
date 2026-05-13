# Packet Capture

Use MacBook interface captures for physical devices and Docker/container logs
for emulator runs. Do not run a second Android VPN packet-capture app while
RIPDPI VPN mode is active.

Examples:

```bash
sudo tcpdump -i en0 -nn -s 0 -w capture/phone-all.pcap host "${PHONE_IP}"
sudo tcpdump -i en0 -nn -s 0 -w capture/dns.pcap host "${PHONE_IP}" and port 53
sudo tcpdump -i en0 -nn -s 0 -w capture/quic.pcap host "${PHONE_IP}" and udp port 9443
```
