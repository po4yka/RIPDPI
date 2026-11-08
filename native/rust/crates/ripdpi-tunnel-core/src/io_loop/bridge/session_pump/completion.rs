use std::future::Future;
use std::pin::Pin;
use std::task::{Context, Waker};

use crate::SessionEntry;

pub(super) fn relay_finished(session: &mut SessionEntry, waker: &Waker) -> bool {
    // Pending registers the loop wake; Ready is consumed once before the entry is removed.
    Pin::new(&mut session.handle).poll(&mut Context::from_waker(waker)).is_ready()
}
