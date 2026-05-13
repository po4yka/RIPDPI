# Linux netem

Run these scripts inside a Linux VM that routes Android/device traffic through
the VM. macOS remains the host for the default MVP lab, but UDP/QUIC packet
loss, reordering, corruption, and IPv6 blackhole scenarios need Linux `tc`.

Set `NETEM_DEV` when the routed interface is not `eth0`.
