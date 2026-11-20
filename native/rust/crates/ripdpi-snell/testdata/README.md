# Snell v4 interop vectors

Both files record one TCP exchange between the sagernet/sing-snell `snellv4` client and its `snellv5` multi-user server at commit `bc5a12ac736f` (the version that sing-box `v1.14.3`, the `snell` deploy role engine, pins). The client sent a request for `example.com:443` with user key `ripdpi-snell-vector-user`, wrote `hello snell from the go v4 client`, and closed its write side. The server answered with `echo:` followed by the payload. Both sides used the synthetic pre-shared key `ripdpi-snell-vector-psk`.

- `v4_client_to_v5_server.bin`: the bytes that the client sent.
- `v5_server_to_v4_client.bin`: the bytes that the server sent.

The generator was a short Go program that wired `snellv4.NewClient` and `snellv5.NewMultiService` through a recording TCP proxy. Salts and padding are random, so a new run gives different bytes with the same decoded content.
