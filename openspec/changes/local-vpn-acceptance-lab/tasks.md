# TST-1791477382746187

## Execution

- [ ] TST-1791478009222457 Implement scenario catalog and strict evidence runner with regression tests #feature !high @item:TST-1791477382746187
- [ ] TST-1791478014844231 Implement isolated VM routing faults and TCP UDP controls with tests #feature !high @item:TST-1791477382746187
- [ ] TST-1791478024148220 Implement real Android local acceptance and configurable peer endpoints #feature !high @item:TST-1791477382746187
- [ ] TST-1791478027241747 Implement independent peer adapters and Hysteria interoperability #feature !high @item:TST-1791477382746187
- [ ] TST-1791478029994870 Integrate CI documentation and observed combined validation #feature !high @item:TST-1791477382746187

## Ownership and gates

Ownership is recorded in docs/tasks/issues/local-vpn-acceptance-lab.md.
Coordinator: manifest/runner, Python regression tests, architecture and task gates.
VM: router and Lima, Python tests plus actual Linux TCP/UDP controls.
Android: instrumentation/fixtures, Go/Python tests, Kotlin checks and real AVD run.
Peers: adapters and Hysteria test, Python tests and locked targeted Cargo interop.
Integration: manifest validation, task-check, CI workflow validation and artifact review.
