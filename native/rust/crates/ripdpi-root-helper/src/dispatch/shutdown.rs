use ripdpi_root_helper_protocol::HelperResponse;

use crate::dispatch::DispatchOutcome;

pub(crate) fn dispatch_shutdown() -> DispatchOutcome {
    DispatchOutcome::shutdown(HelperResponse::success(serde_json::Value::Null))
}
