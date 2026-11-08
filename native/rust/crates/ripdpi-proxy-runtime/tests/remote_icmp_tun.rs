#![cfg(all(target_os = "linux", not(feature = "loom")))]

//! Uses real Linux UDP errors and the production proxy and tunnel packet loop.
//! A datagram socketpair supplies the TUN file descriptor without root access.

mod support;

use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::os::fd::OwnedFd;
use std::os::unix::net::UnixDatagram;
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
use ripdpi_tunnel_config::{Config, MiscConfig, Socks5Config, TunnelConfig};
use ripdpi_tunnel_core::{Stats, run_tunnel_with_ready};
use smoltcp::wire::{Icmpv4Packet, Icmpv6Packet, IpAddress, IpProtocol, Ipv4Packet, Ipv6Packet, UdpPacket};
use tokio_util::sync::CancellationToken;

use support::proxy::{ephemeral_proxy_config, start_proxy};

const TIMEOUT: Duration = Duration::from_secs(3);
const STOP_TIMEOUT: Duration = Duration::from_secs(2);
const PASSWORD: &str = "vpn04-loopback-test-only";
const SOURCE_PORT: u16 = 40123;

struct RunningTunnel {
    cancel: CancellationToken,
    done: mpsc::Receiver<io::Result<()>>,
    worker: Option<thread::JoinHandle<()>>,
}

impl RunningTunnel {
    fn start(port: u16, ipv6: bool) -> (Self, UnixDatagram) {
        let (tun, app) = UnixDatagram::pair().unwrap();
        app.set_read_timeout(Some(TIMEOUT)).unwrap();
        app.set_write_timeout(Some(TIMEOUT)).unwrap();
        let tun_fd: OwnedFd = tun.into();
        let config = Arc::new(Config {
            tunnel: TunnelConfig {
                name: "icmp-kernel-e2e".to_string(),
                mtu: 1500,
                multi_queue: false,
                ipv4: Some("10.0.0.1/24".to_string()),
                ipv6: ipv6.then(|| "fd00::1/64".to_string()),
                post_up_script: None,
                pre_down_script: None,
            },
            socks5: Socks5Config {
                address: "127.0.0.1".to_string(),
                port,
                udp: Some("udp".to_string()),
                udp_address: None,
                pipeline: None,
                username: Some("vpn04".to_string()),
                password: Some(PASSWORD.to_string()),
                mark: None,
            },
            mapdns: None,
            split_dns_policy: None,
            misc: MiscConfig { connect_timeout: 2000, udp_read_write_timeout: 10000, ..MiscConfig::default() },
        });
        let cancel = CancellationToken::new();
        let stop = cancel.clone();
        let (ready_tx, ready_rx) = mpsc::channel();
        let (done_tx, done) = mpsc::channel();
        let worker = thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
            let result =
                runtime.block_on(run_tunnel_with_ready(config, tun_fd, stop, Arc::new(Stats::new()), move || {
                    ready_tx.send(()).unwrap();
                }));
            done_tx.send(result).unwrap();
        });
        let tunnel = Self { cancel, done, worker: Some(worker) };
        ready_rx.recv_timeout(TIMEOUT).expect("production tunnel packet loop ready");
        (tunnel, app)
    }

    fn stop(mut self) {
        self.cancel.cancel();
        self.done.recv_timeout(STOP_TIMEOUT).expect("tunnel stop must interrupt pending UDP read").unwrap();
        self.worker.take().unwrap().join().unwrap();
    }
}

impl Drop for RunningTunnel {
    fn drop(&mut self) {
        self.cancel.cancel();
        if let Some(worker) = self.worker.take() {
            // A failed assertion must not leave the production loop running.
            if self.done.recv_timeout(STOP_TIMEOUT).is_ok() {
                worker.join().unwrap();
            }
        }
    }
}

fn app_address(ipv6: bool) -> IpAddr {
    if ipv6 { "fd00::2".parse().unwrap() } else { Ipv4Addr::new(10, 0, 0, 2).into() }
}

