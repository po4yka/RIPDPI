use std::io;
use std::time::Duration;

use base64::Engine;
use http::Method;
use ripdpi_tls_profiles::selected_profile_metadata;
use tokio::time::timeout;
use url::Url;

use crate::owned_tls_http::dto::{NativeOwnedTlsHttpRequest, NativeOwnedTlsHttpResponse};
use crate::owned_tls_http::redirect::redirect_target;
use crate::owned_tls_http::request::execute_once;
use crate::owned_tls_http::tls_profile::{apply_profile_metadata, profile_catalog_version_string};

pub(crate) fn execute(request_json: &str) -> io::Result<String> {
    let request: NativeOwnedTlsHttpRequest =
        serde_json::from_str(request_json).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(io::Error::other)?;
    let response = runtime.block_on(async {
        timeout(Duration::from_millis(request.call_timeout_ms), execute_async(request))
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "native TLS fetch timed out"))?
    });
    let payload = match response {
        Ok(response) => response,
        Err(error) => NativeOwnedTlsHttpResponse::error(error),
    };
    serde_json::to_string(&payload).map_err(io::Error::other)
}

async fn execute_async(request: NativeOwnedTlsHttpRequest) -> io::Result<NativeOwnedTlsHttpResponse> {
    let profile_metadata = selected_profile_metadata(&request.tls_profile_id);
    let method = Method::from_bytes(request.method.as_bytes())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid method: {error}")))?;
    let mut current_url = Url::parse(&request.url)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid URL: {error}")))?;
    let mut redirects_remaining = request.max_redirects;

    loop {
        let response = execute_once(&method, &current_url, &request).await?;
        if let Some(location) = redirect_target(&current_url, &response)? {
            if redirects_remaining == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("too many redirects while fetching {}", request.url),
                ));
            }
            redirects_remaining -= 1;
            current_url = location;
            continue;
        }

        let mut response_payload = NativeOwnedTlsHttpResponse {
            status_code: Some(response.status_code.as_u16()),
            body_base64: Some(base64::engine::general_purpose::STANDARD.encode(response.body)),
            final_url: Some(current_url.into()),
            tls_profile_id: None,
            tls_profile_catalog_version: Some(profile_catalog_version_string()),
            tls_ja3_parity_target: None,
            tls_ja4_parity_target: None,
            tls_browser_family: None,
            tls_browser_track: None,
            tls_template_alpn: None,
            tls_template_extension_order_family: None,
            tls_template_grease_style: None,
            tls_template_supported_groups_profile: None,
            tls_template_key_share_profile: None,
            tls_template_record_choreography: None,
            tls_template_ech_capable: None,
            tls_template_ech_bootstrap_policy: None,
            tls_template_ech_bootstrap_resolver_id: None,
            tls_template_ech_outer_extension_policy: None,
            client_hello_size_hint: None,
            client_hello_invariant_status: None,
            error: None,
        };
        apply_profile_metadata(&mut response_payload, profile_metadata);
        return Ok(response_payload);
    }
}
