function check(condition, message) {
  if (!condition) throw new Error(message);
}

async function connect(env) {
  const response = await env.RELAY.fetch("https://relay.test/", {
    headers: {
      Upgrade: "websocket",
      Authorization: "Bearer local-test-token",
      "X-Ripdpi-Upstream": "wss://kws2.web.telegram.org/apiws",
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
