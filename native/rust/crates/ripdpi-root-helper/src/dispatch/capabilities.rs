use crate::dispatch::DispatchOutcome;
use crate::handlers;

pub(crate) fn dispatch_probe_capabilities() -> DispatchOutcome {
    let (response, reply_fd) = handlers::handle_probe_capabilities();
    DispatchOutcome::command(response, reply_fd)
}
