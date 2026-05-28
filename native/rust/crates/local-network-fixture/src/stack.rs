use std::fmt;
use std::io;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use crate::control;
use crate::dns;
use crate::echo;
use crate::event::EventLog;
use crate::fault::FaultController;
use crate::socks;
use crate::tls::TlsMaterial;
use crate::types::{FixtureConfig, FixtureManifest};
use crate::util;

pub struct FixtureStack {
    manifest: FixtureManifest,
    events: EventLog,
    faults: FaultController,
    stop: Arc<AtomicBool>,
    handles: Vec<JoinHandle<()>>,
}

struct ServiceContext {
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
}

struct EchoServices {
    tcp_handle: JoinHandle<()>,
    udp_handle: JoinHandle<()>,
    tls_handle: JoinHandle<()>,
    tcp_port: u16,
    udp_port: u16,
    tls_port: u16,
}

struct DnsServices {
    udp_handle: JoinHandle<()>,
    http_handle: JoinHandle<()>,
    dot_handle: JoinHandle<()>,
    dnscrypt_handle: JoinHandle<()>,
    doq_handle: JoinHandle<()>,
    odoh_target_handle: JoinHandle<()>,
    odoh_proxy_handle: JoinHandle<()>,
    udp_port: u16,
    http_port: u16,
    dot_port: u16,
    dnscrypt_port: u16,
    doq_port: u16,
    odoh_proxy_port: u16,
    odoh_target_port: u16,
}

struct SocksService {
    handle: JoinHandle<()>,
    port: u16,
}

struct ControlService {
    handle: JoinHandle<()>,
    port: u16,
}

impl FixtureStack {
    pub fn start(config: FixtureConfig) -> io::Result<Self> {
        let tls = TlsMaterial::generate(&config)?;
        let context = ServiceContext {
            stop: Arc::new(AtomicBool::new(false)),
            events: EventLog::new(),
            faults: FaultController::new(),
        };

        let echo = start_echo_services(&config, &context, &tls)?;
        let dns = start_dns_services(&config, &context, &tls)?;
        let socks = start_socks_service(&config, &context)?;
        let mut manifest = build_manifest(&config, &tls, &echo, &dns, &socks);
        let shared_manifest = Arc::new(Mutex::new(manifest.clone()));
        let control = start_control_service(config.bind_host.clone(), config.control_port, &context, &shared_manifest)?;
        manifest.control_port = control.port;
        if let Ok(mut current) = shared_manifest.lock() {
            *current = manifest.clone();
        }

        let handles = vec![
            echo.tcp_handle,
            echo.udp_handle,
            echo.tls_handle,
            dns.udp_handle,
            dns.http_handle,
            dns.dot_handle,
            dns.dnscrypt_handle,
            dns.doq_handle,
            dns.odoh_target_handle,
            dns.odoh_proxy_handle,
            socks.handle,
            control.handle,
        ];

        Ok(Self { manifest, events: context.events, faults: context.faults, stop: context.stop, handles })
    }

    pub fn manifest(&self) -> &FixtureManifest {
        &self.manifest
    }

    pub fn events(&self) -> EventLog {
        self.events.clone()
    }

    pub fn faults(&self) -> FaultController {
        self.faults.clone()
    }
}

impl Drop for FixtureStack {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.tcp_echo_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.tls_echo_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.dns_http_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.dns_dot_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.dns_dnscrypt_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.dns_odoh_proxy_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.dns_odoh_target_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.socks5_port);
        util::wake_tcp(&self.manifest.bind_host, self.manifest.control_port);
        util::wake_udp(&self.manifest.bind_host, self.manifest.udp_echo_port);
        util::wake_udp(&self.manifest.bind_host, self.manifest.dns_udp_port);
        util::wake_udp(&self.manifest.bind_host, self.manifest.dns_doq_port);
        for handle in self.handles.drain(..) {
            let _ = handle.join();
        }
    }
}

impl fmt::Debug for FixtureStack {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("FixtureStack").field("manifest", &self.manifest).finish_non_exhaustive()
    }
}

fn start_echo_services(
    config: &FixtureConfig,
    context: &ServiceContext,
    tls: &TlsMaterial,
) -> io::Result<EchoServices> {
    let (tcp_handle, tcp_port) = echo::start_tcp_echo_server(
        config.bind_host.clone(),
        config.tcp_echo_port,
        context.stop.clone(),
        context.events.clone(),
        context.faults.clone(),
    )?;
    let (udp_handle, udp_port) = echo::start_udp_echo_server(
        config.bind_host.clone(),
        config.udp_echo_port,
        context.stop.clone(),
        context.events.clone(),
        context.faults.clone(),
    )?;
    let (tls_handle, tls_port) = echo::start_tls_echo_server(
        config.bind_host.clone(),
        config.tls_echo_port,
        context.stop.clone(),
        context.events.clone(),
        context.faults.clone(),
        tls.server_config.clone(),
    )?;

    Ok(EchoServices { tcp_handle, udp_handle, tls_handle, tcp_port, udp_port, tls_port })
}

