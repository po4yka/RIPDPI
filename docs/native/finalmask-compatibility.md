# Finalmask Compatibility

RIPDPI applies one Finalmask layer below TLS or REALITY on the native xHTTP carrier. The order is `TCP → mask → TLS/REALITY → HTTP/2 → xHTTP → VLESS`. The peer must undo the mask before its TLS handshake. The mask does not add encryption or authentication.

## Supported carrier and peer subset

| Native relay | Allowed masks | Peer requirement |
|---|---|---|
| VLESS over xHTTP/TLS | off, header_custom, noise, fragment, sudoku | A matching mask endpoint before TLS |
| VLESS Reality over xHTTP | off, header_custom, noise, fragment, sudoku | A matching mask endpoint before REALITY |
| Cloudflare Tunnel xHTTP/TLS | off, fragment | The Cloudflare edge receives unchanged TLS bytes |

Other relay carriers and chain-hop masks reject active Finalmask. QUIC, Hysteria2, TUIC and MASQUE Finalmask are outside this implementation. A mask uses one type; Xray mask lists and multiple layers are not exposed.

The reference is [Xray v26.10.21, commit 1b1647cd9ddf0abc34b981d4611fc03bcac1ca74](https://github.com/XTLS/Xray-core/tree/1b1647cd9ddf0abc34b981d4611fc03bcac1ca74/transport/internet/finalmask). The REALITY header peer also needs [the CloseWrite adapter patch](../examples/finalmask/xray-header-closewrite.patch). Stock Xray at this commit wraps header TCP without the CloseWrite method required by its REALITY server; the handshake then stalls. This affects header_custom and fixed noise mapped through header-custom. Sudoku already provides that method. The patch forwards half-close to the underlying TCP stream and does not change mask bytes. The exact scope is recorded in [SPEC_VERSION.md](../../native/rust/crates/ripdpi-xhttp/SPEC_VERSION.md). This reference does not change the packaged Xray-provider version.

## Types and parameters

- `off` or an empty type passes bytes through. Unknown nonempty types must fail validation.
- `header_custom` sends `headerHex || random bytes || trailerHex` once, before the first TLS bytes. The trailer is part of the prefix, not a suffix on each payload. Hex must be valid and at least one fixed field must be nonempty. The peer response must have no prefix: inbound bytes pass through unchanged.
- `noise` sends a random prefix once. It is a RIPDPI TCP prefix mode, not Xray's UDP `noise` type. For a fixed `randRange` such as `4-4`, use Xray TCP `header-custom` with one client item `{"rand": 4}` and an empty server sequence.
- `fragment` splits only the first carrier chunk into up to `fragmentPackets` parts. Counts are 1..16; the byte range must be positive and ordered. A large last part can exceed `fragmentMaxBytes`. Count 1 is a no-op. The concatenated TLS record bytes stay unchanged. Separate writes do not guarantee separate TCP segments or DPI bypass.
- `sudoku` uses the exact nonblank `sudokuSeed` bytes as the password. The uplink uses classic hint tuples; the downlink uses packed 6-bit groups. Server padding is accepted. Only the default entropy layout is supported; ASCII, custom tables and multiple layouts are outside this subset.

RIPDPI `randRange` selects the **number** of random bytes, from 0 to 16384. Xray `randRange` selects random **byte values**. They are different fields. The supplied peer uses a fixed count, not Xray `randRange`. A variable prefix length needs a separate peer length agreement; the supplied Xray configuration does not provide one. Do not use it as a compatible variable-length example. A `0-0` noise range adds no bytes.

Only active-type fields take part in validation. Inactive fields can remain after a type change. The Kotlin feature flag still controls activation; settings, service validation and native admission must reject invalid active settings before use.

## Paired examples

- [Native xHTTP profile](../examples/finalmask/xhttp-client-profile.json) and [Xray server](../examples/finalmask/xhttp-finalmask-server.json): REALITY, xHTTP stream-one and a fixed header prefix. The VLESS UUID is `00000000-0000-4000-8000-000000000006`; enter it in the separate credential store. The committed REALITY key is public test data. Use it only on loopback. Generate a new key and UUID for a deployed peer.
- The Xray example listens on `127.0.0.1:11443` and uses a local TLS decoy at `127.0.0.1:12443`, SNI `fixture.example`. The acceptance runner creates and removes its own test certificate and starts the decoy. It does not change system trust.
- [Cloudflare profile](../examples/finalmask/cloudflare-tunnel-client-profile.json) selects fragment. [Origin Xray configuration](../examples/finalmask/cloudflare-tunnel-finalmask-server.json) listens on loopback port 18080, with no origin mask or TLS. Cloudflared forwards the published hostname to this HTTP origin. The Cloudflare edge terminates the client TLS; it cannot remove header, noise or Sudoku masks. Configure publication separately under [Cloudflare operations](cloudflare-tunnel-operations.md). These files do not enroll a tunnel or prove external compatibility.

## Local acceptance

Build the reference peer in an isolated copy of the exact commit above. Keep its module files unchanged. Apply the supplied patch before building:

```sh
patch -d /path/to/Xray-core -p1 < docs/examples/finalmask/xray-header-closewrite.patch
(cd /path/to/Xray-core && go test -mod=readonly ./transport/internet/finalmask/header/custom -run TestRIPDPIHeaderCloseWrite)
(cd /path/to/Xray-core && go build -mod=readonly -o /path/to/xray ./main)
```

Then run from the repository, using the pinned Rust toolchain under `native/rust`:

```sh
(cd native/rust && build-gate --lane ripdpi -- cargo build --locked -p ripdpi-xhttp --example finalmask_peer --jobs 2)
python3 scripts/tests/run-finalmask-peer.py --xray /path/to/xray --client /path/to/cargo-target/debug/examples/finalmask_peer
```

The runner requires both binaries and rejects another Xray version. It checks the supplied Xray configurations with the real parser, then runs off, header_custom, fixed noise, fragment and Sudoku over stream-up and stream-one. All listeners and destinations use loopback. A native XhttpClient opens a client-first session, writes all 256 byte values and compares the echoed response. The owned Freedom outbound allows only the exact loopback target port; other routing remains blocked. The Sudoku password includes edge spaces. The runner owns its processes, ephemeral certificate and ports, and closes them on failure.

This is local REALITY/xHTTP session evidence for the recorded source plus the supplied peer adapter patch. It does not establish external Cloudflare interoperability, Android VPN routing, physical-device behavior or TCP segment boundaries. TLS and REALITY production branches share the Finalmask wrapper; certificate trust and ECH remain separate contracts.
