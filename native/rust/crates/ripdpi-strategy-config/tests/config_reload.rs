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
