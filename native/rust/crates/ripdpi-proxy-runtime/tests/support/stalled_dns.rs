use std::io::{self, Read};
use std::net::{Ipv4Addr, TcpListener};
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};

pub(super) struct StalledDns {
    port: u16,
    pub(super) started: mpsc::Receiver<()>,
    pub(super) closed: mpsc::Receiver<bool>,
    pub(super) release: mpsc::Sender<()>,
    worker: Option<thread::JoinHandle<()>>,
}

impl StalledDns {
    pub(super) fn start() -> Self {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("local DNS");
        let port = listener.local_addr().expect("DNS address").port();
        listener.set_nonblocking(true).expect("nonblocking DNS listener");
        let (started_tx, started) = mpsc::channel();
        let (closed_tx, closed) = mpsc::channel();
        let (release, released) = mpsc::channel();
        let worker = thread::spawn(move || {
            let mut stream = loop {
                if released.try_recv().is_ok() {
                    return;
                }
                match listener.accept() {
                    Ok((stream, _)) => break stream,
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(10)),
                    error => panic!("DNS accept: {error:?}"),
                }
            };
            stream.set_nonblocking(false).expect("blocking DNS stream");
            stream.set_read_timeout(Some(Duration::from_millis(100))).expect("DNS timeout");
            let mut started_tx = Some(started_tx);
            let mut bytes = [0; 4096];
            loop {
                if released.try_recv().is_ok() {
                    let _ = closed_tx.send(false);
                    break;
                }
                match stream.read(&mut bytes) {
                    Ok(0) => {
                        let _ = closed_tx.send(true);
                        break;
                    }
                    Ok(_) => {
                        if let Some(started) = started_tx.take() {
                            let _ = started.send(());
                        }
                    }
                    Err(error) if matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                    Err(error)
                        if matches!(
                            error.kind(),
                            io::ErrorKind::ConnectionReset | io::ErrorKind::ConnectionAborted
                        ) =>
                    {
                        let _ = closed_tx.send(true);
                        break;
                    }
                    Err(error) => panic!("DNS stream: {error}"),
                }
            }
        });
        Self { port, started, closed, release, worker: Some(worker) }
    }

    pub(super) fn context(&self) -> ProxyRuntimeContext {
        ProxyRuntimeContext {
            encrypted_dns: Some(ProxyEncryptedDnsContext {
                resolver_id: Some("local-stalled-dns".to_string()),
                protocol: "doh".to_string(),
                host: "127.0.0.1".to_string(),
                port: self.port,
                tls_server_name: None,
                bootstrap_ips: vec!["127.0.0.1".to_string()],
                doh_url: Some(format!("http://127.0.0.1:{}/dns-query", self.port)),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            }),
            ..ProxyRuntimeContext::default()
        }
    }
}

impl Drop for StalledDns {
    fn drop(&mut self) {
        let _ = self.release.send(());
        self.worker.take().expect("fixture worker").join().expect("DNS fixture thread");
    }
}
