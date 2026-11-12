using Workerd = import "/workerd/workerd.capnp";

const tests :Workerd.Config = (
  services = [
    (name = "tests", worker = (
      modules = [(name = "tests.js", esModule = embed "relay.workerd.test.js")],
      compatibilityDate = "2026-05-15",
      bindings = [(name = "RELAY", service = "relay")],
    )),
    (name = "relay", worker = (
      modules = [(name = "relay.js", esModule = embed "relay.js")],
      compatibilityDate = "2026-05-15",
      bindings = [(name = "RIPDPI_WORKER_BEARER", text = "local-test-token")],
      globalOutbound = (name = "tests", entrypoint = "echo"),
    )),
    (name = "internet", network = (allow = [])),
  ],
);
