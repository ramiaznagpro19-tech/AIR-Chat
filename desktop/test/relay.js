// Minimal Nostr relay (NIP-01) for local tests.
import { WebSocketServer } from "ws";
import { verifyEvent } from "../src/air/nostr.js";

export function startRelay(port) {
  const events = [];
  const wss = new WebSocketServer({ port });
  const subs = new Map(); // ws -> Map(id -> filters)
  const matches = (f, e) =>
    (!f.kinds || f.kinds.includes(e.kind)) &&
    (!f.authors || f.authors.includes(e.pubkey)) &&
    (!f.ids || f.ids.includes(e.id)) &&
    (f.since == null || e.created_at >= f.since) &&
    Object.entries(f).every(([k, v]) => !k.startsWith("#") || e.tags.some((t) => t[0] === k.slice(1) && v.includes(t[1])));
  wss.on("connection", (ws) => {
    subs.set(ws, new Map());
    ws.on("message", (raw) => {
      let m;
      try { m = JSON.parse(raw.toString()); } catch { return; }
      if (m[0] === "EVENT") {
        const e = m[1];
        if (!verifyEvent(e)) return ws.send(JSON.stringify(["OK", e.id, false, "invalid"]));
        if (!(e.kind >= 20000 && e.kind < 30000)) {
          if (e.kind >= 30000 && e.kind < 40000) {
            const d = e.tags.find((t) => t[0] === "d")?.[1] ?? "";
            const i = events.findIndex((x) => x.kind === e.kind && x.pubkey === e.pubkey && (x.tags.find((t) => t[0] === "d")?.[1] ?? "") === d);
            if (i >= 0) events.splice(i, 1);
          }
          events.push(e);
        }
        ws.send(JSON.stringify(["OK", e.id, true, ""]));
        for (const [client, s] of subs) for (const [id, filters] of s) if (filters.some((f) => matches(f, e))) client.send(JSON.stringify(["EVENT", id, e]));
      } else if (m[0] === "REQ") {
        const [, id, ...filters] = m;
        subs.get(ws).set(id, filters);
        for (const f of filters) {
          let list = events.filter((e) => matches(f, e));
          if (f.limit) list = list.slice(-f.limit);
          list.forEach((e) => ws.send(JSON.stringify(["EVENT", id, e])));
        }
        ws.send(JSON.stringify(["EOSE", id]));
      } else if (m[0] === "CLOSE") subs.get(ws).delete(m[1]);
    });
    ws.on("close", () => subs.delete(ws));
  });
  return wss;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  startRelay(Number(process.argv[2] || 7447));
  console.log("relay up");
}
