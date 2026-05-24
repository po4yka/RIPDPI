# Protocol Innovation Playbook

## Extract principle, not dependency

If an external project tunnels through a public media/TURN/CDN service, do not copy the third-party dependency. Extract the principle: NAT traversal, relay allocation, UDP-shaped traffic, HTTP/3 tunneling, selector scoring, or fallback orchestration. Then design an owner-controlled version.

## Good experiment properties

- falsifiable in one lab;
- owner-controlled infrastructure;
- visible metrics;
- no user payload retention;
- explicit kill switch;
- bounded blast radius;
- compatible with existing profile import/export contracts;
- can be removed without stranding users.

## Rejected idea categories

- using public TURN/media/call services as hidden relays;
- depending on accounts, captchas, or policy loopholes;
- consuming third-party bandwidth without authorization;
- covert telemetry or payload capture;
- changes that remove kill-switch behavior;
- designs requiring a central admin panel without explicit threat modeling.

## Candidate experiments

### Owner-controlled TURN/ICE reachability matrix

Deploy an owned relay and test STUN/TURN/ICE candidate behavior across Wi-Fi, cellular, CGNAT, captive networks, and UDP-blocked paths. Use only metadata: candidate type, RTT, failure reason, bytes, and timestamps.

### MASQUE CONNECT-UDP comparison

Compare operator-owned MASQUE HTTP/3 proxying against Hysteria2 and AmneziaWG under UDP drop, UDP throttle, packet loss, and MTU clamp.

### MTU recommendation oracle

Have the app run safe payload-size probes and compare with server-side observations. Emit per-network MTU recommendations for UDP transports.

### Synthetic middlebox lab

Container or VM topology that injects DNS poisoning, TCP reset, QUIC block, TLS SNI abort, HTTP blockpage, and MTU black hole. Use it for deterministic regression tests.
