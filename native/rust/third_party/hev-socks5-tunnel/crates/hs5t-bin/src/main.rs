use std::env;
#[cfg(target_os = "linux")]
use std::net::{Ipv4Addr, Ipv6Addr};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, Context, Result};
#[cfg(target_os = "linux")]
use hs5t_tunnel::{LinuxTunnel, TunnelDriver};
use signal_hook::consts::{SIGINT, SIGTERM};
use tokio_util::sync::CancellationToken;
use tracing::info;
#[cfg(target_os = "linux")]
use tracing::warn;

fn print_version() {
    let version = env!("CARGO_PKG_VERSION");
    let commit = env!("CARGO_PKG_GIT_SHA", "unknown");
    println!("{} {}", version, commit);
}

fn print_usage() {
    println!("Usage: hs5t <config-file>");
    println!();
    println!("Arguments:");
    println!("  <config-file>  Path to the YAML config file (required)");
    println!();
    println!("Options:");
    println!("  --version   Print version and exit");
    println!("  --help      Print this help message and exit");
    println!();
    println!("Environment:");
    println!("  HEV_SOCKS5_TUNNEL_FD   Pre-opened TUN file descriptor");
}

#[cfg(target_os = "linux")]
fn parse_tun_addr_v4(value: &str) -> Result<(Ipv4Addr, u8)> {
    let ip_part = value.split('/').next().unwrap_or(value);
    let prefix = value
        .split('/')
        .nth(1)
        .map(str::parse)
        .transpose()
        .context("invalid IPv4 prefix length")?
        .unwrap_or(24);
    let addr = ip_part.parse().context("invalid IPv4 tunnel address")?;
    Ok((addr, prefix))
}

#[cfg(target_os = "linux")]
fn parse_tun_addr_v6(value: &str) -> Result<(Ipv6Addr, u8)> {
    let ip_part = value.split('/').next().unwrap_or(value);
    let prefix = value
        .split('/')
        .nth(1)
        .map(str::parse)
        .transpose()
        .context("invalid IPv6 prefix length")?
        .unwrap_or(64);
    let addr = ip_part.parse().context("invalid IPv6 tunnel address")?;
    Ok((addr, prefix))
}

fn env_tun_fd() -> Result<Option<i32>> {
    match env::var("HEV_SOCKS5_TUNNEL_FD") {
        Ok(raw) => {
            let fd = raw
                .parse::<i32>()
                .with_context(|| format!("invalid HEV_SOCKS5_TUNNEL_FD value: {raw}"))?;
            Ok(Some(fd))
        }
        Err(env::VarError::NotPresent) => Ok(None),
        Err(env::VarError::NotUnicode(_)) => {
            Err(anyhow!("HEV_SOCKS5_TUNNEL_FD is not valid UTF-8"))
        }
    }
}

fn register_shutdown_signals() -> Result<(Arc<AtomicBool>, Arc<AtomicBool>)> {
    let sigint = Arc::new(AtomicBool::new(false));
    let sigterm = Arc::new(AtomicBool::new(false));
    signal_hook::flag::register(SIGINT, Arc::clone(&sigint))
        .context("failed to install SIGINT handler")?;
    signal_hook::flag::register(SIGTERM, Arc::clone(&sigterm))
        .context("failed to install SIGTERM handler")?;
    Ok((sigint, sigterm))
}

#[cfg(target_os = "linux")]
struct OwnedTunFd {
    tun: LinuxTunnel,
    run_fd: i32,
}

#[cfg(target_os = "linux")]
impl OwnedTunFd {
    fn new(config: &hs5t_config::Config) -> Result<Self> {
        let tun = LinuxTunnel::open(Some(&config.tunnel.name), config.tunnel.multi_queue)
            .context("failed to open Linux TUN device")?;
        tun.set_mtu(config.tunnel.mtu)
            .context("failed to configure TUN MTU")?;
        if let Some(ipv4) = &config.tunnel.ipv4 {
            let (addr, prefix) = parse_tun_addr_v4(ipv4)?;
            tun.set_ipv4(addr, prefix)
                .context("failed to configure TUN IPv4 address")?;
        }
        if let Some(ipv6) = &config.tunnel.ipv6 {
            let (addr, prefix) = parse_tun_addr_v6(ipv6)?;
            tun.set_ipv6(addr, prefix)
                .context("failed to configure TUN IPv6 address")?;
        }
        tun.set_up().context("failed to bring TUN interface up")?;

        let run_fd = unsafe { libc::dup(tun.fd()) };
        if run_fd < 0 {
            return Err(std::io::Error::last_os_error()).context("failed to duplicate TUN fd");
        }

        Ok(Self { tun, run_fd })
    }
}

#[cfg(target_os = "linux")]
impl Drop for OwnedTunFd {
    fn drop(&mut self) {
        if let Err(err) = self.tun.set_down() {
            warn!("failed to bring TUN interface down: {err}");
        }
    }
}

async fn run() -> Result<()> {
    let args: Vec<String> = env::args().collect();

    // Handle flags before anything else.
    if args.iter().any(|a| a == "--version") {
        print_version();
        return Ok(());
    }
    if args.iter().any(|a| a == "--help" || a == "-h") {
        print_usage();
        return Ok(());
    }

    // Config path is the first positional argument.
    let config_path = match args.get(1) {
        Some(p) => p.clone(),
        None => {
            return Err(anyhow!("config file path is required"));
        }
    };

    // Initialise tracing to stderr.
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .init();

    // Load and validate config; exit 1 on any error.
    let config = match hs5t_config::Config::from_file(&config_path) {
        Ok(c) => {
            info!("config loaded from '{}'", config_path);
            Arc::new(c)
        }
        Err(e) => {
            return Err(anyhow!("failed to load config '{}': {}", config_path, e));
        }
    };

    // Set up shared cancellation token for clean shutdown.
    let cancel = CancellationToken::new();
    let stats = Arc::new(hs5t_core::Stats::new());
    let (sigint, sigterm) = register_shutdown_signals()?;

    let tun_fd = if let Some(fd) = env_tun_fd()? {
        info!("using pre-opened TUN fd {}", fd);
        fd
    } else {
        #[cfg(target_os = "linux")]
        {
            let owned_tun = OwnedTunFd::new(&config)?;
            info!("opened Linux TUN interface '{}'", owned_tun.tun.name());
            let result = hs5t_core::run_tunnel(config, owned_tun.run_fd, cancel, stats).await;
            return result.context("tunnel exited with error");
        }
        #[cfg(not(target_os = "linux"))]
        {
            return Err(anyhow!("HEV_SOCKS5_TUNNEL_FD is required on this platform"));
        }
    };

    let tunnel = hs5t_core::run_tunnel(config, tun_fd, cancel.clone(), stats);
    tokio::pin!(tunnel);
    let shutdown_signal = async {
        loop {
            if sigint.load(Ordering::Relaxed) {
                return "SIGINT";
            }
            if sigterm.load(Ordering::Relaxed) {
                return "SIGTERM";
            }
            tokio::time::sleep(Duration::from_millis(25)).await;
        }
    };
    tokio::pin!(shutdown_signal);

    tokio::select! {
        result = &mut tunnel => result.context("tunnel exited with error"),
        signal_name = &mut shutdown_signal => {
            info!("received {signal_name} — shutting down");
            cancel.cancel();
            tunnel.await.context("tunnel exited with error")
        }
    }
}

#[tokio::main]
async fn main() {
    if let Err(err) = run().await {
        eprintln!("error: {err}");
        eprintln!("Usage: hs5t <config-file>");
        std::process::exit(1);
    }
}
