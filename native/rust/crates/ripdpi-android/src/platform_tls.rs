use std::sync::Arc;

use jni::objects::JObject;
use jni::JNIEnv;
use once_cell::sync::OnceCell;
use rustls::client::danger::ServerCertVerifier;

static PLATFORM_VERIFIER: OnceCell<Arc<dyn ServerCertVerifier>> = OnceCell::new();

pub(crate) fn init_platform_tls(env: &mut JNIEnv, context: JObject) {
    rustls_platform_verifier::android::init_hosted(env, context);
    let verifier = Arc::new(rustls_platform_verifier::Verifier::new());
    let _ = PLATFORM_VERIFIER.set(verifier);
}

pub(crate) fn platform_tls_verifier() -> Option<Arc<dyn ServerCertVerifier>> {
    PLATFORM_VERIFIER.get().cloned()
}
