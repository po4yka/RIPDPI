use super::*;

fn transfer_control(stall_ms: u64, total_ms: u64) -> TransferControl<'static> {
    TransferControl::new(stall_ms, total_ms, &|| None).expect("test timeout budget")
}

struct TestIo {
    response: std::io::Cursor<Vec<u8>>,
    written: Vec<u8>,
    timeout_reads: usize,
    write_delay: Duration,
}

impl Read for TestIo {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if self.timeout_reads > 0 {
            self.timeout_reads -= 1;
            std::thread::sleep(Duration::from_millis(2));
            Err(std::io::Error::from(ErrorKind::TimedOut))
        } else {
            self.response.read(buf)
        }
    }
}

impl Write for TestIo {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.written.extend_from_slice(buf);
        std::thread::sleep(self.write_delay);
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

impl TelegramIo for TestIo {
    fn set_io_timeout(&mut self, _timeout: Duration) -> std::io::Result<()> {
        Ok(())
    }
}

#[test]
fn download_rejects_http_error_response() {
    let mut stream = TestIo {
        response: std::io::Cursor::new(b"HTTP/1.1 404 Not Found\r\nContent-Length: 2\r\n\r\nno".to_vec()),
        written: Vec::new(),
        timeout_reads: 0,
        write_delay: Duration::ZERO,
    };

    let result = telegram_download_transfer_loop(&mut stream, TransferResponse::Page, &transfer_control(1_000, 1_000));
    assert_eq!(result.status, "error");
    assert_eq!(result.error.as_deref(), Some("http_status_404"));
}

#[test]
fn download_counts_chunk_payload_and_rejects_truncation() {
    for (wire, expected_bytes, expected_status) in [
        (b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\n\r\n".as_slice(), 2, "ok"),
        (b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nok", 2, "error"),
    ] {
        let mut stream = TestIo {
            response: std::io::Cursor::new(wire.to_vec()),
            written: Vec::new(),
            timeout_reads: 0,
            write_delay: Duration::ZERO,
        };
        let result =
            telegram_download_transfer_loop(&mut stream, TransferResponse::Page, &transfer_control(1_000, 1_000));
        assert_eq!(result.bytes_total, expected_bytes);
        assert_eq!(result.status, expected_status);
        assert!(!result.measures_throughput);
        assert!(stream.written.is_empty(), "response decoder must not write a second request");
    }
}

#[test]
fn download_measures_a_complete_png_and_rejects_the_wrong_representation() {
    let png = include_bytes!("fixtures/single-pixel.png").as_slice();
    for (status, content_type, body, expected) in [
        ("200 OK", "image/png", png, None),
        ("200 OK", "image/png", &png[..16], Some("incomplete_png")),
        ("206 Partial Content", "image/png", png, Some("http_status_206")),
        ("200 OK", "text/html", png, Some("unexpected_content_type")),
        ("200 OK", "image/png", b"<html />".as_slice(), Some("invalid_png_header")),
    ] {
        let mut response =
            format!("HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\n\r\n", body.len())
                .into_bytes();
        response.extend_from_slice(body);
        let mut stream = TestIo {
            response: std::io::Cursor::new(response),
            written: Vec::new(),
            timeout_reads: 0,
            write_delay: Duration::ZERO,
        };
        let result =
            telegram_download_transfer_loop(&mut stream, TransferResponse::Png, &transfer_control(1_000, 1_000));
        assert_eq!(result.error.as_deref(), expected);
        if expected.is_none() {
            assert!(result.measures_throughput);
        }
    }
}

#[test]
fn upload_rejects_http_error_response() {
    let mut stream = TestIo {
        response: std::io::Cursor::new(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".to_vec()),
        written: Vec::new(),
        timeout_reads: 0,
        write_delay: Duration::ZERO,
    };

    let result = telegram_upload_transfer_loop(&mut stream, 5, &transfer_control(1_000, 1_000));
    assert_eq!(stream.written.len(), 5);
    assert_eq!(result.status, "error");
    assert_eq!(result.error.as_deref(), Some("http_status_403"));
}

#[test]
fn upload_204_accepts_only_unframed_acknowledgements() {
    for (headers, expected) in [
        ("", None),
        ("Content-Length: 0\r\n", Some("invalid_framing")),
        ("Transfer-Encoding: chunked\r\n", Some("invalid_framing")),
    ] {
        let mut stream = TestIo {
            response: std::io::Cursor::new(format!("HTTP/1.1 204 No Content\r\n{headers}\r\n").into_bytes()),
            written: Vec::new(),
            timeout_reads: 0,
            write_delay: Duration::ZERO,
        };
        let result = telegram_upload_transfer_loop(&mut stream, 5, &transfer_control(1_000, 1_000));
        assert_eq!(result.error.as_deref(), expected);
        assert_eq!(result.bytes_total, 5);
    }
}

#[test]
fn download_header_timeout_obeys_total_timeout() {
    let mut stream = TestIo {
        response: std::io::Cursor::new(Vec::new()),
        written: Vec::new(),
        timeout_reads: 1,
        write_delay: Duration::ZERO,
    };

    let result = telegram_download_transfer_loop(&mut stream, TransferResponse::Page, &transfer_control(1, 1));
    assert_eq!(result.error.as_deref(), Some("transfer_timeout"));
}

#[test]
fn upload_near_complete_without_response_is_not_ok() {
    let mut stream = TestIo {
        response: std::io::Cursor::new(Vec::new()),
        written: Vec::new(),
        timeout_reads: 0,
        write_delay: Duration::from_millis(5),
    };

    let result = telegram_upload_transfer_loop(&mut stream, TELEGRAM_CHUNK_SIZE + 1, &transfer_control(1, 1));
    assert_eq!(result.bytes_total, TELEGRAM_CHUNK_SIZE);
    assert_eq!(result.error.as_deref(), Some("transfer_timeout"));
}

#[test]
fn upload_with_no_data_does_not_open_a_connection() {
    let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).expect("test listener");
    listener.set_nonblocking(true).expect("nonblocking test listener");
    let mut target = upload_target(&listener);
    target.upload_size_bytes = 0;

    let result = telegram_upload_probe_with_abort(&target, &crate::transport::direct_transport(), None, &|| None);

    assert_eq!(result.error.as_deref(), Some("empty_upload"));
    assert_eq!(listener.accept().unwrap_err().kind(), ErrorKind::WouldBlock);
}

#[test]
fn upload_pinned_ip_does_not_send_http_data_to_an_untrusted_server() {
    use std::sync::Arc;

    let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).expect("test listener");
    let target = upload_target(&listener);
    let server = std::thread::spawn(move || {
        // This self-signed certificate names telegram.org but is outside the trusted roots.
        let cert = rustls::pki_types::CertificateDer::from(include_bytes!("fixtures/untrusted-cert.der").as_slice());
        let key = rustls::pki_types::PrivatePkcs8KeyDer::from(include_bytes!("fixtures/untrusted-key.der").as_slice());
        let config = rustls::ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .expect("test TLS versions")
            .with_no_client_auth()
            .with_single_cert(vec![cert], key.into())
            .expect("test certificate and key");
        let (socket, _) = listener.accept().expect("test client");
        socket.set_read_timeout(Some(Duration::from_secs(3))).expect("test read timeout");
        let conn = rustls::ServerConnection::new(Arc::new(config)).expect("test TLS server");
        let mut stream = rustls::StreamOwned::new(conn, socket);
        let mut bytes = [0; 1024];
        let read = stream.read(&mut bytes);
        if read.as_ref().is_ok_and(|count| *count > 0) {
            let _ = stream.write_all(b"HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n");
            let _ = stream.flush();
        }
        assert_eq!(stream.conn.server_name(), Some("telegram.org"));
        read
    });

    let result = telegram_upload_probe_with_abort(&target, &crate::transport::direct_transport(), None, &|| None);

    assert_eq!(result.bytes_total, 0);
    assert_eq!(result.error.as_deref(), Some("certificate_verification_failed"));
    assert!(server.join().expect("test server").is_err());
}

fn upload_target(listener: &std::net::TcpListener) -> TelegramTarget {
    TelegramTarget {
        media_url: "https://telegram.org/".to_string(),
        upload_ip: "127.0.0.1".to_string(),
        upload_port: listener.local_addr().expect("test address").port(),
        dc_endpoints: Vec::new(),
        stall_timeout_ms: 1000,
        total_timeout_ms: 2000,
        upload_size_bytes: 32,
    }
}

#[test]
fn paused_download_preserves_bytes_and_returns_at_the_stall_budget() {
    let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).expect("test listener");
    let client = std::net::TcpStream::connect(listener.local_addr().unwrap()).expect("test client");
    let (mut peer, _) = listener.accept().expect("test server");
    peer.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\na").expect("response prefix");
    let mut client = ConnectionStream::Plain(client);

    let started = Instant::now();
    let result = telegram_download_transfer_loop(&mut client, TransferResponse::Page, &transfer_control(100, 2000));

    assert_eq!(result.status, "stalled");
    assert_eq!(result.bytes_total, 1);
    assert!(started.elapsed() < Duration::from_millis(800), "result missed the stall window");
}

#[test]
fn shorter_scan_deadline_keeps_its_termination_reason() {
    let mut stream = TestIo {
        response: std::io::Cursor::new(Vec::new()),
        written: Vec::new(),
        timeout_reads: 100,
        write_delay: Duration::ZERO,
    };

    with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(40)), || {
        let control = transfer_control(500, 1000);
        let result = telegram_download_transfer_loop(&mut stream, TransferResponse::Page, &control);
        assert_eq!(result.status, "deadline_exceeded");
    });
}

