# RIPDPI fuzzing scaffold

This directory contains the first `cargo-fuzz` setup for the Rust workspace.

Targets:

- `packets_parse` - parser-heavy surfaces in `ripdpi-packets`
- `failure_http_response` - raw HTTP/blockpage classification in `ripdpi-failure-classifier`
- `failure_field_cache` - field-cache-based failure classification in `ripdpi-failure-classifier`
- `vless_request_header` - VLESS request-header parsing in `ripdpi-vless`
- `proxy_config_json` - public proxy config JSON parsing in `ripdpi-proxy-config`
- `tunnel_config_yaml` - public tunnel YAML config parsing in `ripdpi-tunnel-config`
- `session_request_parse` - SOCKS4/SOCKS5/HTTP CONNECT request parsing in `ripdpi-session`
- `dns_response_answers` - DNS response answer extraction in `ripdpi-dns-resolver`
- `monitor_dns_response` - DNS response parsing in `ripdpi-monitor`
- `monitor_http_response` - HTTP response parsing in `ripdpi-monitor`
- `config_offset_expr` - offset-expression parsing in `ripdpi-config`

Run from `native/rust/fuzz`:

```bash
cargo fuzz run packets_parse
cargo fuzz run failure_http_response
cargo fuzz run failure_field_cache
cargo fuzz run vless_request_header
cargo fuzz run proxy_config_json
cargo fuzz run tunnel_config_yaml
cargo fuzz run session_request_parse
cargo fuzz run dns_response_answers
cargo fuzz run monitor_dns_response
cargo fuzz run monitor_http_response
cargo fuzz run config_offset_expr
```

Seed corpora live under `corpus/`. Generated artifacts and coverage output are ignored by git.
