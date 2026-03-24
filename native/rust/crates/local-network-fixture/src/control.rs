use std::io;
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

use crate::event::{event, EventLog};
use crate::fault::FaultController;
use crate::http::{HttpResponse, start_http_server};
use crate::types::{FixtureFaultSpec, FixtureManifest};

pub(crate) fn start_control_server(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    faults: FaultController,
    manifest: Arc<Mutex<FixtureManifest>>,
) -> io::Result<(JoinHandle<()>, u16)> {
    start_http_server(bind_host, port, stop, events.clone(), move |request, peer, local| {
        match (request.method.as_str(), request.path.as_str()) {
            ("GET", "/health") => HttpResponse::text("ok"),
            ("GET", "/manifest") => {
                events.record(event("control", "http", peer, local, "manifest", request.raw.len(), None));
                let snapshot = manifest
                    .lock()
                    .map_or_else(|poisoned| poisoned.into_inner().clone(), |manifest| manifest.clone());
                HttpResponse::json(serde_json::to_string(&snapshot).unwrap_or_else(|_| "{}".to_string()))
            }
            ("GET", "/events") => {
                events.record(event("control", "http", peer, local, "events", request.raw.len(), None));
                HttpResponse::json(serde_json::to_string(&events.snapshot()).unwrap_or_else(|_| "[]".to_string()))
            }
            ("POST", "/events/reset") => {
                events.clear();
                HttpResponse::text("reset")
            }
            ("GET", "/faults") => {
                events.record(event("control", "http", peer, local, "faults", request.raw.len(), None));
                HttpResponse::json(serde_json::to_string(&faults.snapshot()).unwrap_or_else(|_| "[]".to_string()))
            }
            ("POST", "/faults") => match serde_json::from_slice::<FixtureFaultSpec>(&request.body) {
                Ok(spec) => {
                    faults.set(spec.clone());
                    events.record(event(
                        "control",
                        "http",
                        peer,
                        local,
                        &format!("fault:set:{:?}:{:?}", spec.target, spec.outcome),
                        request.raw.len(),
                        None,
                    ));
                    HttpResponse::text("ok")
                }
                Err(err) => HttpResponse::bad_request(&err.to_string()),
            },
            ("POST", "/faults/reset") => {
                faults.clear();
                events.record(event("control", "http", peer, local, "faults:reset", request.raw.len(), None));
                HttpResponse::text("reset")
            }
            _ => HttpResponse::not_found(),
        }
    })
}