fn start_dns_services(config: &FixtureConfig, context: &ServiceContext, tls: &TlsMaterial) -> io::Result<DnsServices> {
    let (udp_handle, udp_port) = dns::start_dns_udp_server(
        config.bind_host.clone(),
        config.dns_udp_port,
        context.stop.clone(),
        context.events.clone(),
        context.faults.clone(),
        config.dns_answer_ipv4.clone(),
    )?;
    let (http_handle, http_port) = dns::start_dns_http_server(
        config.bind_host.clone(),
        config.dns_http_port,
        context.stop.clone(),
        context.events.clone(),
        context.faults.clone(),
        config.dns_answer_ipv4.clone(),
    )?;
    let (dot_handle, dot_port) = dns::start_dns_dot_server(
        config.bind_host.clone(),
        config.dns_dot_port,
        context.stop.clone(),
        context.events.clone(),
        context.faults.clone(),
        config.dns_answer_ipv4.clone(),
        tls.server_config.clone(),
    )?;
    let (dnscrypt_handle, dnscrypt_port) = dns::start_dns_dnscrypt_server(
        config.bind_host.clone(),
        config.dns_dnscrypt_port,
        context.stop.clone(),
        context.events.clone(),
        context.faults.clone(),
        config.dns_answer_ipv4.clone(),
        config.dnscrypt_provider_name.clone(),
        config.dnscrypt_public_key.clone(),
    )?;
    let (doq_handle, doq_port) = dns::start_dns_doq_server(
        config.bind_host.clone(),
        config.dns_doq_port,
        context.stop.clone(),
        context.events.clone(),
        context.faults.clone(),
        config.dns_answer_ipv4.clone(),
        tls.server_config.clone(),
    )?;
    let (odoh_target_handle, odoh_target_port) = dns::start_dns_odoh_target_server(
        config.bind_host.clone(),
        config.dns_odoh_target_port,
        context.stop.clone(),
        context.events.clone(),
        config.dns_answer_ipv4.clone(),
    )?;
    let (odoh_proxy_handle, odoh_proxy_port) = dns::start_dns_odoh_proxy_server(
        config.bind_host.clone(),
        config.dns_odoh_proxy_port,
        context.stop.clone(),
        context.events.clone(),
    )?;

    Ok(DnsServices {
        udp_handle,
        http_handle,
        dot_handle,
        dnscrypt_handle,
        doq_handle,
        odoh_target_handle,
        odoh_proxy_handle,
        udp_port,
        http_port,
        dot_port,
        dnscrypt_port,
        doq_port,
        odoh_proxy_port,
        odoh_target_port,
    })
}

fn start_socks_service(config: &FixtureConfig, context: &ServiceContext) -> io::Result<SocksService> {
    let (handle, port) = socks::start_socks5_server(
        config.clone(),
        context.stop.clone(),
        context.events.clone(),
        context.faults.clone(),
    )?;
    Ok(SocksService { handle, port })
}

fn start_control_service(
    bind_host: String,
    port: u16,
    context: &ServiceContext,
    shared_manifest: &Arc<Mutex<FixtureManifest>>,
) -> io::Result<ControlService> {
    let (handle, port) = control::start_control_server(
        bind_host,
        port,
        context.stop.clone(),
        context.events.clone(),
        context.faults.clone(),
        shared_manifest.clone(),
    )?;
    Ok(ControlService { handle, port })
}

fn build_manifest(
    config: &FixtureConfig,
    tls: &TlsMaterial,
    echo: &EchoServices,
    dns: &DnsServices,
    socks: &SocksService,
) -> FixtureManifest {
    FixtureManifest {
        bind_host: config.bind_host.clone(),
        android_host: config.android_host.clone(),
        tcp_echo_port: echo.tcp_port,
        udp_echo_port: echo.udp_port,
        tls_echo_port: echo.tls_port,
        dns_udp_port: dns.udp_port,
        dns_http_port: dns.http_port,
        dns_dot_port: dns.dot_port,
        dns_dnscrypt_port: dns.dnscrypt_port,
        dns_doq_port: dns.doq_port,
        dns_odoh_proxy_port: dns.odoh_proxy_port,
        dns_odoh_target_port: dns.odoh_target_port,
        socks5_port: socks.port,
        control_port: 0,
        fixture_domain: config.fixture_domain.clone(),
        fixture_ipv4: config.fixture_ipv4.clone(),
        dns_answer_ipv4: config.dns_answer_ipv4.clone(),
        tls_certificate_pem: tls.certificate_pem.clone(),
        dnscrypt_provider_name: config.dnscrypt_provider_name.clone(),
        dnscrypt_public_key: config.dnscrypt_public_key.clone(),
    }
}