fn udp_packet(target: SocketAddr, payload: &[u8]) -> Vec<u8> {
    let source = app_address(target.is_ipv6());
    let ip_len = if target.is_ipv4() { 24 } else { 40 };
    let udp_len = 8 + payload.len();
    let mut bytes = vec![0; ip_len + udp_len];
    match (source, target.ip()) {
        (IpAddr::V4(source), IpAddr::V4(destination)) => {
            let total = bytes.len();
            let mut ip = Ipv4Packet::new_unchecked(&mut bytes[..]);
            ip.set_version(4);
            ip.set_header_len(24);
            ip.set_total_len(u16::try_from(total).unwrap());
            ip.set_dscp(14);
            ip.set_ecn(1);
            ip.set_ident(0x9a31);
            ip.set_dont_frag(true);
            ip.set_hop_limit(37);
            ip.set_next_header(IpProtocol::Udp);
            ip.set_src_addr(source);
            ip.set_dst_addr(destination);
            bytes[20..24].copy_from_slice(&[1, 1, 0, 0]); // NOP, NOP, EOL, padding.
            Ipv4Packet::new_unchecked(&mut bytes[..]).fill_checksum();
        }
        (IpAddr::V6(source), IpAddr::V6(destination)) => {
            let mut ip = Ipv6Packet::new_unchecked(&mut bytes[..]);
            ip.set_version(6);
            ip.set_traffic_class(0xa5);
            ip.set_flow_label(0x12345);
            ip.set_payload_len(u16::try_from(udp_len).unwrap());
            ip.set_next_header(IpProtocol::Udp);
            ip.set_hop_limit(37);
            ip.set_src_addr(source);
            ip.set_dst_addr(destination);
        }
        _ => unreachable!(),
    }
    let mut udp = UdpPacket::new_unchecked(&mut bytes[ip_len..]);
    udp.set_src_port(SOURCE_PORT);
    udp.set_dst_port(target.port());
    udp.set_len(u16::try_from(udp_len).unwrap());
    udp.payload_mut().copy_from_slice(payload);
    udp.fill_checksum(&IpAddress::from(source), &IpAddress::from(target.ip()));
    bytes
}

fn read_packet(app: &UnixDatagram) -> Vec<u8> {
    let mut bytes = vec![0; 2048];
    let length = app.recv(&mut bytes).expect("production tunnel must return an IP packet");
    bytes.truncate(length);
    bytes
}

fn assert_icmp(response: &[u8], original: &[u8], target: SocketAddr) {
    // smoltcp is independent of the production etherparse ICMP serializer.
    let payload = if target.is_ipv4() {
        let ip = Ipv4Packet::new_checked(response).unwrap();
        assert_eq!(ip.src_addr(), Ipv4Addr::LOCALHOST);
        assert_eq!(IpAddr::from(ip.dst_addr()), app_address(false));
        assert_eq!(usize::from(ip.total_len()), response.len());
        assert_eq!(ip.next_header(), IpProtocol::Icmp);
        assert!(ip.verify_checksum(), "outer IPv4 checksum");
        let icmp = Icmpv4Packet::new_checked(ip.payload()).unwrap();
        assert!(icmp.verify_checksum(), "ICMPv4 checksum");
        assert_eq!(&ip.payload()[..2], &[3, 3], "actual remote port-unreachable, not local setup failure");
        ip.payload()
    } else {
        let ip = Ipv6Packet::new_checked(response).unwrap();
        assert_eq!(ip.src_addr(), Ipv6Addr::LOCALHOST);
        assert_eq!(IpAddr::from(ip.dst_addr()), app_address(true));
        assert_eq!(usize::from(ip.payload_len()) + 40, response.len());
        assert_eq!(ip.next_header(), IpProtocol::Icmpv6);
        let icmp = Icmpv6Packet::new_checked(ip.payload()).unwrap();
        assert!(icmp.verify_checksum(&ip.src_addr(), &ip.dst_addr()), "ICMPv6 pseudo-header checksum");
        assert_eq!(&ip.payload()[..2], &[1, 4], "actual remote port-unreachable, not local setup failure");
        ip.payload()
    };
    assert_eq!(&payload[4..8], &[0; 4]);
    let quoted = &payload[8..];
    if target.is_ipv4() {
        assert!(quoted.len() >= 24 + 8, "IPv4 quote must contain original options and full UDP header");
        assert!(quoted.len() <= original.len());
    } else {
        assert_eq!(quoted.len(), original.len(), "small IPv6 packet must fit in the quote");
    }
    assert_eq!(
        quoted,
        &original[..quoted.len()],
        "preserve original IP options, identifiers, traffic class and hop limit"
    );
}

