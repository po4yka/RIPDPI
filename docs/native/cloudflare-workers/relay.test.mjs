import assert from "node:assert/strict";
import { timingSafeEqual } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";

Object.defineProperty(globalThis.crypto.subtle, "timingSafeEqual", {
  value: (left, right) => timingSafeEqual(Buffer.from(left), Buffer.from(right)),
});

const source = await readFile(new URL("./relay.js", import.meta.url), "utf8");
const moduleUrl = `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;
const worker = (await import(moduleUrl)).default;
const env = { RIPDPI_WORKER_BEARER: "operator-secret" };

function request(headers = {}, method = "GET") {
  return new Request("https://edge.example.workers.dev/relay", { method, headers });
}

test("rejects requests without a matching bearer", async () => {
  const response = await worker.fetch(request({ Upgrade: "websocket" }), env);
  assert.equal(response.status, 401);
});

test("rejects malformed and wrong-length bearers", async () => {
  for (const authorization of ["Basic operator-secret", "Bearer short", "Bearer bad token"]) {
    const response = await worker.fetch(request({ Upgrade: "websocket", Authorization: authorization }), env);
    assert.equal(response.status, 401);
  }
});

test("rejects non-WebSocket requests before dialing", async () => {
  const response = await worker.fetch(
    request({
      Authorization: "Bearer operator-secret",
      "X-Ripdpi-Upstream": "wss://kws2.web.telegram.org/apiws",
    }),
    env,
  );
  assert.equal(response.status, 426);
});

test("rejects arbitrary upstreams without calling fetch", async () => {
  let fetchCalled = false;
  globalThis.fetch = async () => {
    fetchCalled = true;
    throw new Error("unexpected fetch");
  };
  const response = await worker.fetch(
    request({
      Upgrade: "websocket",
      Authorization: "Bearer operator-secret",
      "X-Ripdpi-Upstream": "wss://127.0.0.1/admin",
    }),
    env,
  );
  assert.equal(response.status, 400);
  assert.equal(fetchCalled, false);
});

test("dials only the canonical Telegram gateway with binary subprotocol", async () => {
  let observed;
  globalThis.fetch = async (url, init) => {
    observed = { url, init };
    return new Response(null, { status: 500 });
  };
  const response = await worker.fetch(
    request({
      Upgrade: "websocket",
      Authorization: "Bearer operator-secret",
      "X-Ripdpi-Upstream": "wss://kws2.web.telegram.org/apiws",
    }),
    env,
  );
  assert.equal(response.status, 502);
  assert.equal(observed.url, "https://kws2.web.telegram.org/apiws");
  assert.deepEqual(observed.init.headers, {
    Upgrade: "websocket",
    "Sec-WebSocket-Protocol": "binary",
  });
});
