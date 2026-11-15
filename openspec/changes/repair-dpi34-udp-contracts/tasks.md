# RST-1794723522152914: Repair DPI-34 contracts

## Objective

Fix six findings with local proof.

## Ownership

See portfolio and design.md.

## Execution

## Verification

Owning tests, independent review, Clippy, ci-gates and hosted CI after push.
- [x] RST-1794723793879997 F1 isolate outgoing UDP sockets and replacement tuples #bug !high @item:RST-1794723522152914
- [x] RST-1794723799353040 F2 gate source rebind on observed QUIC context #bug !high @item:RST-1794723522152914
- [x] RST-1794723803895791 F3 validate nonpositive udplen across callers #bug !high @item:RST-1794723522152914
- [x] RST-1794723808701492 F4 support safe IPv6 option chains for udplen #bug !high @item:RST-1794723522152914
- [x] RST-1794723813599604 F5 document actual low-port range and fallback #bug @item:RST-1794723522152914
- [x] RST-1794723818128018 F6 correct QUIC validation explanation #bug @item:RST-1794723522152914
- [x] RST-1794723821878358 Validate review integrate and push combined changes #chore !high @item:RST-1794723522152914
