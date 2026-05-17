# nftables rules for live-mode TSPU emulator

`tspu-outbound.nft` is loaded inside the live-mode container before the Python handler starts. It binds outbound TCP:443 and UDP:443 to nfqueue 0; the handler in `runner/live.py` then dispatches every queued packet through the pattern matrix.

Constraints:

- The container must run with `cap_add: NET_ADMIN` to install rules.
- `tcp dport 443 queue num 0 bypass` uses `bypass` so packets are accepted if the queue is full or the handler crashes — choose fail-open behaviour over silently dropping production traffic.
- Inbound traffic is not queued (the patterns are outbound-only by design).
- Other destinations stay on the `accept` policy so setup and the classifier itself remain reachable.

If you need to extend the queue policy (e.g. add UDP:80 for HTTP/3 testing or alternate destinations for combination matrices), edit this file and rebuild the container image. The handler is unaware of the rules' filter and will run against whatever the queue forwards.
