use std::io;
use std::net::{Ipv4Addr, Ipv6Addr};

use tokio::io::{AsyncRead, AsyncReadExt};

/// Encode a VLESS request header.
///
/// Wire format (from the VLESS spec):
/// ```text
/// Version(1B = 0x01) | UUID(16B) | AddonsLen(1B) | Addons(var)
/// | Cmd(1B) | Port(2B BE) | AddrType(1B) | Addr(var)
/// ```
///
/// `target` is `"host:port"` — the destination the VLESS server should proxy to.
pub fn encode_request(uuid: &[u8; 16], addons: &[u8], target: &str) -> Vec<u8> {
    let (host, port) = parse_target(target);
    let addons_len = u8::try_from(addons.len()).expect("addons must be < 256 bytes");

    let mut buf = Vec::with_capacity(1 + 16 + 1 + addons.len() + 1 + 2 + 1 + host.len() + 2);

    // Version
    buf.push(0x01);
    // UUID
    buf.extend_from_slice(uuid);
    // Addons length + addons
    buf.push(addons_len);
    buf.extend_from_slice(addons);
    // Command: TCP connect
    buf.push(0x01);
    // Port (big-endian)
    buf.extend_from_slice(&port.to_be_bytes());

    // Address
    if let Ok(v4) = host.parse::<Ipv4Addr>() {
        buf.push(0x01); // IPv4
        buf.extend_from_slice(&v4.octets());
    } else if let Ok(v6) = host.parse::<Ipv6Addr>() {
        buf.push(0x03); // IPv6
        buf.extend_from_slice(&v6.octets());
    } else {
        // Domain
        let domain_bytes = host.as_bytes();
        let domain_len = u8::try_from(domain_bytes.len()).expect("domain must be < 256 bytes");
        buf.push(0x02); // Domain
        buf.push(domain_len);
        buf.extend_from_slice(domain_bytes);
    }

    buf
}

/// Read and consume the VLESS response header from the stream.
///
/// Response format: `Version(1B) | AddonsLen(1B) | Addons(var)`
///
/// After this call returns successfully, the stream carries raw bidirectional data.
pub async fn read_response(stream: &mut (impl AsyncRead + Unpin)) -> io::Result<()> {
    let mut header = [0u8; 2];
    stream.read_exact(&mut header).await?;
    let _version = header[0];
    let addons_len = usize::from(header[1]);
    if addons_len > 0 {
        let mut addons = vec![0u8; addons_len];
        stream.read_exact(&mut addons).await?;
    }
    Ok(())
}

/// Split `"host:port"` into `(host, port)`.
fn parse_target(target: &str) -> (String, u16) {
    // Handle IPv6 bracket notation: [::1]:443
    if let Some(bracket_end) = target.rfind("]:") {
        let host = target[1..bracket_end].to_owned();
        let port: u16 = target[bracket_end + 2..].parse().expect("invalid port in target");
        return (host, port);
    }
    if let Some(colon) = target.rfind(':') {
        let host = target[..colon].to_owned();
        let port: u16 = target[colon + 1..].parse().expect("invalid port in target");
        (host, port)
    } else {
        (target.to_owned(), 443)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_request_domain() {
        let uuid = [0x01u8; 16];
        let addons = &[0x0a, 0x10, b'x'];
        let buf = encode_request(&uuid, addons, "example.com:443");

        assert_eq!(buf[0], 0x01); // version
        assert_eq!(&buf[1..17], &[0x01; 16]); // UUID
        assert_eq!(buf[17], 3); // addons len
        assert_eq!(&buf[18..21], addons); // addons
        assert_eq!(buf[21], 0x01); // cmd = TCP
        assert_eq!(u16::from_be_bytes([buf[22], buf[23]]), 443); // port
        assert_eq!(buf[24], 0x02); // addr type = domain
        assert_eq!(buf[25], 11); // domain length
        assert_eq!(&buf[26..37], b"example.com");
    }

    #[test]
    fn encode_request_ipv4() {
        let uuid = [0xAA; 16];
        let buf = encode_request(&uuid, &[], "1.2.3.4:80");

        // version(1) + uuid(16) + addonslen(1) + addons(0 bytes) + cmd(1) + port(2)
        let base = 1 + 16 + 1 + 1 + 2;
        assert_eq!(buf[base], 0x01); // IPv4
        assert_eq!(&buf[base + 1..base + 5], &[1, 2, 3, 4]);
    }

    #[test]
    fn encode_request_ipv6() {
        let uuid = [0xBB; 16];
        let buf = encode_request(&uuid, &[], "[::1]:8080");

        // version(1) + uuid(16) + addonslen(1) + addons(0 bytes) + cmd(1) + port(2)
        let base = 1 + 16 + 1 + 1 + 2;
        assert_eq!(buf[base], 0x03); // IPv6
                                     // ::1 = 15 zeros + 0x01
        assert_eq!(buf[base + 16], 0x01);
    }
}
