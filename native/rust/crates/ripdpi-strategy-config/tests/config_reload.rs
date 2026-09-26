use std::fs;
use std::thread;
use std::time::Duration;

use ripdpi_strategy_config::StrategyConfigReloader;

#[test]
fn reload_if_changed_reloads_modified_yaml_file() {
    let base = std::env::temp_dir().join(format!("ripdpi-strategy-reload-{}", std::process::id()));
    fs::create_dir_all(&base).expect("create temp dir");
    let path = base.join("strategy.yaml");
    fs::write(&path, "version: 1\nstrategies: []\n").expect("write first config");
    let mut reloader = StrategyConfigReloader::load(&path).expect("load reloader");
    assert_eq!(reloader.current().strategies.len(), 0);

    thread::sleep(Duration::from_millis(10));
    fs::write(&path, "version: 1\nstrategies:\n  - id: later\n    steps:\n      - type: split\n")
        .expect("write second config");

    assert!(reloader.reload_if_changed().expect("reload"));
    assert_eq!(reloader.current().strategies.len(), 1);
    let _ = fs::remove_dir_all(base);
}

#[test]
fn reload_if_changed_reloads_referenced_host_list() {
    let base = std::env::temp_dir().join(format!("ripdpi-host-reload-{}", std::process::id()));
    fs::create_dir_all(&base).expect("create temp dir");
    let path = base.join("strategy.yaml");
    let hosts = base.join("hosts.txt");
    fs::write(&hosts, "one.example\n").expect("write hosts");
    fs::write(&path, "version: 1\nstrategies:\n  - id: host_list\n    match:\n      hosts: '@hosts.txt'\n    steps:\n      - type: split\n").expect("write strategy");
    let mut reloader = StrategyConfigReloader::load(&path).expect("load reloader");
    fs::write(&hosts, "two.example\n").expect("update hosts");

    assert!(reloader.reload_if_changed().expect("reload referenced hosts"));
    assert_eq!(reloader.current().strategies[0].matcher.hosts, ["two.example"]);
    let _ = fs::remove_dir_all(base);
}
