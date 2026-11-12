// Nostr events and relay connections (same relays and format as the Android app).
import WebSocket from "ws";
import crypto from "node:crypto";
import { hex, publicKey, sha256, signSchnorr, unhex, verifySchnorr } from "./crypto.js";

export const DEFAULT_RELAYS = [
  "wss://relay.damus.io",
  "wss://nos.lol",
  "wss://relay.primal.net",
  "wss://nostr.mom",
  "wss://relay.nostr.net",
];

function quote(s) {
  let out = '"';
  for (const ch of s) {
    const c = ch.codePointAt(0);
    if (ch === '"') out += '\\"';
    else if (ch === "\\") out += "\\\\";
    else if (ch === "\n") out += "\\n";
    else if (ch === "\r") out += "\\r";
    else if (ch === "\t") out += "\\t";
    else if (ch === "\b") out += "\\b";
    else if (ch === "\f") out += "\\f";
    else if (c < 0x20) out += "\\u" + c.toString(16).padStart(4, "0");
    else out += ch;
  }
  return out + '"';
}

const tagsJson = (tags) => "[" + tags.map((t) => "[" + t.map(quote).join(",") + "]").join(",") + "]";

export function computeId(pubkey, createdAt, kind, tags, content) {
  return sha256(`[0,${quote(pubkey)},${createdAt},${kind},${tagsJson(tags)},${quote(content)}]`);
}

export function createEvent(privateKey, kind, tags, content, createdAt = Math.floor(Date.now() / 1000)) {
  const pubkey = hex(publicKey(privateKey));
  const id = computeId(pubkey, createdAt, kind, tags, content);
  return { id: hex(id), pubkey, created_at: createdAt, kind, tags, content, sig: hex(signSchnorr(id, privateKey)) };
}

export function verifyEvent(e) {
  try {
    const expected = hex(computeId(e.pubkey, e.created_at, e.kind, e.tags, e.content));
    if (expected !== e.id) return false;
    return verifySchnorr(unhex(expected), unhex(e.pubkey), unhex(e.sig));
  } catch {
    return false;
  }
}

export function firstTag(e, name) {
  const t = e.tags.find((t) => t.length >= 2 && t[0] === name);
  return t ? t[1] : null;
}

export const eventJson = (e) =>
  `{"id":${quote(e.id)},"pubkey":${quote(e.pubkey)},"created_at":${e.created_at},"kind":${e.kind},"tags":${tagsJson(e.tags)},"content":${quote(e.content)},"sig":${quote(e.sig)}}`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export class RelayPool {
  constructor(relays = (process.env.AIR_RELAYS ? process.env.AIR_RELAYS.split(",") : DEFAULT_RELAYS)) {
    this.relays = relays;
    this.sockets = new Map();
    this.connected = new Set();
    this.pending = new Map();
    this.subscriptions = new Map();
    this.okWaiters = new Map();
    this.started = false;
    this.listeners = new Set();
  }

  get connectedCount() {
    return this.connected.size;
  }

  start() {
    if (this.started) return;
    this.started = true;
    this.relays.forEach((url) => this.connect(url));
  }

  stop() {
    this.started = false;
    for (const s of this.sockets.values()) s.terminate?.();
    this.sockets.clear();
    this.connected.clear();
  }

  connect(url) {
    let ws;
    try {
      ws = new WebSocket(url, { handshakeTimeout: 10_000 });
    } catch {
      return this.reconnectLater(url);
    }
    this.sockets.set(url, ws);
    let pingTimer;
    ws.on("open", () => {
      this.connected.add(url);
      for (const [id, sub] of this.subscriptions) this.sendReq(ws, id, sub.filters);
      (this.pending.get(url) || []).forEach((t) => ws.send(t));
      this.pending.delete(url);
      pingTimer = setInterval(() => ws.ping?.(), 30_000);
    });
    ws.on("message", (data) => this.handle(url, data.toString()));
    const closed = () => {
      clearInterval(pingTimer);
      if (this.sockets.get(url) !== ws) return;
      this.reconnectLater(url);
    };
    ws.on("close", closed);
    ws.on("error", () => {});
  }

  reconnectLater(url) {
    this.connected.delete(url);
    this.sockets.delete(url);
    setTimeout(() => {
      if (this.started && !this.sockets.has(url)) this.connect(url);
    }, 5_000);
  }

  handle(url, text) {
    let a;
    try {
      a = JSON.parse(text);
    } catch {
      return;
    }
    if (a[0] === "EVENT") {
      const e = a[2];
      if (!e || !verifyEvent(e)) return;
      this.subscriptions.get(a[1])?.onEvent(e);
    } else if (a[0] === "EOSE") {
      this.subscriptions.get(a[1])?.onEose?.(url);
    } else if (a[0] === "OK") {
      if (a[2] === true) this.okWaiters.get(a[1])?.(true);
    }
  }

  sendReq(ws, id, filters) {
    ws.send(JSON.stringify(["REQ", id, ...filters]));
  }

  sendToAll(text) {
    for (const url of this.relays) {
      const ws = this.sockets.get(url);
      if (ws && this.connected.has(url)) ws.send(text);
      else {
        if (!this.pending.has(url)) this.pending.set(url, []);
        this.pending.get(url).push(text);
      }
    }
  }

  async publish(event, timeoutMs = 8_000) {
    this.start();
    const result = new Promise((resolve) => {
      this.okWaiters.set(event.id, resolve);
      setTimeout(() => resolve(false), timeoutMs);
    });
    this.sendToAll(`["EVENT",${eventJson(event)}]`);
    const ok = await result;
    this.okWaiters.delete(event.id);
    return ok;
  }

  send(event) {
    this.start();
    this.sendToAll(`["EVENT",${eventJson(event)}]`);
  }

  subscribe(filters, onEvent) {
    this.start();
    const id = "air-" + crypto.randomUUID().slice(0, 12);
    this.subscriptions.set(id, { filters, onEvent });
    for (const url of this.relays) {
      const ws = this.sockets.get(url);
      if (ws && this.connected.has(url)) this.sendReq(ws, id, filters);
    }
    return id;
  }

  unsubscribe(id) {
    if (!this.subscriptions.delete(id)) return;
    const close = JSON.stringify(["CLOSE", id]);
    for (const url of this.relays) if (this.connected.has(url)) this.sockets.get(url)?.send(close);
  }

  async query(filters, timeoutMs = 6_000) {
    this.start();
    for (let i = 0; i < 40 && this.connected.size === 0; i++) await sleep(100);
    const events = new Map();
    const eose = new Set();
    let finish;
    const done = new Promise((r) => (finish = r));
    const id = "airq-" + crypto.randomUUID().slice(0, 12);
    this.subscriptions.set(id, {
      filters,
      onEvent: (e) => events.set(e.id, e),
      onEose: (url) => {
        eose.add(url);
        if (eose.size >= Math.max(1, this.connected.size)) finish();
      },
    });
    for (const url of this.relays) {
      const ws = this.sockets.get(url);
      if (ws && this.connected.has(url)) this.sendReq(ws, id, filters);
    }
    await Promise.race([done, sleep(timeoutMs)]);
    this.unsubscribe(id);
    return [...events.values()];
  }
}

export const relays = new RelayPool();
