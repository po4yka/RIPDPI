# Finalmask Compatibility

RIPDPI does not implement Finalmask on-device. Finalmask is an xray-core server-side feature, so this phase only
documents server shapes that work with the existing RIPDPI relay handling.

Use these examples as references for compatible server-side setups and matching client profiles:

- [xHTTP server example](../examples/finalmask/xhttp-finalmask-server.json)
- [Cloudflare Tunnel server example](../examples/finalmask/cloudflare-tunnel-finalmask-server.json)
- [xHTTP client profile](../examples/finalmask/xhttp-client-profile.json)
- [Cloudflare Tunnel client profile](../examples/finalmask/cloudflare-tunnel-client-profile.json)

The client-side relay behavior stays unchanged:

- xHTTP relay profiles should resolve normally through the existing VLESS/xHTTP path.
- Cloudflare Tunnel relay profiles should still force xHTTP and disable UDP.
- No Finalmask toggle should appear in RIPDPI settings.
