## Context

The TLS probe in `ripdpi-diagnostics-tls` records `local_socket_ttl` from `platform_ttl::get_local_socket_ttl` (`src/tls/probe/stream.rs`). That value is the configured outbound TTL. No code sets a probe TTL, and no code locates a middlebox.

Non-root Android cannot observe ICMP Time Exceeded for TCP: raw sockets need root, and the Linux socket error queue (`MSG_ERRQUEUE`, used by `ripdpi-proxy-runtime-adapter/src/platform/udp_error_queue.rs`) reports ICMP only for UDP and raw sockets. A design that needs router addresses would be unsupported on almost every target device.

## Goals / Non-Goals

Goal: a bounded hop interval for a middlebox that reacts to the test SNI, from non-root measurements on the selected direct network.

Non-goals: router addresses or traceroute output, root-only capture, background runs, upload, automatic strategy changes.

## Decisions

Primary signal. For each TTL value k in an ordered ladder, open a fresh TCP connection with the default TTL, then set the outbound TTL to k (`TcpStream::set_ttl` for IPv4, `socket2::Socket::set_unicast_hops_v6` for IPv6) and send one ClientHello. Record what returns within the attempt window: injected RST, injected FIN, TLS alert, ServerHello, or silence. The control SNI and the test SNI alternate at each k.

Interval. The path length is the smallest k at which the control receives a ServerHello. The divergence upper bound is the smallest k at which the test receives a reaction and the control at the same k is silent. The lower bound is the largest smaller k at which both are silent. A test reaction first seen at the path length cannot be told apart from server behavior and is inconclusive.

Lifecycle. The TTL change applies only after the TCP handshake, so the SYN uses the default TTL. TCP retransmits the ClientHello with the same TTL inside the attempt window; this is expected. After the window, close the socket with `SO_LINGER` 0. Do not reuse a socket across attempts.

Bounds. Maximum TTL 32, two repetitions per (k, SNI) cell, one fixed attempt window, and one total deadline for the run. Cancellation stops the ladder at the current attempt. Only the endpoint the user selected is probed; no address ranges.

Capability. The probe is supported only when the socket is protected through the existing protection callback, bound to the selected direct network, and the TTL setter succeeds. Otherwise the run is `unsupported` and other diagnostics are not affected. There is no root path.

Limits. The run reports these as limits, never as proof of no interference: control never reaches the server, control reacts like the test, ServerHello at k of 1 or 2 (local proxy or interception), network change during the run, reactions that vary across repetitions (load balancing or asymmetric paths).

Privacy. The non-root path collects no hop addresses. Per-attempt timings stay in local history. The ordinary export contains the interval, the limits, and the endpoint label only. A detailed per-attempt export is out of scope for this change.

## Migration Plan

The result is a new optional field in the diagnostics contract. Older saved reports stay readable. Validate the current schema version in Rust and Kotlin before changing the contract.