fn assert_udp_reply(response: &[u8], target: SocketAddr, expected: &[u8]) {
    let source = app_address(target.is_ipv6());
    let payload = if target.is_ipv4() {
        let ip = Ipv4Packet::new_checked(response).unwrap();
        assert!(ip.verify_checksum());
        assert_eq!(IpAddr::from(ip.src_addr()), target.ip());
        assert_eq!(IpAddr::from(ip.dst_addr()), source);
        assert_eq!(ip.next_header(), IpProtocol::Udp);
        ip.payload()
    } else {
        let ip = Ipv6Packet::new_checked(response).unwrap();
        assert_eq!(IpAddr::from(ip.src_addr()), target.ip());
        assert_eq!(IpAddr::from(ip.dst_addr()), source);
        assert_eq!(ip.next_header(), IpProtocol::Udp);
        ip.payload()
    };
    let udp = UdpPacket::new_checked(payload).unwrap();
    assert_eq!(udp.src_port(), target.port());
    assert_eq!(udp.dst_port(), SOURCE_PORT);
    assert!(udp.verify_checksum(&IpAddress::from(target.ip()), &IpAddress::from(source)));
    assert_eq!(udp.payload(), expected);
}

#[test]
fn linux_remote_udp_errors_cross_authenticated_proxy_and_real_tunnel_loop() {
    for ipv6 in [false, true] {
        let bind = if ipv6 { "[::1]:0" } else { "127.0.0.1:0" };
        let closed = UdpSocket::bind(bind).unwrap();
        let closed_target = closed.local_addr().unwrap();
        let echo = UdpSocket::bind(bind).unwrap();
        echo.set_read_timeout(Some(TIMEOUT)).unwrap();
        let live_target = echo.local_addr().unwrap();
        let (pending_tx, pending_rx) = mpsc::channel();
        let fixture = thread::spawn(move || {
            let mut data = [0; 100];
            let (length, peer) = echo.recv_from(&mut data).unwrap();
            assert_eq!(&data[..length], b"other-flow");
            echo.send_to(&data[..length], peer).unwrap();
            let (length, _) = echo.recv_from(&mut data).unwrap();
            assert_eq!(&data[..length], b"pending-during-stop");
            pending_tx.send(()).unwrap();
        });
        let mut config = ephemeral_proxy_config(&["--ip", "127.0.0.1"]);
        config.network.listen.auth_token = Some(PASSWORD.to_string());
        config.network.udp = true;
        config.network.ipv6 = true;
        config.groups = vec![DesyncGroup::new(0)];
        let proxy = start_proxy(config, None);
        let (tunnel, app) = RunningTunnel::start(proxy.port, ipv6);
        // No listener remains: the real kernel must create the remote ICMP error.
        drop(closed);
        let original = udp_packet(closed_target, b"unique-remote-error-datagram");
        app.send(&original).unwrap();
        assert_icmp(&read_packet(&app), &original, closed_target);

        app.send(&udp_packet(live_target, b"other-flow")).unwrap();
        assert_udp_reply(&read_packet(&app), live_target, b"other-flow");
        app.send(&udp_packet(live_target, b"pending-during-stop")).unwrap();
        pending_rx.recv_timeout(TIMEOUT).expect("pending UDP request reached real upstream socket");
        tunnel.stop();
        fixture.join().unwrap();
        let (stopped_tx, stopped_rx) = mpsc::channel();
        let stopper = thread::spawn(move || {
            proxy.control.request_shutdown();
            drop(proxy);
            stopped_tx.send(()).unwrap();
        });
        stopped_rx.recv_timeout(STOP_TIMEOUT).expect("proxy revoke must close UDP association and stop");
        stopper.join().unwrap();
    }
}
