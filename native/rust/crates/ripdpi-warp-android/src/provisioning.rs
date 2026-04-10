use std::collections::BTreeMap;
use std::io;

use boring::ssl::SslVersion;
use bytes::Bytes;
use http::header::HOST;
use http::{Method, Request};
use http_body_util::{BodyExt, Full};
use hyper::client::conn::http1;
use hyper_util::rt::TokioIo;
use ripdpi_tls_profiles::configure_builder;
use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use url::Url;

const HTTP11_ALPN: &[u8] = b"\x08http/1.1";
const CHROME_STABLE_PROFILE: &str = "chrome_stable";

#[derive(Debug, Deserialize)]
pub struct NativeWarpProvisioningHttpRequest {
    pub method: String,
    pub url: String,
    #[serde(default)]
    pub headers: BTreeMap<String, String>,
    #[serde(default)]
    pub body: Option<String>,
    #[serde(default)]
    pub proxy: Option<NativeWarpProvisioningProxyConfig>,
}

#[derive(Debug, Deserialize)]
pub struct NativeWarpProvisioningProxyConfig {
    pub host: String,
    pub port: u16,
}

#[derive(Debug, Serialize)]
pub struct NativeWarpProvisioningHttpResponse {
    #[serde(rename = "statusCode")]
    pub status_code: Option<u16>,
    pub body: Option<String>,
    pub error: Option<String>,
}

pub fn execute(request_json: &str) -> io::Result<String> {
    let request: NativeWarpProvisioningHttpRequest =
        serde_json::from_str(request_json).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(io::Error::other)?;
    let response = runtime.block_on(execute_async(request));
    let payload = match response {
        Ok(response) => response,
        Err(error) => {
            NativeWarpProvisioningHttpResponse { status_code: None, body: None, error: Some(error.to_string()) }
        }
    };
    serde_json::to_string(&payload).map_err(io::Error::other)
}

async fn execute_async(request: NativeWarpProvisioningHttpRequest) -> io::Result<NativeWarpProvisioningHttpResponse> {
    let parsed = Url::parse(&request.url)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid URL: {error}")))?;
    let host = parsed
        .host_str()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "WARP provisioning URL has no host"))?
        .to_string();
    let port = parsed.port_or_known_default().unwrap_or(443);
    let path = parsed.path().to_string();
    let query_suffix = parsed.query().map(|query| format!("?{query}")).unwrap_or_default();
    let target_path = format!("{path}{query_suffix}");

    let tcp = connect_transport(&host, port, request.proxy.as_ref()).await?;
    tcp.set_nodelay(true)?;

    let mut connector =
        configure_builder(CHROME_STABLE_PROFILE).map_err(|error| io::Error::other(format!("TLS profile: {error}")))?;
    connector
        .set_min_proto_version(Some(SslVersion::TLS1_2))
        .map_err(|error| io::Error::other(format!("TLS min version: {error}")))?;
    connector
        .set_max_proto_version(Some(SslVersion::TLS1_2))
        .map_err(|error| io::Error::other(format!("TLS max version: {error}")))?;
    connector.set_alpn_protos(HTTP11_ALPN).map_err(|error| io::Error::other(format!("TLS ALPN: {error}")))?;

    let ssl = connector.build().configure().map_err(|error| io::Error::other(format!("TLS configure: {error}")))?;
    let tls = tokio_boring::connect(ssl, &host, tcp)
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("TLS handshake failed: {error}")))?;

    let (mut sender, connection) = http1::handshake(TokioIo::new(tls))
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, format!("HTTP handshake failed: {error}")))?;
    tokio::spawn(async move {
        let _ = connection.await;
    });

    let method = Method::from_bytes(request.method.as_bytes())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid method: {error}")))?;
    let body = Bytes::from(request.body.unwrap_or_default());
    let mut builder = Request::builder().method(method).uri(target_path);
    let mut has_host_header = false;
    for (name, value) in request.headers {
        if name.eq_ignore_ascii_case(HOST.as_str()) {
            has_host_header = true;
        }
        builder = builder.header(name.as_str(), value.as_str());
    }
    if !has_host_header {
        builder = builder.header(HOST, authority_header_value(&host, port));
    }
    let request = builder
        .body(Full::new(body))
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid request body: {error}")))?;

    let response = sender
        .send_request(request)
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, format!("request failed: {error}")))?;
    let status_code = response.status().as_u16();
    let body =
        response.into_body().collect().await.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionAborted, format!("response body failed: {error}"))
        })?;
    let body = String::from_utf8(body.to_bytes().to_vec())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("response body was not UTF-8: {error}")))?;
    Ok(NativeWarpProvisioningHttpResponse { status_code: Some(status_code), body: Some(body), error: None })
}

async fn connect_transport(
    host: &str,
    port: u16,
    proxy: Option<&NativeWarpProvisioningProxyConfig>,
) -> io::Result<TcpStream> {
    match proxy {
        Some(proxy) => {
            let mut stream = TcpStream::connect((proxy.host.as_str(), proxy.port)).await?;
            socks5_handshake_no_auth(&mut stream).await?;
            socks5_connect_domain(&mut stream, host, port).await?;
            Ok(stream)
        }
        None => TcpStream::connect((host, port)).await,
    }
}

fn authority_header_value(host: &str, port: u16) -> String {
    if port == 443 {
        host.to_string()
    } else {
        format!("{host}:{port}")
    }
}

async fn socks5_handshake_no_auth(stream: &mut TcpStream) -> io::Result<()> {
    stream.write_all(&[0x05, 0x01, 0x00]).await?;
    let mut response = [0u8; 2];
    stream.read_exact(&mut response).await?;
    if response != [0x05, 0x00] {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            format!("SOCKS5 handshake rejected method {:?}", response),
        ));
    }
    Ok(())
}

async fn socks5_connect_domain(stream: &mut TcpStream, host: &str, port: u16) -> io::Result<()> {
    let host_len =
        u8::try_from(host.len()).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SOCKS5 host is too long"))?;
    let mut request = Vec::with_capacity(7 + host.len());
    request.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, host_len]);
    request.extend_from_slice(host.as_bytes());
    request.extend_from_slice(&port.to_be_bytes());
    stream.write_all(&request).await?;

    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    if header[1] != 0x00 {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("SOCKS5 CONNECT failed with REP={:#04x}", header[1]),
        ));
    }
    match header[3] {
        0x01 => {
            let mut rest = [0u8; 6];
            stream.read_exact(&mut rest).await?;
        }
        0x04 => {
            let mut rest = [0u8; 18];
            stream.read_exact(&mut rest).await?;
        }
        0x03 => {
            let mut length = [0u8; 1];
            stream.read_exact(&mut length).await?;
            let mut rest = vec![0u8; usize::from(length[0]) + 2];
            stream.read_exact(&mut rest).await?;
        }
        atyp => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("SOCKS5 CONNECT returned invalid ATYP={atyp:#04x}"),
            ))
        }
    }
    Ok(())
}
