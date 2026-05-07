use std::io;
use std::os::raw::c_int;

pub fn install_shutdown_signal_handlers(handler: extern "C" fn(c_int)) -> io::Result<()> {
    use nix::sys::signal::{signal, SigHandler, Signal};

    for sig in [Signal::SIGINT, Signal::SIGTERM, Signal::SIGHUP] {
        // SAFETY: the caller-provided handler must be async-signal-safe.
        unsafe { signal(sig, SigHandler::Handler(handler)) }.map_err(io::Error::from)?;
    }
    Ok(())
}
