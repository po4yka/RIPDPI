# tor-ptmgr vendor notes

This is published tor-ptmgr 0.48.0 from
https://static.crates.io/crates/tor-ptmgr/tor-ptmgr-0.48.0.crate.
Archive SHA-256: e5a85e68659fc0627daa90cb7c1993461801ace009fc22aec230a62cdef27747.
Source revision: 7c038125baef924a4bf287429c192c5af606f79c.

The IPC patch gives the child and stdin to a supervisor independent of stdout.
Dropping AsyncPtChild requests stdin closure, bounded exit and child reaping,
even when the helper emits no further lines. It does not block an async worker.
The supervisor preserves the upstream five-second graceful exit window.
Remove this patch after upstream provides equivalent owned shutdown.

The supervisor is allocated before the process is created. Reader-thread startup
failure returns an error while the supervisor retains kill/reap ownership.
Steady state uses a blocking shutdown notification. If the OS refuses kill or
reap, the supervisor retains ownership and retries without blocking the caller;
termination cannot be guaranteed while the OS rejects it. The standalone std-only
child owner is included in the wrapper regression harness to fault-inject reader
thread allocation failure against a real local child.

Startup failures retain ChildSpawnFailed with the requested binary path and the
original I/O error. A missing executable therefore retains RetryTime::Never.
The wrapper's pt_shutdown integration suite checks this public PT launch path.
