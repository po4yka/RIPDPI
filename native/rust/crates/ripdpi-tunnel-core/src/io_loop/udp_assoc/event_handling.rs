mod dispatch;
mod event;

pub(in crate::io_loop) use dispatch::handle_udp_event;
pub(in crate::io_loop) use event::UdpEvent;
