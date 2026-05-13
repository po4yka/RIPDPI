#!/usr/bin/env node
// capture-prod.mjs -- atomic "build + start + capture + cleanup" helper for
// the Play Store screenshot generator. Run via `bun run capture:prod`.
//
// Lifecycle:
//   1. spawn `next start -p ${PORT}` as a child process
//   2. poll the TCP port until the server accepts a connection (or time out)
//   3. spawn `node capture.mjs` and wait for it to exit
//   4. tear down the server (SIGTERM, escalate to SIGKILL) -- always runs,
//      even when capture fails, so port 3099 is never left bound
//   5. exit with capture's exit code; non-zero captures propagate non-zero
//
// No external dependencies: uses Node built-ins (net, child_process) only.

import { spawn } from "node:child_process";
import net from "node:net";
import process from "node:process";

const PORT = Number(process.env.CAPTURE_PORT ?? 3099);
const READY_TIMEOUT_MS = Number(process.env.CAPTURE_READY_TIMEOUT_MS ?? 60_000);
const READY_POLL_MS = 250;
const SHUTDOWN_GRACE_MS = 5_000;

/** Resolve once the server starts accepting TCP connections on `port`. */
function waitForPort(port, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const socket = net.connect({ port, host: "127.0.0.1" });
      let done = false;
      const finish = (fn) => {
        if (done) return;
        done = true;
        socket.destroy();
        fn();
      };
      socket.once("connect", () => finish(resolve));
      socket.once("error", () => {
        if (Date.now() >= deadline) {
          finish(() => reject(new Error(`Timed out waiting for :${port} after ${timeoutMs}ms`)));
        } else {
          finish(() => setTimeout(attempt, READY_POLL_MS));
        }
      });
    };
    attempt();
  });
}

/** Spawn a child, log its stdio with a prefix, and resolve on exit. */
function runChild(label, cmd, args, opts = {}) {
  const child = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"], ...opts });
  const prefix = `[${label}]`;
  child.stdout?.on("data", (chunk) => process.stdout.write(`${prefix} ${chunk}`));
  child.stderr?.on("data", (chunk) => process.stderr.write(`${prefix} ${chunk}`));
  const exited = new Promise((resolve) => {
    child.once("exit", (code, signal) => resolve({ code, signal }));
  });
  return { child, exited };
}

/** Politely terminate a child process; escalate to SIGKILL after a grace period. */
async function shutdown(child, exited) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  child.kill("SIGTERM");
  const killer = setTimeout(() => {
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
  }, SHUTDOWN_GRACE_MS);
  killer.unref?.();
  try {
    await exited;
  } finally {
    clearTimeout(killer);
  }
}

async function main() {
  // 1. Boot the production Next.js server.
  const { child: server, exited: serverExited } = runChild(
    "next",
    "bunx",
    ["--bun", "next", "start", "-p", String(PORT)],
    { env: { ...process.env, PORT: String(PORT) } },
  );

  // Bubble up server crashes during startup or capture.
  let serverCrashed = false;
  serverExited.then(({ code, signal }) => {
    if (code !== 0 && signal !== "SIGTERM" && signal !== "SIGKILL") {
      serverCrashed = true;
      console.error(`[next] server exited unexpectedly (code=${code}, signal=${signal})`);
    }
  });

  // Forward host signals to the server so Ctrl-C is clean.
  const forwardSignals = ["SIGINT", "SIGTERM", "SIGHUP"];
  const forwarder = (sig) => () => {
    if (server.exitCode === null && server.signalCode === null) server.kill(sig);
    process.exit(130);
  };
  const handlers = forwardSignals.map((sig) => {
    const h = forwarder(sig);
    process.on(sig, h);
    return [sig, h];
  });

  let captureCode = 1;
  try {
    // 2. Wait until :PORT accepts connections.
    console.log(`[capture-prod] waiting for server on :${PORT}...`);
    await waitForPort(PORT, READY_TIMEOUT_MS);
    if (serverCrashed) throw new Error("server exited before becoming ready");
    console.log(`[capture-prod] server is up; running capture...`);

    // 3. Run capture.mjs against the live server.
    const { exited: captureExited } = runChild("capture", "node", ["capture.mjs"], {
      env: { ...process.env, CAPTURE_PORT: String(PORT) },
    });
    const result = await captureExited;
    captureCode = result.code ?? 1;
    if (captureCode !== 0) {
      console.error(`[capture-prod] capture exited with code ${captureCode}`);
    } else {
      console.log(`[capture-prod] capture succeeded`);
    }
  } catch (err) {
    console.error(`[capture-prod] ${err instanceof Error ? err.message : err}`);
    captureCode = 1;
  } finally {
    // 4. Always tear the server down -- no zombies on :PORT.
    handlers.forEach(([sig, h]) => process.off(sig, h));
    await shutdown(server, serverExited);
  }

  // 5. Propagate capture's exit code.
  process.exit(captureCode);
}

main().catch((err) => {
  console.error(`[capture-prod] fatal: ${err instanceof Error ? err.stack : err}`);
  process.exit(1);
});
