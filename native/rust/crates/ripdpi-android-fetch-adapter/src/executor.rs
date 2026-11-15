use std::io;
use std::time::Duration;

use base64::Engine;
use http::Method;
use ripdpi_tls_profiles::selected_profile_metadata;
use tokio::sync::watch;
use tokio::time::timeout;
use url::Url;

use crate::dto::{NativeOwnedTlsHttpRequest, NativeOwnedTlsHttpResponse};
use crate::redirect::redirect_target;
use crate::redirect_hop::{MAX_REDIRECTS, prepare_redirect_hop, take_redirect};
use crate::request::execute_once;
use crate::tls_profile::{apply_profile_metadata, profile_catalog_version_string};

pub(crate) fn execute(request_json: &str) -> io::Result<String> {
    execute_inner(request_json, None)
}

pub(crate) fn execute_cancellable(request_json: &str, cancelled: watch::Receiver<bool>) -> io::Result<String> {
    execute_inner(request_json, Some(cancelled))
}

fn execute_inner(request_json: &str, cancelled: Option<watch::Receiver<bool>>) -> io::Result<String> {
    let request: NativeOwnedTlsHttpRequest =
        serde_json::from_str(request_json).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(io::Error::other)?;
    let response = runtime.block_on(async {
        let operation = async {
            timeout(Duration::from_millis(request.call_timeout_ms), execute_async(request))
                .await
                .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "native TLS fetch timed out"))?
        };
        match cancelled {
            Some(cancelled) => crate::http_cancellation::run(cancelled, operation).await,
            None => operation.await,
        }
    });
    let payload = match response {
        Ok(response) => response,
        Err(error) => NativeOwnedTlsHttpResponse::error(error),
    };
    serde_json::to_string(&payload).map_err(io::Error::other)
}

/// # Cancel safety:
/// NOT cancel-safe: a peer can apply the request before cancellation; owned transports are closed.
async fn execute_async(mut request: NativeOwnedTlsHttpRequest) -> io::Result<NativeOwnedTlsHttpResponse> {
    let profile_metadata = selected_profile_metadata(&request.tls_profile_id);
    let mut method = Method::from_bytes(request.method.as_bytes())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid method: {error}")))?;
    let mut current_url = Url::parse(&request.url)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid URL: {error}")))?;
    let mut redirects_remaining = request.max_redirects.min(MAX_REDIRECTS);

    loop {
        let response = execute_once(&method, &current_url, &request).await?;
        if let Some(location) = redirect_target(&current_url, &response)? {
            take_redirect(&mut redirects_remaining, &request.url)?;
            method = prepare_redirect_hop(response.status_code, &method, &current_url, &location, &mut request.headers);
            current_url = location;
            continue;
        }

        let mut response_payload = NativeOwnedTlsHttpResponse {
            status_code: Some(response.status_code.as_u16()),
            body_base64: Some(base64::engine::general_purpose::STANDARD.encode(response.body)),
            final_url: Some(current_url.into()),
            content_type: response
                .headers
                .get(http::header::CONTENT_TYPE)
                .and_then(|value| value.to_str().ok())
                .map(str::to_owned),
            tls_profile_catalog_version: Some(profile_catalog_version_string()),
            ..NativeOwnedTlsHttpResponse::default()
        };
        apply_profile_metadata(&mut response_payload, profile_metadata);
        return Ok(response_payload);
    }
}
