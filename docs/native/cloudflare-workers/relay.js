const TELEGRAM_WS_HOST = /^kws[1-5](?:-test)?\.web\.telegram\.org$/;
const MAX_FRAME_BYTES = 1024 * 1024;

function reject(status) {
  return new Response(null, { status });
}

function bearerFrom(request) {
  const header = request.headers.get("Authorization") || "";
  const match = /^Bearer ([^\u0000-\u0020\u007f]+)$/.exec(header);
  return match ? match[1] : null;
}

async function bearerMatches(request, expectedBearer) {
  const encoder = new TextEncoder();
  const candidate = bearerFrom(request) || "";
  const [candidateDigest, expectedDigest] = await Promise.all([
    crypto.subtle.digest("SHA-256", encoder.encode(candidate)),
    crypto.subtle.digest("SHA-256", encoder.encode(expectedBearer)),
  ]);
  return constantTimeEqual(candidateDigest, expectedDigest);
}

function constantTimeEqual(left, right) {
  const leftBytes = new Uint8Array(left);
  const rightBytes = new Uint8Array(right);
  let diff = leftBytes.length ^ rightBytes.length;
  for (let index = 0; index < Math.max(leftBytes.length, rightBytes.length); index += 1) {
    diff |= (leftBytes[index] || 0) ^ (rightBytes[index] || 0);
  }
  return diff === 0;
}

function parseUpstream(value) {
  if (!value) return null;
  let url;
  try {
    url = new URL(value);
  } catch {
    return null;
  }
  if (url.protocol !== "wss:") return null;
  if (url.username || url.password || url.hash) return null;
  if (url.port && url.port !== "443") return null;
  if (url.pathname !== "/apiws" || url.search) return null;
  if (!TELEGRAM_WS_HOST.test(url.hostname)) return null;
  return url;
}

function fetchUpgradeUrl(upstream) {
  const url = new URL(upstream);
  url.protocol = "https:";
  return url.toString();
}

function binaryFrame(value) {
  if (value instanceof ArrayBuffer) return value.byteLength <= MAX_FRAME_BYTES ? value : null;
  if (ArrayBuffer.isView(value)) return value.byteLength <= MAX_FRAME_BYTES ? value : null;
  return null;
}

function bridge(source, destination) {
  source.addEventListener("message", (event) => {
    const frame = binaryFrame(event.data);
    if (frame) {
      destination.send(frame);
    } else {
      source.close(1003, "binary frames only");
      destination.close(1003, "binary frames only");
    }
  });
  source.addEventListener("close", (event) => destination.close(event.code, event.reason));
  source.addEventListener("error", () => destination.close(1011, "relay error"));
}

export default {
  async fetch(request, env) {
    if (request.method !== "GET") return reject(405);
    if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") return reject(426);

    const expectedBearer = env.RIPDPI_WORKER_BEARER;
    if (!expectedBearer || !(await bearerMatches(request, expectedBearer))) return reject(401);

    const upstream = parseUpstream(request.headers.get("X-Ripdpi-Upstream"));
    if (!upstream) return reject(400);

    const upstreamResponse = await fetch(fetchUpgradeUrl(upstream), {
      headers: {
        Upgrade: "websocket",
        "Sec-WebSocket-Protocol": "binary",
      },
    });
    if (upstreamResponse.status !== 101 || !upstreamResponse.webSocket) return reject(502);

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    const upstreamSocket = upstreamResponse.webSocket;
    server.accept();
    upstreamSocket.accept();
    bridge(server, upstreamSocket);
    bridge(upstreamSocket, server);

    return new Response(null, {
      status: 101,
      webSocket: client,
      headers: { "Sec-WebSocket-Protocol": "binary" },
    });
  },
};
