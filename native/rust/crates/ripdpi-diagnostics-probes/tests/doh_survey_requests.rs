//! Exercise both library request builders against a real local HTTP peer.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::time::{Duration, Instant};

use ripdpi_diagnostics_probes::probes::doh_json_survey::{DohJsonResolverEndpoint, DohJsonSurveyRunner};
use ripdpi_diagnostics_probes::probes::doh_survey::{
    DohHttpClient, DohHttpFuture, DohResolverEndpoint, DohSurveyRunner,
};
use ripdpi_diagnostics_probes::{Probe, ProbeContext, ProbeVerdict};

struct LocalHttpClient;

impl DohHttpClient for LocalHttpClient {
    fn get<'a>(&'a self, url: &'a str, timeout: Duration) -> DohHttpFuture<'a> {
        Box::pin(async move {
            let (authority, path) = url
                .strip_prefix("http://")
                .and_then(|url| url.split_once('/'))
                .ok_or_else(|| "expected local HTTP fixture".to_string())?;
            assert!(authority.starts_with("127.0.0.1:"));
            let mut socket = TcpStream::connect(authority).map_err(|e| e.to_string())?;
            socket.set_read_timeout(Some(timeout)).map_err(|e| e.to_string())?;
            socket.set_write_timeout(Some(timeout)).map_err(|e| e.to_string())?;
            write!(socket, "GET /{path} HTTP/1.1\r\nHost: {authority}\r\nConnection: close\r\n\r\n")
                .map_err(|e| e.to_string())?;
            let mut bytes = Vec::new();
            socket.read_to_end(&mut bytes).map_err(|e| e.to_string())?;
            let boundary = bytes.windows(4).position(|bytes| bytes == b"\r\n\r\n").ok_or("missing head")?;
            let head = std::str::from_utf8(&bytes[..boundary]).map_err(|e| e.to_string())?;
            let status = head
                .split_ascii_whitespace()
                .nth(1)
                .ok_or("missing status")?
                .parse::<u16>()
                .map_err(|e| e.to_string())?;
            Ok((status, bytes[boundary + 4..].to_vec()))
        })
    }
}

fn run_both(url: &str) -> [ProbeVerdict; 2] {
    let context = ProbeContext::empty();
    let timeout = Duration::from_secs(1);
    let legacy = DohSurveyRunner {
        query_host: "a&b.example".into(),
        timeout,
        resolvers: vec![DohResolverEndpoint { label: "local".into(), url: url.into() }],
        client: LocalHttpClient,
    };
    let json = DohJsonSurveyRunner {
        query_host: "a&b.example".into(),
        timeout,
        resolvers: vec![DohJsonResolverEndpoint { label: "local".into(), url: url.into() }],
        client: LocalHttpClient,
    };
    [
        futures::executor::block_on(legacy.survey(&context)).run(&context).verdict,
        futures::executor::block_on(json.survey(&context)).run(&context).verdict,
    ]
}

#[test]
fn both_surveys_preserve_endpoint_parameters_and_encode_the_requested_name_on_wire() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let address = listener.local_addr().unwrap();
    let peer = std::thread::spawn(move || {
        let mut requests = Vec::new();
        let deadline = Instant::now() + Duration::from_secs(5);
        while requests.len() < 2 && Instant::now() < deadline {
            let mut socket = match listener.accept() {
                Ok((socket, _)) => socket,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(5));
                    continue;
                }
                Err(error) => panic!("accept local request: {error}"),
            };
            socket.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
            socket.set_write_timeout(Some(Duration::from_secs(1))).unwrap();
            let mut request = Vec::new();
            while !request.ends_with(b"\r\n\r\n") && request.len() < 4096 {
                let mut byte = [0];
                socket.read_exact(&mut byte).unwrap();
                request.push(byte[0]);
            }
            requests.push(String::from_utf8(request).unwrap().lines().next().unwrap().to_string());
            let body = br#"{"Status":0,"Question":[{"name":"a&b.example.","type":1}],"Answer":[{"name":"a&b.example.","type":1,"TTL":60,"data":"192.0.2.9"}]}"#;
            write!(socket, "HTTP/1.1 200 OK\r\nContent-Type: application/dns-json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len()).unwrap();
            socket.write_all(body).unwrap();
        }
        requests
    });
    let url = format!("http://{address}/resolve?trace=kept&ct=application%2Fdns-json");
    assert_eq!(run_both(&url), [ProbeVerdict::Pass, ProbeVerdict::Pass]);
    let requests = peer.join().unwrap();
    assert_eq!(requests.len(), 2);
    for request in requests {
        eprintln!("DIA05_URL_REQUEST {request}");
        assert_eq!(request, "GET /resolve?trace=kept&ct=application%2Fdns-json&name=a%26b.example&type=A HTTP/1.1");
    }
}

#[test]
fn invalid_endpoints_are_rejected_before_a_tcp_connection() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    for path in ["/resolve?%6eame=other.example", "/res%QZolve", "/resolve%"] {
        let url = format!("http://{}{path}", listener.local_addr().unwrap());
        assert!(run_both(&url).iter().all(|verdict| matches!(verdict, ProbeVerdict::Inconclusive { .. })));
    }
    assert!(matches!(listener.accept(), Err(error) if error.kind() == std::io::ErrorKind::WouldBlock));
}
