use std::thread;
use std::time::Duration;

use local_network_fixture::{FixtureConfig, FixtureStack};

fn main() {
    let config = FixtureConfig::from_env();
    let stack = FixtureStack::start(config).expect("start local network fixture");
    println!("{}", serde_json::to_string(stack.manifest()).expect("serialize fixture manifest"));
    loop {
        thread::sleep(Duration::from_secs(60));
    }
}
