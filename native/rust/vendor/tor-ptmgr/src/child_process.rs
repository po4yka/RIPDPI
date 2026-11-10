//! Own managed children independently of a blocking stdout reader.

use std::io;
use std::process::{Child, ChildStdout, Command};
use std::sync::mpsc::{self, Sender};
use std::thread;
use std::time::{Duration, Instant};

const GRACE: Duration = Duration::from_secs(5);
type Reader = Box<dyn FnOnce(ChildStdout) + Send>;
type Work = Box<dyn FnOnce() + Send>;

pub(super) fn launch(command: Command, reader: Reader) -> io::Result<Sender<()>> {
    launch_with_reader_spawner(command, reader, |work| {
        thread::Builder::new().name("tor-pt-stdout".into()).spawn(work).map(|_| ())
    })
}

struct OwnedChild(Child);

impl Drop for OwnedChild {
    fn drop(&mut self) {
        // This runs only on the supervisor. If the OS refuses termination,
        // retain ownership and retry instead of blocking an async caller or
        // discarding an unreaped child.
        loop {
            if let Ok(Some(_)) = self.0.try_wait() {
                return;
            }
            if self.0.kill().is_ok() && self.0.wait().is_ok() {
                return;
            }
            thread::sleep(Duration::from_millis(100));
        }
    }
}

pub(super) fn launch_with_reader_spawner(
    mut command: Command,
    reader: Reader,
    spawn_reader: impl FnOnce(Work) -> io::Result<()> + Send + 'static,
) -> io::Result<Sender<()>> {
    let (shutdown, stop) = mpsc::channel();
    let reader_done = shutdown.clone();
    let (started, startup) = mpsc::sync_channel(1);
    thread::Builder::new().name("tor-pt-owner".into()).spawn(move || {
        // The supervisor exists before any process is started.
        let child = match command.spawn() {
            Ok(child) => child,
            Err(error) => {
                let _ = started.send(Err(error));
                return;
            }
        };
        let mut child = OwnedChild(child);
        let stdin = child.0.stdin.take();
        let Some(stdout) = child.0.stdout.take() else {
            let _ = started.send(Err(io::Error::other("PT stdout pipe missing")));
            return;
        };
        if stdin.is_none() {
            let _ = started.send(Err(io::Error::other("PT stdin pipe missing")));
            return;
        }
        if let Err(error) = spawn_reader(Box::new(move || {
            reader(stdout);
            let _ = reader_done.send(());
        })) {
            let _ = started.send(Err(error));
            return;
        }
        if started.send(Ok(())).is_err() {
            return;
        }
        // No periodic wakeups during normal operation: EOF and owner Drop
        // both notify this channel.
        let _ = stop.recv();
        drop(stdin);
        let deadline = Instant::now() + GRACE;
        loop {
            match child.0.try_wait() {
                Ok(Some(_)) => return,
                Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(10)),
                _ => return,
            }
        }
    })?;
    startup.recv().map_err(|_| io::Error::other("PT supervisor stopped during startup"))??;
    Ok(shutdown)
}
