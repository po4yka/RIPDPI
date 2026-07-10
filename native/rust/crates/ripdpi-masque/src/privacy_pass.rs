use std::io;

use crate::auth::{AuthHeader, PrivacyPassProviderRequest, PrivacyPassProviderResponse, build_static_auth_header};
use crate::client::MasqueClientInner;
use crate::config::MasqueAuthMode;

impl MasqueClientInner {
    pub(crate) async fn request_auth_header(&self, target: &str) -> io::Result<Option<AuthHeader>> {
        if self.config.effective_auth_mode() == MasqueAuthMode::PrivacyPass {
            return Ok(self.cached_privacy_pass_header(target).await);
        }
        build_static_auth_header(&self.config)
    }

    pub(crate) async fn cached_privacy_pass_header(&self, target: &str) -> Option<AuthHeader> {
        if self.config.effective_auth_mode() != MasqueAuthMode::PrivacyPass {
            return None;
        }

        self.privacy_pass_cache.lock().await.entry(target.to_string()).or_default().pop()
    }

    pub(crate) async fn fetch_privacy_pass_header(
        &self,
        target: &str,
        challenge_header: &str,
    ) -> io::Result<AuthHeader> {
        if self.config.effective_auth_mode() != MasqueAuthMode::PrivacyPass {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "Privacy Pass retry requested while MASQUE auth mode is not privacy_pass",
            ));
        }

        let provider_url =
            self.config.privacy_pass_provider_url.as_ref().filter(|value| !value.trim().is_empty()).ok_or_else(
                || {
                    io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "MASQUE privacy_pass mode requires a deployer-supplied token provider URL",
                    )
                },
            )?;

        let mut request = self.provider_client.post(provider_url).json(&PrivacyPassProviderRequest {
            proxy_url: self.config.url.clone(),
            target: target.to_string(),
            challenge_header: challenge_header.to_string(),
        });
        if let Some(token) =
            self.config.privacy_pass_provider_auth_token.as_ref().filter(|value| !value.trim().is_empty())
        {
            request = request.bearer_auth(token);
        }

        let response = request.send().await.map_err(|error| {
            io::Error::other(format!("Privacy Pass provider request failed: {}", error.without_url()))
        })?;
        if !response.status().is_success() {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                format!("Privacy Pass provider returned {}", response.status()),
            ));
        }

        let response: PrivacyPassProviderResponse = response.json().await.map_err(|error| {
            io::Error::new(
                io::ErrorKind::InvalidData,
                format!("invalid Privacy Pass provider body: {}", error.without_url()),
            )
        })?;
        let expires_at_epoch_ms = response.expires_at_epoch_ms;
        let mut headers = response.into_headers();
        if headers.is_empty() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "Privacy Pass provider returned no authorization headers",
            ));
        }

        let mut cache = self.privacy_pass_cache.lock().await;
        cache.entry(target.to_string()).or_default().extend(std::mem::take(&mut headers), expires_at_epoch_ms);
        cache
            .entry(target.to_string())
            .or_default()
            .pop()
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Privacy Pass provider cache was empty"))
    }
}
