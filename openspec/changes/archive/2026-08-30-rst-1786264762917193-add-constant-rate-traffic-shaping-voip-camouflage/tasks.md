# RST-1786264762917193: Add constant-rate traffic shaping with VoIP camouflage profile

## Objective

Add constant-rate traffic shaping with VoIP camouflage profile

## Ownership

Ownership is declared in the portfolio task and the implementation worktree before execution.

## Execution

- [x] RST-1786264762917660 New crate ripdpi-traffic-shape with a Shaper trait that wraps any AsyncRead + AsyncWrite stream #feature @item:RST-1786264762917193
- [x] RST-1786264762917048 At least two preset profiles: opusvoip (200-byte / 20 ms) and webrtcvideo (variable but bounded) #feature @item:RST-1786264762917193
- [x] RST-1786264762917517 Configurable via core:data:model typed schema #feature @item:RST-1786264762917193
- [x] RST-1786264762917383 Unit tests verify: outgoing rate stays within ±5% of target over 1000 ticks; size distribution is constant; reverse-path padding round-trips cleanly #feature @item:RST-1786264762917193
- [x] RST-1786264762917995 Telemetry counters for bytes-padded vs bytes-real (so operators can see the overhead) #feature @item:RST-1786264762917193

## Verification

Use the exact gates and evidence required by the portfolio task and `verification.md` when present.
