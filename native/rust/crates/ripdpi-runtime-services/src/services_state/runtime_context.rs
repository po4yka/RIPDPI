use std::sync::Arc;

use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime_api::RuntimeTelemetrySink;

pub(crate) struct RuntimeContextHandle {
    pub(crate) telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
    pub(crate) runtime_context: Option<ProxyRuntimeContext>,
}

impl RuntimeContextHandle {
    pub(crate) fn new(
        telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
        runtime_context: Option<ProxyRuntimeContext>,
    ) -> Self {
        Self { telemetry, runtime_context }
    }
}
