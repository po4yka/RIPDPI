use super::*;
use std::net::TcpListener;
use std::thread;

fn reply_stream(reply: Vec<u8>) -> (TcpStream, thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let address = listener.local_addr().unwrap();
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        let mut request = [0; 10];
        stream.read_exact(&mut request).unwrap();
        assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
        stream.write_all(&reply).unwrap();
    });
    (TcpStream::connect(address).unwrap(), server)
}

#[test]
fn socks5_associate_rejects_invalid_fixed_header() {
    for header in [[4, 0, 0, 1], [5, 0, 1, 1], [5, 0, 255, 1]] {
        let (mut stream, server) = reply_stream([header.as_slice(), &[127, 0, 0, 1, 0, 53]].concat());
        assert!(socks5_udp_associate(&mut stream).is_err(), "header {header:?}");
        server.join().unwrap();
    }
}