#[test]
fn aborted_upload_keeps_completed_chunks() {
    use std::sync::atomic::AtomicBool;

    struct UploadPeer<'a> {
        cancel: &'a AtomicBool,
        written: usize,
    }

    impl Read for UploadPeer<'_> {
        fn read(&mut self, _: &mut [u8]) -> std::io::Result<usize> {
            panic!("aborted upload must not read an acknowledgement")
        }
    }

    impl Write for UploadPeer<'_> {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            if self.written > 0 {
                self.cancel.store(true, Ordering::Relaxed);
                return Err(std::io::ErrorKind::BrokenPipe.into());
            }
            self.written += bytes.len();
            Ok(bytes.len())
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    impl TelegramIo for UploadPeer<'_> {
        fn set_io_timeout(&mut self, _: Duration) -> std::io::Result<()> {
            Ok(())
        }
    }

    let cancel = AtomicBool::new(false);
    let should_abort = || cancel.load(Ordering::Relaxed).then_some("cancelled");
    let mut peer = UploadPeer { cancel: &cancel, written: 0 };
    let control = TransferControl::new(500, 1000, &should_abort).expect("test limits");
    let result = telegram_upload_transfer_loop(&mut peer, TELEGRAM_CHUNK_SIZE + 1, &control);

    assert_eq!(result.status, "cancelled");
    assert_eq!(result.bytes_total, TELEGRAM_CHUNK_SIZE);
    assert!(result.measures_throughput);
}

#[test]
fn media_rate_keeps_header_wait_out_of_body_window() {
    let listener = std::net::TcpListener::bind(("127.0.0.1", 0)).expect("test listener");
    let client = std::net::TcpStream::connect(listener.local_addr().unwrap()).expect("test client");
    let (mut peer, _) = listener.accept().expect("test server");
    let png = include_bytes!("fixtures/single-pixel.png");
    let mut response =
        format!("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: {}\r\n\r\n", png.len()).into_bytes();
    response.extend_from_slice(png);
    let writer = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(100));
        peer.write_all(&response).expect("test HTTP response");
    });
    let mut client = ConnectionStream::Plain(client);
    let result = telegram_download_transfer_loop(&mut client, TransferResponse::Png, &transfer_control(800, 1000));
    writer.join().expect("test server");

    assert_eq!(result.status, "ok");
    assert!(result.duration_ms >= 90);
    assert!(result.avg_bps > (png.len() as u64 * 1_000 / result.duration_ms) * 2);
}
