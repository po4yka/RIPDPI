use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use bytes::Bytes;
use smoltcp::phy::{DeviceCapabilities, Medium};
use smoltcp::time::Instant;
use tokio::sync::broadcast;

use crate::ports::PortProtocol;

use super::bus::{Bus, Event};

pub(super) struct VirtualIpDevice {
    max_transmission_unit: usize,
    bus_sender: broadcast::Sender<(u64, Event)>,
    sender_id: u64,
    process_queue: Arc<Mutex<VecDeque<Bytes>>>,
}

impl VirtualIpDevice {
    pub(super) fn new(protocol: PortProtocol, bus: Bus, max_transmission_unit: usize) -> Self {
        let mut endpoint = bus.new_endpoint();
        let bus_sender = endpoint.tx.clone();
        let sender_id = endpoint.id;
        let process_queue = Arc::new(Mutex::new(VecDeque::new()));
        {
            let process_queue = Arc::clone(&process_queue);
            tokio::spawn(async move {
                loop {
                    match endpoint.recv().await {
                        Event::Shutdown => break,
                        Event::InboundInternetPacket(packet_protocol, data) if packet_protocol == protocol => {
                            process_queue.lock().expect("process queue").push_back(data);
                            endpoint.send(Event::VirtualDeviceFed(packet_protocol));
                        }
                        _ => {}
                    }
                }
            });
        }
        Self { max_transmission_unit, bus_sender, sender_id, process_queue }
    }
}

impl smoltcp::phy::Device for VirtualIpDevice {
    type RxToken<'a>
        = DeviceRxToken
    where
        Self: 'a;
    type TxToken<'a>
        = DeviceTxToken
    where
        Self: 'a;

    fn receive(&mut self, _timestamp: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let next = self.process_queue.lock().expect("process queue").pop_front()?;
        Some((DeviceRxToken { buffer: next }, DeviceTxToken { tx: self.bus_sender.clone(), sender_id: self.sender_id }))
    }

    fn transmit(&mut self, _timestamp: Instant) -> Option<Self::TxToken<'_>> {
        Some(DeviceTxToken { tx: self.bus_sender.clone(), sender_id: self.sender_id })
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut cap = DeviceCapabilities::default();
        cap.medium = Medium::Ip;
        cap.max_transmission_unit = self.max_transmission_unit;
        cap
    }
}

pub(crate) struct DeviceRxToken {
    buffer: Bytes,
}

impl smoltcp::phy::RxToken for DeviceRxToken {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        f(&self.buffer)
    }
}

pub(crate) struct DeviceTxToken {
    tx: broadcast::Sender<(u64, Event)>,
    sender_id: u64,
}

impl smoltcp::phy::TxToken for DeviceTxToken {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buffer = vec![0u8; len];
        let result = f(&mut buffer);
        let _ = self.tx.send((self.sender_id, Event::OutboundInternetPacket(Bytes::from(buffer))));
        result
    }
}
