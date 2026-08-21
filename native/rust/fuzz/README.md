# RIPDPI fuzzing scaffold

This directory contains the first `cargo-fuzz` setup for the Rust workspace.

Targets:

- `packets_parse` - parser-heavy surfaces in `ripdpi-packets`
- `packets_tls_quic` - focused TLS ClientHello and QUIC Initial mutation/parsing in `ripdpi-packets`
- `failure_http_response` - raw HTTP/blockpage classification in `ripdpi-failure-classifier`
- `failure_field_cache` - field-cache-based failure classification in `ripdpi-failure-classifier`
- `vless_request_header` - VLESS request-header parsing in `ripdpi-vless`
- `proxy_config_json` - public proxy config JSON parsing in `ripdpi-proxy-config`
- `tunnel_config_yaml` - public tunnel YAML config parsing in `ripdpi-tunnel-config`
- `session_request_parse` - SOCKS4/SOCKS5/HTTP CONNECT request parsing in `ripdpi-session`
- `dns_response_answers` - DNS response answer extraction in `ripdpi-dns-resolver`
- `dns_https_service_binding` - HTTPS/SVCB service-binding parsing in `ripdpi-dns-resolver`
- `monitor_dns_response` - DNS response parsing in the shared `parsers` module (`fuzz_targets/parsers.rs`)
- `monitor_http_response` - HTTP response parsing in the shared `parsers` module (`fuzz_targets/parsers.rs`)
- `config_offset_expr` - offset-expression parsing in `ripdpi-config`
- `client_hello_offsets` - TLS ClientHello offset discovery used by desync planning
- `mtproto_init` - Telegram MTProto obfuscated2 init seed classifier in `ripdpi-ws-tunnel` (covers `classify_mtproto_seed`, `decrypt_init_packet`, `extract_dc_from_init`)
- `finalmask_spec` - xHTTP FinalMask config parser in `ripdpi-xhttp` (`finalmask/spec.rs`); covers `FinalmaskSpec::from_config`, including the Sudoku-seed path, header/trailer hex decoders, and rand-range parser
- `finalmask_decoder` - xHTTP FinalMask byte-stream decoder in `ripdpi-xhttp` (`finalmask/masks.rs`, `finalmask/sudoku.rs`); exercises `TcpInboundMask::decode` with attacker-influenced ciphertext bytes including the Sudoku hint-tuple table walk (`SudokuDecoder::decode_stream_chunk`)
- `vless_response` - VLESS response-header parsing in `ripdpi-vless`

Run from `native/rust/fuzz`:

```bash
cargo fuzz run packets_parse
cargo fuzz run packets_tls_quic
cargo fuzz run failure_http_response
cargo fuzz run failure_field_cache
cargo fuzz run vless_request_header
cargo fuzz run proxy_config_json
cargo fuzz run tunnel_config_yaml
cargo fuzz run session_request_parse
cargo fuzz run dns_response_answers
cargo fuzz run dns_https_service_binding
cargo fuzz run monitor_dns_response
cargo fuzz run monitor_http_response
cargo fuzz run config_offset_expr
cargo fuzz run client_hello_offsets
cargo fuzz run mtproto_init
cargo fuzz run finalmask_spec
cargo fuzz run finalmask_decoder
cargo fuzz run vless_response
```

Seed corpora live under `corpus/`. Generated artifacts and coverage output are ignored by git.
