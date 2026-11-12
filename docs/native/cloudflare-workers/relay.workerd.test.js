function check(condition, message) {
  if (!condition) throw new Error(message);
}

async function connect(env, upstream = "wss://kws2.web.telegram.org/apiws") {
  const response = await env.RELAY.fetch("https://relay.test/", {
    headers: {
      Upgrade: "websocket",
      Authorization: "Bearer local-test-token",
      "X-Ripdpi-Upstream": upstream,
    },
  });
  check(response.status === 101, `upgrade returned ${response.status}`);
  const socket = response.webSocket;
  socket.binaryType = "arraybuffer";
  socket.accept();
  return socket;
}

function nextEvent(socket) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("relay event timed out")), 1000);
    const done = (event) => {
      clearTimeout(timer);
      socket.removeEventListener("message", done);
      socket.removeEventListener("close", done);
      resolve(event);
    };
    socket.addEventListener("message", done);
    socket.addEventListener("close", done);
  });
}

export const echo = {
  fetch() {
    const [client, server] = Object.values(new WebSocketPair());
    server.binaryType = "arraybuffer";
    server.accept();
    server.addEventListener("message", (event) => server.send(event.data));
    server.addEventListener("close", (event) => server.close(event.code, event.reason));
    return new Response(null, { status: 101, webSocket: client });
  },
};

export const binaryRoundTrip = {
  async test(_controller, env) {
    for (const bytes of [new Uint8Array([0, 1, 127, 128, 254, 255]), new Uint8Array(1024 * 1024).fill(173)]) {
      const socket = await connect(env);
      const received = nextEvent(socket);
      socket.send(bytes);
      const event = await received;
      check(event.type === "message", `binary relay closed with ${event.code}`);
      check(event.data instanceof ArrayBuffer, "response was not binary");
      const actual = new Uint8Array(event.data);
      check(actual.length === bytes.length && actual.every((byte, index) => byte === bytes[index]), "binary bytes changed");
      const closed = nextEvent(socket);
      socket.close(1000);
      await closed;
    }
  },
};

export const rejectedFrames = {
  async test(_controller, env) {
    for (const frame of ["text", new Uint8Array(1024 * 1024 + 1)]) {
      const socket = await connect(env);
      const closed = nextEvent(socket);
      socket.send(frame);
      const event = await closed;
      check(event.type === "close" && event.code === 1003, "invalid frame was not rejected with 1003");
      socket.close(1000);
    }
  },
};

export const canonicalGateways = {
  async test(_controller, env) {
    for (const host of ["kws1", "kws2", "kws3", "kws4", "kws5"]) {
      for (const path of ["apiws", "apiws_test"]) {
        const socket = await connect(env, `wss://${host}.web.telegram.org/${path}`);
        const closed = nextEvent(socket);
        socket.close(1000);
        await closed;
      }
    }
  },
};

export const rejectedUpstreams = {
  async test(_controller, env) {
    for (const upstream of [
      "wss://kws2-test.web.telegram.org/apiws",
      "wss://kws0.web.telegram.org/apiws",
      "wss://kws6.web.telegram.org/apiws",
      "wss://kws2.web.telegram.org.example/apiws",
      "wss://kws2.web.telegram.org/other",
      "wss://kws2.web.telegram.org/apiws_test/extra",
      "https://kws2.web.telegram.org/apiws",
      "wss://user:password@kws2.web.telegram.org/apiws",
      "wss://kws2.web.telegram.org:444/apiws",
      "wss://kws2.web.telegram.org/apiws?test=1",
      "wss://kws2.web.telegram.org/apiws#fragment",
    ]) {
      const response = await env.RELAY.fetch("https://relay.test/", {
        headers: {
          Upgrade: "websocket",
          Authorization: "Bearer local-test-token",
          "X-Ripdpi-Upstream": upstream,
        },
      });
      check(response.status === 400, `invalid upstream returned ${response.status}: ${upstream}`);
    }
  },
};
