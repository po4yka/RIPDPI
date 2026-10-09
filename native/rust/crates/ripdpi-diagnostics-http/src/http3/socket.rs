//! A fixed worker keeps a blocking platform protection callback off the probe runtime.
use super::Failure;
use ripdpi_diagnostics_transport::transport::protected_udp_bind;
use std::net::{SocketAddr, UdpSocket};
use std::sync::{OnceLock, mpsc};
use std::time::{Duration, Instant};

type SocketResult = Result<UdpSocket, Failure>;
struct Job {
    target: SocketAddr,
    deadline: Instant,
    result: mpsc::Sender<SocketResult>,
}
static WORKER: OnceLock<Result<mpsc::SyncSender<Job>, ()>> = OnceLock::new();

fn worker() -> Result<&'static mpsc::SyncSender<Job>, Failure> {
    WORKER
        .get_or_init(|| {
            let (sender, receiver) = mpsc::sync_channel::<Job>(4);
            std::thread::Builder::new()
                .name("ripdpi-http3-protect".into())
                .spawn(move || {
                    while let Ok(job) = receiver.recv() {
                        if Instant::now() >= job.deadline {
                            continue;
                        }
                        let result =
                            std::panic::catch_unwind(|| bind(job.target)).unwrap_or(Err(("FAILED", "protect_failed")));
                        if Instant::now() < job.deadline {
                            // If cancelled, send fails and owns/drops the returned socket.
                            let _ = job.result.send(result);
                        }
                        // Expired jobs drop the socket here. This worker never sends packets.
                    }
                })
                .map_err(|_| ())?;
            Ok(sender)
        })
        .as_ref()
        .map_err(|()| ("FAILED", "io_error"))
}
fn bind(target: SocketAddr) -> SocketResult {
    let address = SocketAddr::new(
        if target.is_ipv4() { std::net::Ipv4Addr::UNSPECIFIED.into() } else { std::net::Ipv6Addr::UNSPECIFIED.into() },
        0,
    );
    protected_udp_bind(address, target).map_err(|error| {
        ("FAILED", if error.kind() == std::io::ErrorKind::PermissionDenied { "protect_failed" } else { "io_error" })
    })
}

/// # Cancel safety:
/// conditionally cancel-safe: cancelling the timer await drops the receiver. A
/// queued/running platform callback can continue on the fixed worker. Its socket
/// is then dropped without outbound use. The caller owns the overall deadline.
// cancel-safe: abandoning the receiver never creates outbound activity or another thread.
pub(crate) async fn protected_socket(target: SocketAddr, deadline: Instant) -> SocketResult {
    let (sender, receiver) = mpsc::channel();
    worker()?.try_send(Job { target, deadline, result: sender }).map_err(|_| ("FAILED", "io_error"))?;
    loop {
        match receiver.try_recv() {
            Ok(result) => return result,
            Err(mpsc::TryRecvError::Disconnected) => return Err(("TIMEOUT", "deadline_exceeded")),
            Err(mpsc::TryRecvError::Empty) => tokio::time::sleep(Duration::from_millis(5)).await,
        }
    }
}
