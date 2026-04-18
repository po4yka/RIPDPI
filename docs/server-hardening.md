# Server Hardening For Self-Hosted Relays

This note documents the server-side graylist pattern referenced by the Tier 3
roadmap. It is not a client feature and it does not change RIPDPI's runtime.
Use it only if you operate your own relay or origin and want to deflect active
probing without hard-dropping traffic.

## Goal

When a probe source looks like an active scanner, route it to a harmless
fallback instead of the real protected service.

Operational rules:

- never `DROP` purely on score; redirect or tarp it instead
- keep the real service reachable for normal clients
- keep the scoring logic outside RIPDPI clients
- treat graylist entries as short-lived, reversible decisions

## Score Model

The practical scoring model from the roadmap is:

| Indicator | Score | Notes |
|-----------|-------|-------|
| Probe starts 1-3 seconds after legitimate traffic from the same source | `+5` | strongest signal |
| Empty SNI, session under 2 seconds, or burst of at least 10 connects per 5 seconds | `+3` | common scanner shape |
| Fewer than 300 bytes, unusual connection rate, or suspicious TCP window behavior | `+2` | medium-confidence fingerprint |
| Odd TTL or non-standard MSS | `+1` | weak signal, only use as tie-breaker |

Recommended threshold: `score >= 5` moves the source into the graylist.

## Deployment Shape

Use three layers:

1. `nginx stream` terminates nothing and only performs `ssl_preread`, logging,
   and source-based routing.
2. A sidecar scorer tails the stream log, computes the score, and rewrites a
   graylist map file.
3. Graylisted sources go to a fallback upstream that looks benign and stable,
   while other sources continue to the real origin.

## Example File Layout

```text
/etc/nginx/nginx.conf
/etc/ripdpi/graylist.map
/var/log/nginx/stream_access.log
/usr/local/bin/ripdpi-graylist-score.py
```

## `nginx stream` Example

```nginx
stream {
    log_format ripdpi_stream
        '$remote_addr:$remote_port '
        'time=$time_iso8601 '
        'server=$server_addr:$server_port '
        'upstream=$upstream_addr '
        'status=$status '
        'bytes_in=$bytes_received '
        'bytes_out=$bytes_sent '
        'session_time=$session_time '
        'sni="$ssl_preread_server_name"';

    access_log /var/log/nginx/stream_access.log ripdpi_stream;

    map $remote_addr $ripdpi_route {
        include /etc/ripdpi/graylist.map;
        default protected_origin;
    }

    upstream protected_origin {
        server 127.0.0.1:9443;
    }

    upstream probe_fallback {
        server 127.0.0.1:10443;
    }

    server {
        listen 443 reuseport;
        proxy_pass $ripdpi_route;
        ssl_preread on;
    }
}
```

The fallback target should behave consistently and cheaply. Good options are:

- a decoy TLS service with an ordinary certificate chain
- a static nginx stream backend that accepts and closes cleanly
- a low-risk mirror or splash origin that reveals nothing about the protected
  upstream

## Graylist Map Format

`nginx stream` can include a plain `map` fragment:

```nginx
203.0.113.14 probe_fallback;
198.51.100.0/24 probe_fallback;
2001:db8:100::/64 probe_fallback;
```

Operational guidance:

- prefer short TTL entries, for example `10m` to `2h`
- list exact IPs first, then short CIDRs if a scanner rotates within one block
- regenerate atomically, then `nginx -s reload`

## Capture Script

Use a narrow capture window around the front-door port:

```bash
#!/usr/bin/env bash
set -euo pipefail

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="${1:-/var/log/ripdpi-captures}"
mkdir -p "$OUT"

tcpdump -i any \
  -nn \
  -s 0 \
  -w "$OUT/stream-${STAMP}.pcap" \
  'tcp port 443 or icmp or icmp6'
```

Keep captures short and rotate aggressively. The goal is operator debugging, not
long-term retention.

## Compose Reference

```yaml
services:
  nginx-stream:
    image: nginx:1.27-alpine
    network_mode: host
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - ./graylist.map:/etc/ripdpi/graylist.map
      - ./logs:/var/log/nginx
    restart: unless-stopped

  graylist-scorer:
    image: python:3.12-alpine
    network_mode: host
    command: ["python", "/opt/ripdpi-graylist-score.py"]
    volumes:
      - ./ripdpi-graylist-score.py:/opt/ripdpi-graylist-score.py:ro
      - ./graylist.map:/etc/ripdpi/graylist.map
      - ./logs:/var/log/nginx
    restart: unless-stopped
```

The scorer process should:

- parse new stream log lines
- maintain a short sliding window per source IP
- emit the score breakdown for operator review
- rewrite `graylist.map` atomically
- reload nginx only when the generated file actually changes

## Recommended Scorer Inputs

At minimum, score on:

- connect burst rate per source IP
- empty or mismatched SNI
- session lifetime
- total bytes transferred
- time delta from the last legitimate session for the same source

If you also have packet capture or conntrack telemetry, fold in:

- observed MSS
- TTL spread
- TCP option anomalies

Do not use a single weak signal, such as odd TTL alone, to graylist a source.

## Safety Notes

- do not `DROP` high-score sources by default; scanners often retry harder after
  silent failure
- keep the fallback response ordinary enough that it does not become a new
  fingerprint
- separate logs for protected and fallback upstreams so score tuning is auditable
- rate-limit reloads; frequent reload storms become their own availability risk

## What This Does Not Cover

This document does not provide:

- a bundled scoring daemon
- nftables automation
- a probe classifier inside RIPDPI clients
- a server implementation for SYN-Hide or UDP-over-ICMP transport experiments

Those remain separate Tier 3 transport work.
