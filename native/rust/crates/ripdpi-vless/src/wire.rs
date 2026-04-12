use std::io;
use std::net::{Ipv4Addr, Ipv6Addr};

use tokio::io::{AsyncRead, AsyncReadExt};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedRequestHeader {
    pub uuid: [u8; 16],
    pub target: String,
    pub consumed_len: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParseRequestError {
    NeedMoreData,
    Invalid(String),
}

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

/// Encode the VLESS response header.
///
/// Response format: `Version(1B) | AddonsLen(1B) | Addons(var)`.
pub fn encode_response(addons: &[u8]) -> Vec<u8> {
    let addons_len = u8::try_from(addons.len()).expect("addons must be < 256 bytes");
    let mut buf = Vec::with_capacity(2 + addons.len());
    buf.push(0x00);
    buf.push(addons_len);
    buf.extend_from_slice(addons);
    buf
}

/// Parse a VLESS request header from an in-memory buffer.
pub fn parse_request_header(bytes: &[u8]) -> Result<DecodedRequestHeader, ParseRequestError> {
    const VERSION_AND_UUID_LEN: usize = 17;
    if bytes.len() < VERSION_AND_UUID_LEN + 1 {
        return Err(ParseRequestError::NeedMoreData);
    }
    if bytes[0] != 0x01 {
        return Err(ParseRequestError::Invalid(format!("unsupported VLESS version {}", bytes[0])));
    }
    let mut uuid = [0u8; 16];
    uuid.copy_from_slice(&bytes[1..17]);

    let addons_len = usize::from(bytes[17]);
    let mut cursor = VERSION_AND_UUID_LEN + 1;
    if bytes.len() < cursor + addons_len + 3 {
        return Err(ParseRequestError::NeedMoreData);
    }
    cursor += addons_len;

    let command = bytes[cursor];
    cursor += 1;
    if command != 0x01 {
        return Err(ParseRequestError::Invalid(format!("unsupported VLESS command {command}")));
    }

    let port = u16::from_be_bytes([bytes[cursor], bytes[cursor + 1]]);
    cursor += 2;

    let address_type = bytes[cursor];
    cursor += 1;
    let host = match address_type {
        0x01 => {
            if bytes.len() < cursor + 4 {
                return Err(ParseRequestError::NeedMoreData);
            }
            let host = Ipv4Addr::new(bytes[cursor], bytes[cursor + 1], bytes[cursor + 2], bytes[cursor + 3]);
            cursor += 4;
            host.to_string()
        }

        0x02 => {
            if bytes.len() < cursor + 1 {
                return Err(ParseRequestError::NeedMoreData);
            }
            let domain_len = usize::from(bytes[cursor]);
            cursor += 1;
            if bytes.len() < cursor + domain_len {
                return Err(ParseRequestError::NeedMoreData);
            }
            let host = std::str::from_utf8(&bytes[cursor..cursor + domain_len])
                .map_err(|error| ParseRequestError::Invalid(format!("domain target is not valid UTF-8: {error}")))?;
            cursor += domain_len;
            host.to_owned()
        }

        0x03 => {
            if bytes.len() < cursor + 16 {
                return Err(ParseRequestError::NeedMoreData);
            }
            let mut raw = [0u8; 16];
            raw.copy_from_slice(&bytes[cursor..cursor + 16]);
            cursor += 16;
            Ipv6Addr::from(raw).to_string()
        }

        other => {
            return Err(ParseRequestError::Invalid(format!("unsupported VLESS address type {other}")));
        }
    };

    Ok(DecodedRequestHeader { uuid, target: format_target(&host, port), consumed_len: cursor })
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

fn format_target(host: &str, port: u16) -> String {
    if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
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

    #[test]
    fn encode_response_writes_default_success_header() {
        assert_eq!(encode_response(&[]), vec![0x00, 0x00]);
    }

    #[test]
    fn parse_request_header_round_trips_domain_target() {
        let uuid = [0x44; 16];
        let encoded = encode_request(&uuid, &[0x0a], "example.com:443");

        let decoded = parse_request_header(&encoded).expect("domain request");

        assert_eq!(uuid, decoded.uuid);
        assert_eq!("example.com:443", decoded.target);
        assert_eq!(encoded.len(), decoded.consumed_len);
    }

    #[test]
    fn parse_request_header_round_trips_ipv6_target() {
        let uuid = [0x55; 16];
        let encoded = encode_request(&uuid, &[], "[2001:db8::1]:8443");

        let decoded = parse_request_header(&encoded).expect("ipv6 request");

        assert_eq!("[2001:db8::1]:8443", decoded.target);
        assert_eq!(encoded.len(), decoded.consumed_len);
    }

    #[test]
    fn parse_request_header_reports_partial_buffers() {
        let uuid = [0x66; 16];
        let encoded = encode_request(&uuid, &[], "example.com:443");

        assert_eq!(Err(ParseRequestError::NeedMoreData), parse_request_header(&encoded[..8]));
    }

    #[test]
    fn parse_request_header_rejects_unknown_command() {
        let mut encoded = encode_request(&[0x77; 16], &[], "example.com:443");
        encoded[18] = 0x02;

        assert_eq!(
            Err(ParseRequestError::Invalid("unsupported VLESS command 2".to_string())),
            parse_request_header(&encoded),
        );
    }
}
