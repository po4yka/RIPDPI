# Xray provider fixture

This peer runs the pinned Xray core with real VLESS REALITY TCP and xHTTP
transports. It belongs to the Android functional acceptance tier. It proves
traffic, identity rejection, and service lifecycle behavior; it does not prove
packet behavior on a physical network.

The patched Xray core blocks private destinations after outbound redirects.
Each fixture-owned outbound has a `finalRules` entry that permits only its
owned listener address, port, and protocol. A final block rule rejects
all other destinations. The default routing outbound remains `blackhole`.

The published transport ports use fixture-owned TCP forwarding listeners. Xray
listens on separate loopback ports in the same host or network namespace. These
listeners forward bytes without changing protocol messages or authentication.
The pinned Xray version's xHTTP `Listener.Close` closes the listening socket,
but does not close the HTTP/2 server's established connections. A pooled client
can otherwise open a new stream and reach the echo handler after `/peer/stop`.

`/peer/stop` closes the published listeners and both ends of each accepted
connection, cancels pending backend dials, and joins the forwarding goroutines
before it closes the core and acknowledges the request. Registration and stop
share a lock, so a concurrent accept cannot escape cleanup. `/peer/start` keeps
the public ports and identity. A failed start closes the partial runtime. The
management endpoint, direct sentinel, and owned targets remain available.

Run the host checks with `go test -mod=readonly ./...` and
`go test -race -mod=readonly ./...`. The pooled-client regression fixes XMUX to
one connection to force reuse across stop; the client instance remains alive
through rejection and recovery. No external server is required.
