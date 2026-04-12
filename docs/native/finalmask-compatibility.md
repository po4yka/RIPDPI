# Finalmask Compatibility

RIPDPI now applies Finalmask on the client side for xHTTP-backed relay transports:

- VLESS Reality with `xhttp`
- Cloudflare Tunnel profiles that resolve through the xHTTP TLS path

The current implementation mutates the outbound xHTTP transport stream before the Reality or TLS handshake starts.
Supported modes are `header-custom`, `fragment`, and `Sudoku`.

The app still does not implement QUIC-side Finalmask. `noise`, Hysteria2, TUIC, and MASQUE remain unsupported.

Use these examples as references for compatible server-side setups and matching client profiles:

- [xHTTP server example](../examples/finalmask/xhttp-finalmask-server.json)
- [Cloudflare Tunnel server example](../examples/finalmask/cloudflare-tunnel-finalmask-server.json)
- [xHTTP client profile](../examples/finalmask/xhttp-client-profile.json)
- [Cloudflare Tunnel client profile](../examples/finalmask/cloudflare-tunnel-client-profile.json)

Client-side behavior:

- xHTTP relay profiles now apply Finalmask directly in the native xHTTP transport.
- Cloudflare Tunnel relay profiles still force xHTTP and disable UDP, and can use the same Finalmask transport modes.
- Unsupported relay kinds or Finalmask modes are rejected at validation time instead of being silently ignored.
