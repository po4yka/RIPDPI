# ripdpi-dns-resolver

`ripdpi-dns-resolver` owns RIPDPI's native encrypted DNS client paths. Current implemented modes are DoH, DoT, DNSCrypt, and DoQ.

## ODoH Client Mode Constraints

Planned ODoH support is client-only and follows RFC 9230: the client encrypts DNS wire queries to an Oblivious Target with HPKE, sends the encrypted ODoH message through an Oblivious Proxy over HTTPS, and decrypts the target response returned through that proxy.

ODoH is resolver privacy, not DPI evasion. It prevents any one non-colluding server from seeing both the client IP address and the DNS query contents; it does not make DNS traffic look less like HTTPS to a censor than the client-to-proxy HTTPS leg already does.

Non-goals are an ODoH server or target role, hand-rolled HPKE, crypto agility beyond RFC 9230's default suite, and any fallback that presents ODoH as a DPI-bypass transport.

The proxy and target must be non-colluding. A same-operator proxy and target pair gives no privacy benefit over plain DoH because one operator can observe both client IP and query contents, so built-in configurations must not ship same-operator pairs and custom configurations must be refused or warned when the proxy and target appear to share an operator.
