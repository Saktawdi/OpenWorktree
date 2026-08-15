import http from "node:http";
import { URL } from "node:url";

const PORT = 4098;
const HOST = "127.0.0.1";
const MOCK_TOKEN_PREFIX = "mock-human-token-";

function send(res, status, headers, body) {
  res.writeHead(status, headers);
  res.end(body);
}

function sendSseHeaders(res) {
  res.writeHead(200, {
    "Content-Type": "text/event-stream",
    "Cache-Control": "no-cache, no-transform",
    Connection: "keep-alive",
    "X-Accel-Buffering": "no",
  });
}

function sseWrite(res, data, event) {
  let chunk = "";
  if (event) chunk = chunk + "event: " + event + "\n";
  const lines = data.split("\n");
  for (let i = 0; i < lines.length; i++) {
    chunk = chunk + "data: " + lines[i];
    if (i < lines.length - 1) chunk = chunk + "\n";
  }
  chunk = chunk + "\n\n";
  res.write(chunk);
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url || "/", "http://" + HOST + ":" + PORT);
  const path = u.pathname;

  if (req.method !== "GET") {
    return send(res, 405, { "Content-Type": "application/json" }, JSON.stringify({ error: "method not allowed" }));
  }

  const taskMatch = path.match(/^\/api\/tasks\/([^/]+)\/events$/);
  if (taskMatch) {
    const taskId = taskMatch[1];
    const token = u.searchParams.get("token") || "";
    if (!token.startsWith(MOCK_TOKEN_PREFIX)) {
      return send(
        res,
        401,
        { "Content-Type": "application/json" },
        JSON.stringify({
          error_code: 64,
          error: "USAGE",
          message: "token missing or mismatch (?token=)",
          detail: [],
        }),
      );
    }

    sendSseHeaders(res);

    const now = new Date().toISOString();
    const events = [
      { kind: "message", payload: JSON.stringify({ taskId: taskId, kind: "progress", percent: 10, label: "starting review engine", at: now }) },
      { kind: "message", payload: JSON.stringify({ taskId: taskId, kind: "progress", percent: 35, label: "prism parsing", at: now }) },
      { kind: "message", payload: JSON.stringify({ taskId: taskId, kind: "progress", percent: 70, label: "aggregating findings", at: now }) },
      { kind: "message", payload: JSON.stringify({ taskId: taskId, kind: "progress", percent: 95, label: "almost done", at: now }) },
      { kind: "done", payload: JSON.stringify({ taskId: taskId, status: "SUCCEEDED", finishedAt: now }) },
    ];

    let i = 0;
    const timer = setInterval(() => {
      if (i >= events.length) {
        clearInterval(timer);
        try { res.end(); } catch (e) {}
        return;
      }
      const ev = events[i++];
      if (ev.kind === "done") {
        sseWrite(res, ev.payload, "done");
      } else {
        sseWrite(res, ev.payload, "message");
      }
    }, 600);

    req.on("close", () => {
      clearInterval(timer);
      try { res.end(); } catch (e) {}
    });
    return;
  }

  send(res, 404, { "Content-Type": "application/json" }, JSON.stringify({ error: "not found" }));
});

server.listen(PORT, HOST, () => {
  console.log("[mock-sse] listening on http://" + HOST + ":" + PORT + "  (task SSE: /api/tasks/{id}/events?token=mock-human-token-...)");
});