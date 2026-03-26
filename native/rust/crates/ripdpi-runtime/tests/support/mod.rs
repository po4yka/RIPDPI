#![allow(dead_code)]

#[cfg(not(feature = "loom"))]
pub mod proxy;
pub mod socks5;
pub mod telemetry;
pub mod tls;
pub mod wire;

use std::time::Duration;

pub const START_TIMEOUT: Duration = Duration::from_secs(5);
pub const SOCKET_TIMEOUT: Duration = Duration::from_secs(5);
