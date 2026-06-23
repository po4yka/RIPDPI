use std::net::{SocketAddr, TcpStream};
use std::thread;
use std::time::Duration;

use super::address::resolve_addresses;
use super::route_experiment::{connect_addresses_with_route_experiment, route_identity};
use super::socks5::connect_via_socks5_observed;
use super::types::{RouteExperimentConfig, TargetAddress, TransportConfig, TransportConnectResult};
use crate::util::CONNECT_TIMEOUT;

pub fn connect_transport_observed(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
) -> Result<TransportConnectResult, String> {
    match transport {
        TransportConfig::Direct { route_experiment } => {
            connect_direct_observed(targets, port, route_experiment.as_ref())
        }
        TransportConfig::Socks5 { host, port: proxy_port } => {
            connect_via_socks5_observed(targets, port, host, *proxy_port)
        }
    }
}

pub fn connect_direct(target: &TargetAddress, port: u16) -> Result<TcpStream, String> {
    Ok(connect_direct_observed(std::slice::from_ref(target), port, None)?.stream)
}

fn connect_direct_observed(
    targets: &[TargetAddress],
    port: u16,
    route_experiment: Option<&RouteExperimentConfig>,
) -> Result<TransportConnectResult, String> {
    let addresses = resolve_candidate_addresses(targets, port)?;
    if let Some(config) = route_experiment {
        let route_identity = route_identity(&addresses);
        let ((stream, connected_addr, local_addr), route_report) =
            connect_addresses_with_route_experiment(&addresses, config, &route_identity)?;
        return Ok(TransportConnectResult {
            stream,
            connected_addr: Some(connected_addr),
            local_addr: Some(local_addr),
            route_report: Some(route_report),
        });
    }
    let (stream, connected_addr) = connect_addresses_with_race(&addresses)?;
    let local_addr = stream.local_addr().ok();
    Ok(TransportConnectResult { stream, connected_addr: Some(connected_addr), local_addr, route_report: None })
}

pub(super) fn resolve_candidate_addresses(targets: &[TargetAddress], port: u16) -> Result<Vec<SocketAddr>, String> {
    let mut resolved = Vec::new();
    for target in targets {
        for address in resolve_addresses(target, port)? {
            if !resolved.contains(&address) {
                resolved.push(address);
            }
        }
    }
    if resolved.is_empty() {
        return Err("no_socket_addrs".to_string());
    }
    Ok(resolved)
}

fn connect_addresses_with_race(addresses: &[SocketAddr]) -> Result<(TcpStream, SocketAddr), String> {
    let initial_batch = addresses.iter().take(2).copied().collect::<Vec<_>>();
    let mut last_error = None;
    if !initial_batch.is_empty() {
        let raced = thread::scope(|scope| {
            let handles = initial_batch
                .iter()
                .map(|address| {
                    scope.spawn(move || (*address, super::protect::protected_tcp_connect(*address, CONNECT_TIMEOUT)))
                })
                .collect::<Vec<_>>();
            let mut winner = None;
            let mut local_last_error = None;
            for handle in handles {
                let (address, result) = handle.join().map_err(|_| "connect_race_panicked".to_string())?;
                match result {
                    Ok(stream) if winner.is_none() => winner = Some((stream, address)),
                    Ok(_) => {}
                    Err(err) => local_last_error = Some(err.to_string()),
                }
            }
            Ok::<_, String>((winner, local_last_error))
        })?;
        if let Some((stream, address)) = raced.0 {
            return Ok((stream, address));
        }
        last_error = raced.1;
    }
    for address in addresses.iter().skip(initial_batch.len()).copied() {
        match super::protect::protected_tcp_connect(address, CONNECT_TIMEOUT) {
            Ok(stream) => return Ok((stream, address)),
            Err(err) => last_error = Some(err.to_string()),
        }
    }
    Err(last_error.unwrap_or_else(|| "no_addresses".to_string()))
}

pub fn wait_for_listener(addr: SocketAddr) -> Result<(), String> {
    for _ in 0..40 {
        if super::protect::protected_tcp_connect(addr, Duration::from_millis(50)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(25));
    }
    Err(format!("probe runtime listener did not become ready on {addr}"))
}
