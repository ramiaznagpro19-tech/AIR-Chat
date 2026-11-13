// AIR Chat peer-to-peer network: port of AirNetwork.kt, wire-compatible with the Android app.
import fs from "node:fs";
import path from "node:path";
import { RTCPeerConnection } from "node-datachannel/polyfill";
import {
  b64,
  hex,
  hkdf,
  open,
  orgJsonArray,
  randomBytes,
  randomPrivateKey,
  publicKey as pubOf,
  seal,
  sha256,
  sharedSecret,
  signSchnorr,
  ulid,
  unb64,
  unhex,
  verifySchnorr,
} from "./crypto.js";
import { createEvent, firstTag, relays } from "./nostr.js";
import { account, USERNAME_KIND, Vault } from "./account.js";
import { Emitter } from "./emitter.js";
import { imageSize } from "./imagesize.js";
import { servers } from "./servers.js";
import { official } from "./official.js";

export const KIND = { DM: "dm", GROUP: "group", SERVER: "server", CHANNEL: "channel" };
export const ITEM = {
  MESSAGE: "msg",
  THREAD: "thread",
  FORUM: "forum",
  REPLY: "reply",
  EDIT: "edit",
  DELETE: "delete",
  REACT: "react",
  UNREACT: "unreact",
  OP: "op",
  JOIN: "join",
};

export const SIGNAL_KIND = 21420;
const HELLO_INTERVAL_MS = 60_000;
export const MAX_FILE_SIZE = 25 * 1024 * 1024;
const CHUNK_SIZE = 16 * 1024;
const RELAY_CHUNK = 24_000;
const RELAY_SYNC_INTERVAL_MS = 5 * 60_000;
const RELAY_MAX_FILE = 3 * 1024 * 1024;
const ICE_SERVERS = ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302", "stun:stun.cloudflare.com:3478"];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const take = (s, n) => String(s ?? "").slice(0, n);

// ---- items ----

export function signedBytes(item) {
  return sha256(
    orgJsonArray([
      item.id,
      item.conversation,
      item.author,
      item.createdAt,
      item.type,
      item.text,
      item.title,
      item.tags,
      item.parent ?? "",
    ]),
  );
}

export function itemValid(item) {
  try {
    return verifySchnorr(signedBytes(item), unhex(item.author), unhex(item.signature));
  } catch {
    return false;
  }
}

export const itemToJson = (i) => ({
  id: i.id,
  conversation: i.conversation,
  author: i.author,
  createdAt: i.createdAt,
  type: i.type,
  text: i.text,
  title: i.title,
  tags: i.tags,
  parent: i.parent ?? "",
  signature: i.signature,
});

export function itemFromJson(j) {
  if (typeof j.id !== "string" || typeof j.conversation !== "string" || typeof j.author !== "string") throw new Error("bad item");
  if (typeof j.signature !== "string" || typeof j.type !== "string") throw new Error("bad item");
  return {
    id: j.id,
    conversation: j.conversation,
    author: j.author,
    createdAt: Number(j.createdAt),
    type: j.type,
    text: take(j.text, 8000),
    title: take(j.title, 200),
    tags: Array.isArray(j.tags) ? j.tags.map(String) : [],
    parent: j.parent ? String(j.parent) : null,
    signature: j.signature,
  };
}

// ---- conversations ----

export function conversationToJson(c) {
  const j = {
    id: c.id,
    group: c.isGroup,
    name: c.name,
    members: [...c.members],
    createdAt: c.createdAt,
    kind: c.kind,
    parent: c.parent ?? "",
    owner: c.owner ?? "",
    secret: c.secret ?? "",
    link: c.link ?? "",
  };
  if (c.voice) j.voice = true;
  return j;
}

export function conversationFromJson(j) {
  try {
    if (typeof j.id !== "string" || typeof j.group !== "boolean" || !Array.isArray(j.members)) return null;
    return makeConversation({
      id: j.id,
      isGroup: j.group,
      name: take(j.name, 64),
      members: j.members.map(String),
      createdAt: Number(j.createdAt) || 0,
      kind: j.kind || (j.group ? KIND.GROUP : KIND.DM),
      parent: j.parent || null,
      owner: j.owner || null,
      secret: j.secret || null,
      link: j.link || null,
      voice: j.voice === true,
    });
  } catch {
    return null;
  }
}

export function makeConversation(c) {
  return {
    id: c.id,
    isGroup: !!c.isGroup,
    name: c.name ?? "",
    members: new Set(c.members || []),
    createdAt: c.createdAt ?? Date.now(),
    kind: c.kind || (c.isGroup ? KIND.GROUP : KIND.DM),
    parent: c.parent ?? null,
    owner: c.owner ?? null,
    secret: c.secret ?? null,
    link: c.link ?? null,
    voice: !!c.voice,
  };
}

export function sameConversation(a, b) {
  if (!a || !b) return a === b;
  if (a.members.size !== b.members.size) return false;
  for (const m of a.members) if (!b.members.has(m)) return false;
  return (
    a.id === b.id &&
    a.isGroup === b.isGroup &&
    a.name === b.name &&
    a.createdAt === b.createdAt &&
    a.kind === b.kind &&
    a.parent === b.parent &&
    a.owner === b.owner &&
    a.secret === b.secret &&
    a.link === b.link &&
    a.voice === b.voice
  );
}

// ---- files ----

export const fileMetaToJson = (m) => {
  const j = { id: m.id, tag: m.tag, filename: m.filename, contentType: m.contentType, size: m.size };
  if (m.width != null) j.width = m.width;
  if (m.height != null) j.height = m.height;
  j.sha256 = m.sha256;
  return j;
};

export function fileMetaFromJson(j) {
  if (typeof j.id !== "string" || typeof j.sha256 !== "string") throw new Error("bad meta");
  return {
    id: j.id,
    tag: j.tag ?? "attachments",
    filename: take(j.filename ?? "file", 128),
    contentType: j.contentType ?? "application/octet-stream",
    size: Number(j.size) || 0,
    width: j.width != null ? Number(j.width) : null,
    height: j.height != null ? Number(j.height) : null,
    sha256: j.sha256,
  };
}

/** Kotlin's JSONObject(...).toString() for a file meta, used inside "f:" tags (Android escapes "/"). */
export function fileMetaTag(m) {
  const q = (s) => orgJsonArray([s]).slice(1, -1);
  const parts = [
    `"id":${q(m.id)}`,
    `"tag":${q(m.tag)}`,
    `"filename":${q(m.filename)}`,
    `"contentType":${q(m.contentType)}`,
    `"size":${m.size}`,
  ];
  if (m.width != null) parts.push(`"width":${m.width}`);
  if (m.height != null) parts.push(`"height":${m.height}`);
  parts.push(`"sha256":${q(m.sha256)}`);
  return "f:{" + parts.join(",") + "}";
}

// ---- the network ----

class Network extends Emitter {
  session = null;
  contacts = new Map(); // key -> {publicKey, username}
  conversations = new Map(); // id -> conversation
  items = new Map(); // id -> item
  online = new Set();
  readUpTo = new Map();
  acks = new Map();
  names = new Map();
  profiles = new Map(); // key -> {displayName, avatar, updatedAt}
  fileMetas = new Map();
  settings = new Map(); // key -> [ts, value]
  peers = new Map();
  handledSignals = new Set();
  lastHelloSent = new Map();
  inviteSubscriptions = new Map();
  wantedFiles = new Map();
  incomingParts = new Map();
  resolving = new Set();
  relayEndpoints = new Map();
  botOwners = new Map(); // bot key -> owner key
  callHandler = null;

  get selfKey() {
    return this.session?.publicKey ?? null;
  }

  get selfUsername() {
    return this.session?.username ?? null;
  }

  /** This installation (several devices can use the same account). */
  get deviceId() {
    if (!this._deviceId) {
      const file = path.join(Vault.dir, "device-id");
      try {
        this._deviceId = fs.readFileSync(file, "utf8").trim();
      } catch {}
      if (!this._deviceId || this._deviceId.length < 8) {
        this._deviceId = hex(randomBytes(8));
        try {
          fs.mkdirSync(Vault.dir, { recursive: true });
          fs.writeFileSync(file, this._deviceId);
        } catch {}
      }
    }
    return this._deviceId;
  }

  start(session) {
    if (this.session?.publicKey === session.publicKey) return;
    this.stop();
    this.session = session;
    this.vault = new Vault(`net3_${session.publicKey.slice(0, 16)}.vault`);
    this.load();
    relays.start();
    this.subscriptionId = relays.subscribe(
      [{ kinds: [SIGNAL_KIND], "#p": [session.publicKey], since: Math.floor(Date.now() / 1000) - 30 }],
      (e) => this.onSignalEvent(e),
    );
    this.refreshInviteSubscriptions();
    official.ensure();
    (async () => {
      try {
        await account.resolveOwner();
      } catch {}
      try {
        await account.resolveVerified();
      } catch {}
      official.ensure();
    })();
    this.helloTimer = setInterval(() => this.helloEveryone(), HELLO_INTERVAL_MS);
    setTimeout(() => this.helloEveryone(), 1500);
    // my other devices (phone, PC...): exchange everything
    setTimeout(() => {
      this.sendSignal(this.selfKey, { t: "devhello" });
      this.syncMyDevices(true);
    }, 2500);
    this.emit("started");
  }

  stop() {
    clearInterval(this.helloTimer);
    for (const id of this.inviteSubscriptions.values()) relays.unsubscribe(id);
    this.inviteSubscriptions.clear();
    if (this.subscriptionId) relays.unsubscribe(this.subscriptionId);
    this.subscriptionId = null;
    for (const key of [...this.peers.keys()]) this.closePeer(key);
    this.session = null;
    this.vault = null;
    this.contacts = new Map();
    this.conversations = new Map();
    this.items = new Map();
    this.online = new Set();
    this.readUpTo = new Map();
    this.acks = new Map();
    this.names = new Map();
    this.profiles = new Map();
    this.fileMetas = new Map();
    this.settings = new Map();
    this.relayEndpoints = new Map();
    this.sentToDevices = new Map();
    this.botOwners = new Map();
    this.emit("stopped");
  }

  // ---- change helpers ----

  setConversations(next) {
    const prev = this.conversations;
    this.conversations = next;
    this.save();
    this.emit("conversations", prev, next);
    this.scheduleDeviceSync();
  }

  setContacts(next) {
    const prev = this.contacts;
    this.contacts = next;
    this.save();
    this.emit("contacts", prev, next);
  }

  setNames(next) {
    const prev = this.names;
    this.names = next;
    this.save();
    this.emit("names", prev, next);
  }

  setProfiles(next) {
    const prev = this.profiles;
    this.profiles = next;
    this.save();
    this.emit("profiles", prev, next);
  }

  setOnline(next) {
    const prev = this.online;
    this.online = next;
    this.emit("online", prev, next);
  }

  // ---- direct connections: one per device of each person ----

  linkKey(publicKey, dev) {
    return `${publicKey}/${dev || ""}`;
  }

  openPeersOf(publicKey) {
    return [...this.peers.values()].filter((p) => p.publicKey === publicKey && p.isOpen);
  }

  isOpenTo(publicKey) {
    return this.openPeersOf(publicKey).length > 0;
  }

  // ---- public API ----

  username(key) {
    if (key === this.selfKey) return this.selfUsername ?? key.slice(0, 8);
    return this.contacts.get(key)?.username ?? this.names.get(key) ?? key.slice(0, 8);
  }

  resolveNames(keys) {
    const unknown = [...keys].filter(
      (k) => k && k !== this.selfKey && !this.contacts.has(k) && !this.names.has(k) && !this.resolving.has(k),
    );
    if (!unknown.length) return;
    unknown.forEach((k) => this.resolving.add(k));
    (async () => {
      try {
        const events = await relays.query([{ kinds: [USERNAME_KIND], authors: unknown }]);
        const found = new Map();
        const byKey = new Map();
        for (const e of events) {
          if (!byKey.has(e.pubkey)) byKey.set(e.pubkey, []);
          byKey.get(e.pubkey).push(e);
        }
        for (const [key, list] of byKey) {
          list.sort((a, b) => a.created_at - b.created_at);
          let name;
          try {
            const claim = JSON.parse(list[0].content);
            name = claim.username;
            if (claim.bot === true && typeof claim.owner === "string" && claim.owner.length === 64) this.rememberBot(key, claim.owner);
          } catch {}
          if (!name || !account.isUsernameValid(name)) continue;
          if ((await account.ownerOf(name)) === key) found.set(key, account.normalize(name));
        }
        if (found.size) this.setNames(new Map([...this.names, ...found]));
      } catch (e) {
        console.warn("[air] could not resolve usernames", e?.message);
      } finally {
        unknown.forEach((k) => this.resolving.delete(k));
      }
    })();
  }

  async addContact(username) {
    const name = account.normalize(username);
    const owner = await account.ownerOf(name);
    if (!owner || owner === this.selfKey) return null;
    const contact = { publicKey: owner, username: name };
    this.setContacts(new Map(this.contacts).set(owner, contact));
    this.sendContactsToMyDevices();
    this.sendHello(owner);
    return contact;
  }

  removeContact(key) {
    const next = new Map(this.contacts);
    next.delete(key);
    this.setContacts(next);
  }

  directConversationId(other) {
    return "dm:" + [this.selfKey ?? "", other].sort().join(":");
  }

  openDirect(other) {
    const id = this.directConversationId(other);
    const existing = this.conversations.get(id);
    if (existing) return existing;
    const c = makeConversation({ id, isGroup: false, name: "", members: [this.selfKey ?? "", other], createdAt: Date.now() });
    this.setConversations(new Map(this.conversations).set(id, c));
    return c;
  }

  createGroup(name, members) {
    const me = this.selfKey;
    const c = makeConversation({
      id: "group:" + hex(randomBytes(12)),
      isGroup: true,
      name: take(String(name).trim(), 64),
      members: [...members, me],
      createdAt: Date.now(),
    });
    this.setConversations(new Map(this.conversations).set(c.id, c));
    for (const m of members) {
      this.sendSignal(m, { t: "invite", conv: conversationToJson(c) });
      for (const p of this.openPeersOf(m)) this.sendConversation(p, c);
    }
    return c;
  }

  rememberBot(key, owner, name = null) {
    if (this.botOwners.get(key) === owner && (!name || this.names.get(key) === name)) return;
    this.botOwners.set(key, owner);
    if (name) this.setNames(new Map(this.names).set(key, name));
    this.save();
    this.emit("bots");
  }

  post(conversationId, type, text, { title = "", tags = [], parent = null, author = null } = {}) {
    const s = author ?? this.session;
    if (!s) return null;
    const unsigned = {
      id: ulid(),
      conversation: conversationId,
      author: s.publicKey,
      createdAt: Date.now(),
      type,
      text: take(text, 8000),
      title: take(title, 200),
      tags: tags.map((t) => String(t).trim()).filter(Boolean).slice(0, 8),
      parent,
    };
    const item = { ...unsigned, signature: hex(signSchnorr(signedBytes(unsigned), s.privateKey)) };
    this.addItems([item]);
    this.broadcastItems(conversationId, [item], null);
    // and to my other devices
    if (this.selfKey) this.sendItems(this.relayEndpoint(this.selfKey), [item]);
    for (const m of this.conversations.get(conversationId)?.members ?? []) {
      if (m !== this.selfKey && !this.isOpenTo(m)) this.sendHello(m);
    }
    return item;
  }

  // ---- servers ----

  newConversationId(prefix) {
    return prefix + ":" + hex(randomBytes(12));
  }

  putConversation(c) {
    this.setConversations(new Map(this.conversations).set(c.id, c));
    if (c.kind === KIND.SERVER) this.refreshInviteSubscriptions();
  }

  upsertConversation(c) {
    this.putConversation(c);
  }

  shareConversation(c, only = null) {
    for (const m of c.members) {
      if (m === this.selfKey || (only && !only.has(m))) continue;
      const open = this.openPeersOf(m);
      for (const p of open) this.sendConversation(p, c);
      if (!open.length) this.sendSignal(m, { t: "invite", conv: conversationToJson(c) });
    }
  }

  channelsOf(serverId) {
    return [...this.conversations.values()]
      .filter((c) => c.kind === KIND.CHANNEL && c.parent === serverId)
      .sort((a, b) => a.createdAt - b.createdAt);
  }

  createServer(name) {
    const me = this.selfKey;
    const now = Date.now();
    const server = makeConversation({
      id: this.newConversationId("server"),
      isGroup: true,
      name: take(String(name).trim(), 64),
      members: [me],
      createdAt: now,
      kind: KIND.SERVER,
      owner: me,
      secret: hex(randomPrivateKey()),
    });
    const general = makeConversation({
      id: this.newConversationId("channel"),
      isGroup: true,
      name: "general",
      members: [me],
      createdAt: now + 1,
      kind: KIND.CHANNEL,
      parent: server.id,
      owner: me,
    });
    this.putConversation(server);
    this.putConversation(general);
    servers.ensureJoinItem(server.id);
    this.ensureLink(server.id).catch(() => {});
    return [server, general];
  }

  createServerChannel(serverId, name, voice = false) {
    const server = this.conversations.get(serverId);
    const me = this.selfKey;
    if (!server || !me || server.owner !== me) return null;
    const channel = makeConversation({
      id: this.newConversationId("channel"),
      isGroup: true,
      name: take(String(name).trim(), 64),
      members: [...server.members],
      createdAt: Date.now(),
      kind: KIND.CHANNEL,
      parent: server.id,
      owner: me,
      voice,
    });
    this.putConversation(channel);
    this.shareConversation(channel);
    return channel;
  }

  renameConversation(id, name) {
    const c = this.conversations.get(id);
    if (!c) return null;
    if (c.owner && c.owner !== this.selfKey) return null;
    const renamed = { ...c, name: take(String(name).trim(), 64) };
    this.putConversation(renamed);
    this.shareConversation(renamed);
    return renamed;
  }

  addMembers(id, newMembers) {
    const c = this.conversations.get(id);
    if (!c || c.kind === KIND.DM) return null;
    const updated = { ...c, members: new Set([...c.members, ...newMembers]) };
    this.putConversation(updated);
    this.shareConversation(updated);
    if (c.kind === KIND.SERVER) {
      for (const ch of this.channelsOf(c.id)) {
        const u = { ...ch, members: new Set([...ch.members, ...newMembers]) };
        this.putConversation(u);
        this.shareConversation(u);
      }
    }
    return updated;
  }

  leaveConversation(id) {
    if (official.isOpen(id)) {
      official.setHidden(true);
      return;
    }
    const next = new Map(this.conversations);
    next.delete(id);
    for (const ch of this.channelsOf(id)) next.delete(ch.id);
    this.setConversations(next);
  }

  // ---- invitations ----

  inviteCode(serverId) {
    let server = this.conversations.get(serverId);
    if (!server) return null;
    if (!server.secret) {
      if (server.owner !== this.selfKey) return null;
      server = { ...server, secret: hex(randomPrivateKey()) };
      this.putConversation(server);
      this.shareConversation(server);
    }
    const invitePublic = pubOf(unhex(server.secret));
    const nameBytes = Buffer.from(server.name, "utf8").subarray(0, 40);
    return b64(Buffer.concat([Buffer.from(invitePublic), Buffer.from(unhex(serverId.split(":")[1] || "")), nameBytes]));
  }

  async ensureLink(serverId) {
    const server = this.conversations.get(serverId);
    if (!server) return null;
    if (server.link) return server.link;
    if (server.owner !== this.selfKey) return null;
    const secret = server.secret ?? hex(randomPrivateKey());
    const invitePublic = hex(pubOf(unhex(secret)));
    const link = await servers.claimLink({ ...server, secret }, invitePublic);
    if (!link) return null;
    const updated = { ...(this.conversations.get(serverId) ?? server), secret, link };
    this.putConversation(updated);
    this.shareConversation(updated);
    return link;
  }

  parseInvite(code) {
    try {
      const bytes = unb64(code.trim());
      if (bytes.length < 44) return null;
      return {
        inviteKey: hex(bytes.subarray(0, 32)),
        serverId: "server:" + hex(bytes.subarray(32, 44)),
        name: Buffer.from(bytes.subarray(44)).toString("utf8"),
      };
    } catch {
      return null;
    }
  }

  requestJoin(invite) {
    this.sendSignal(invite.inviteKey, { t: "join", server: invite.serverId });
  }

  onJoinRequest(from, serverId) {
    const server = this.conversations.get(serverId);
    if (!server || server.kind !== KIND.SERVER || !server.members.has(this.selfKey)) return;
    const state = servers.state(server);
    if (state.bans.has(from)) return;
    if (state.removed.has(from)) this.post(serverId, ITEM.OP, JSON.stringify({ user: from }), { title: "readd" });
    const updated = this.addMembers(serverId, [from]);
    if (!updated) return;
    const only = new Set([from]);
    this.shareConversation(updated, only);
    for (const ch of this.channelsOf(serverId)) this.shareConversation(ch, only);
  }

  refreshInviteSubscriptions() {
    if (!this.session) return;
    const wanted = new Map();
    for (const c of this.conversations.values()) {
      if (c.kind === KIND.SERVER && c.secret) wanted.set(hex(pubOf(unhex(c.secret))), c.secret);
    }
    for (const key of [...this.inviteSubscriptions.keys()]) {
      if (!wanted.has(key)) {
        relays.unsubscribe(this.inviteSubscriptions.get(key));
        this.inviteSubscriptions.delete(key);
      }
    }
    for (const [invitePublic, invitePrivate] of wanted) {
      if (this.inviteSubscriptions.has(invitePublic)) continue;
      this.inviteSubscriptions.set(
        invitePublic,
        relays.subscribe(
          [{ kinds: [SIGNAL_KIND], "#p": [invitePublic], since: Math.floor(Date.now() / 1000) - 30 }],
          (e) => this.onInviteSignal(e, invitePublic, invitePrivate),
        ),
      );
    }
  }

  onInviteSignal(event, invitePublic, invitePrivate) {
    if (this.handledSignals.has(event.id)) return;
    this.handledSignals.add(event.id);
    if (Date.now() / 1000 - event.created_at > 120) return;
    const from = event.pubkey;
    if (from === this.selfKey) return;
    let json;
    try {
      const shared = sharedSecret(unhex(invitePrivate), unhex(from));
      const key = hkdf(shared, Buffer.from("AIR Chat"), "AIR Chat signalling v1");
      json = JSON.parse(Buffer.from(open(key, unb64(event.content), `S|${invitePublic}`)).toString("utf8"));
    } catch {
      return;
    }
    if (json.t !== "join") return;
    (async () => {
      await this.rememberSender(from, json.from_name || "");
      this.onJoinRequest(from, String(json.server || ""));
    })();
  }

  // ---- profiles ----

  ownProfile() {
    return (this.selfKey && this.profiles.get(this.selfKey)) || { displayName: null, avatar: null, updatedAt: 0 };
  }

  updateOwnProfile(displayName, avatar) {
    const me = this.selfKey;
    if (!me) return;
    const name = displayName == null ? null : take(String(displayName).trim(), 32) || null;
    this.setProfiles(new Map(this.profiles).set(me, { displayName: name, avatar: avatar || null, updatedAt: Date.now() }));
    for (const p of this.peers.values()) if (p.isOpen) this.sendProfile(p);
    this.sendProfile(this.relayEndpoint(me));
  }

  profileJson(p, withMeta = true) {
    const j = { displayName: p.displayName ?? "", avatar: p.avatar ?? "", updatedAt: p.updatedAt };
    if (withMeta && p.avatar) {
      const meta = this.fileMetas.get(p.avatar);
      if (meta) j.avatarMeta = fileMetaToJson(meta);
    }
    return j;
  }

  profileFromJson(j) {
    return { displayName: take(j.displayName, 32).trim() ? take(j.displayName, 32) : null, avatar: j.avatar || null, updatedAt: Number(j.updatedAt) || 0 };
  }

  sendProfile(peer) {
    const own = this.selfKey && this.profiles.get(this.selfKey);
    if (own) this.send(peer, { t: "profile", profile: this.profileJson(own) });
    // profiles of the bots this device hosts
    for (const [key, owner] of this.botOwners) {
      const p = owner === this.selfKey && this.profiles.get(key);
      if (p) this.send(peer, { t: "profile", of: key, profile: this.profileJson(p) });
    }
  }

  /** Profile of a bot hosted here: shown to everyone we are connected to. */
  updateBotProfile(key, displayName, avatar) {
    if (this.botOwners.get(key) !== this.selfKey) return;
    const name = displayName == null ? null : take(String(displayName).trim(), 32) || null;
    const profile = { displayName: name, avatar: avatar || null, updatedAt: Date.now() };
    this.setProfiles(new Map(this.profiles).set(key, profile));
    for (const p of this.peers.values()) if (p.isOpen) this.send(p, { t: "profile", of: key, profile: this.profileJson(profile) });
  }

  onProfile(peer, j, of = null) {
    // a bot's profile comes from the device of its owner
    const target = of && this.botOwners.get(of) === peer.publicKey ? of : of ? null : peer.publicKey;
    if (!target) return;
    const incoming = this.profileFromJson(j);
    if (j.avatarMeta) {
      try {
        this.rememberFileMeta(fileMetaFromJson(j.avatarMeta));
      } catch {}
    }
    const current = this.profiles.get(target);
    if (current && current.updatedAt >= incoming.updatedAt) return;
    this.setProfiles(new Map(this.profiles).set(target, incoming));
    if (incoming.avatar && !this.hasFile(incoming.avatar)) {
      this.requestFile(incoming.avatar);
      if (peer.relay) this.send(peer, { t: "fget", id: incoming.avatar });
    }
  }

  // ---- files ----

  filesDir() {
    const d = path.join(Vault.dir, "air_files");
    fs.mkdirSync(d, { recursive: true });
    return d;
  }

  fileOf(id) {
    return path.join(this.filesDir(), String(id).replace(/[^\p{L}\p{Nd}]/gu, ""));
  }

  fileMeta(id) {
    return this.fileMetas.get(id) ?? null;
  }

  hasFile(id) {
    return fs.existsSync(this.fileOf(id));
  }

  fileBytes(id) {
    try {
      return fs.readFileSync(this.fileOf(id));
    } catch {
      return null;
    }
  }

  rememberFileMeta(meta) {
    if (this.fileMetas.has(meta.id)) return;
    this.fileMetas.set(meta.id, meta);
    this.save();
  }

  storeFile(tag, filename, contentType, bytes) {
    if (bytes.length > MAX_FILE_SIZE) throw new Error("File too large");
    const id = ulid();
    const size = contentType.startsWith("image/") ? imageSize(bytes) : null;
    const meta = {
      id,
      tag,
      filename: take(filename, 128),
      contentType,
      size: bytes.length,
      width: size?.width ?? null,
      height: size?.height ?? null,
      sha256: hex(sha256(bytes)),
    };
    fs.writeFileSync(this.fileOf(id), bytes);
    this.fileMetas.set(id, meta);
    this.save();
    return meta;
  }

  attachmentsOf(item) {
    return item.tags
      .filter((t) => t.startsWith("f:"))
      .map((t) => {
        try {
          return fileMetaFromJson(JSON.parse(t.slice(2)));
        } catch {
          return null;
        }
      })
      .filter(Boolean);
  }

  requestFile(id) {
    const now = Date.now();
    if (now - (this.wantedFiles.get(id) ?? 0) < 10_000) return;
    this.wantedFiles.set(id, now);
    for (const p of this.peers.values()) if (p.isOpen) this.send(p, { t: "fget", id });
  }

  async awaitFile(id, timeoutMs = 20_000) {
    const have = this.fileBytes(id);
    if (have) return have;
    this.requestFile(id);
    const end = Date.now() + timeoutMs;
    while (Date.now() < end) {
      await sleep(300);
      if (this.hasFile(id)) return this.fileBytes(id);
      this.requestFile(id);
    }
    return null;
  }

  async onFileRequest(peer, id) {
    const bytes = this.fileBytes(id);
    if (!bytes) return;
    const meta = this.fileMetas.get(id);
    if (peer.relay && bytes.length > RELAY_MAX_FILE) return;
    const total = Math.max(1, Math.ceil(bytes.length / CHUNK_SIZE));
    for (let index = 0; index < total; index++) {
      if (peer.relay) await sleep(350);
      else {
        if (!peer.channel || !peer.isOpen) return;
        while (peer.channel.bufferedAmount > 2 * 1024 * 1024) await sleep(50);
      }
      const part = bytes.subarray(index * CHUNK_SIZE, Math.min(bytes.length, (index + 1) * CHUNK_SIZE));
      const msg = { t: "fpart", id, i: index, n: total, d: b64(part) };
      if (index === 0 && meta) msg.meta = fileMetaToJson(meta);
      this.send(peer, msg);
    }
  }

  onFilePart(j) {
    const id = String(j.id);
    if (this.hasFile(id)) return;
    if (j.meta) {
      try {
        this.rememberFileMeta(fileMetaFromJson(j.meta));
      } catch {}
    }
    const total = Number(j.n);
    if (!(total > 0) || total > MAX_FILE_SIZE / CHUNK_SIZE + 1) return;
    let parts = this.incomingParts.get(id);
    if (!parts) this.incomingParts.set(id, (parts = new Array(total).fill(null)));
    if (parts.length !== total) return;
    const index = Number(j.i);
    if (!(index >= 0 && index < total)) return;
    parts[index] = Buffer.from(unb64(j.d));
    if (parts.some((p) => p === null)) return;
    this.incomingParts.delete(id);
    const bytes = Buffer.concat(parts);
    const expected = this.fileMetas.get(id)?.sha256;
    if (!expected || hex(sha256(bytes)) !== expected) return;
    fs.writeFileSync(this.fileOf(id), bytes);
    this.emit("files");
  }

  // ---- read state & settings ----

  ack(conversationId, messageId) {
    if ((this.acks.get(conversationId) ?? "") >= messageId) return;
    this.acks.set(conversationId, messageId);
    this.save();
  }

  getSetting(key) {
    return this.settings.get(key) ?? null;
  }

  setSetting(key, ts, value) {
    this.settings.set(key, [ts, value]);
    this.save();
  }

  // ---- signalling ----

  signalKey(other) {
    const shared = sharedSecret(this.session.privateKey, unhex(other));
    return hkdf(shared, Buffer.from("AIR Chat"), "AIR Chat signalling v1");
  }

  sendCallMessage(to, payload) {
    const open = this.openPeersOf(to);
    for (const p of open) this.send(p, { t: "call", call: payload });
    if (!open.length) this.sendSignal(to, payload);
  }

  isConnected(key) {
    return this.isOpenTo(key);
  }

  sendSignal(to, payload) {
    const s = this.session;
    if (!s) return;
    // bots have no device of their own: their host already has everything
    if (this.botOwners.has(to)) return;
    try {
      const body = { ...payload, from_name: s.username, dev: this.deviceId };
      const sealed = seal(this.signalKey(to), Buffer.from(JSON.stringify(body), "utf8"), `S|${to}`);
      relays.send(createEvent(s.privateKey, SIGNAL_KIND, [["p", to]], b64(sealed)));
    } catch (e) {
      console.warn("[air] could not send signal", e?.message);
    }
  }

  sendHello(to) {
    const now = Date.now();
    if (now - (this.lastHelloSent.get(to) ?? 0) < 20_000) return;
    this.lastHelloSent.set(to, now);
    this.sendSignal(to, { t: "hello" });
  }

  helloEveryone() {
    if (!this.session) return;
    const targets = new Set([...this.contacts.keys()]);
    for (const c of this.conversations.values()) for (const m of c.members) targets.add(m);
    for (const t of targets) if (t && t !== this.selfKey && !this.isOpenTo(t)) this.sendHello(t);
    this.relaySyncEveryone();
    this.syncMyDevices(false);
  }

  // ---- my other devices (same account on phone and PC) ----

  syncMyDevices(force) {
    const me = this.selfKey;
    if (!me) return;
    const self = this.relayEndpoint(me);
    const now = Date.now();
    if (!force && now - self.syncedAt < RELAY_SYNC_INTERVAL_MS) return;
    self.syncedAt = now;
    self.synced.clear();
    this.sendContactsToMyDevices();
    this.sendProfile(self);
    for (const c of this.conversations.values()) this.sendConversation(self, c);
    for (const c of this.conversations.values()) this.sendSync(self, c.id);
  }

  sendContactsToMyDevices() {
    if (!this.selfKey) return;
    this.relayDeliver(this.selfKey, {
      t: "contacts",
      list: [...this.contacts.values()].map((c) => ({ key: c.publicKey, username: c.username })),
    });
  }

  scheduleDeviceSync() {
    clearTimeout(this.deviceSyncTimer);
    this.deviceSyncTimer = setTimeout(() => {
      const me = this.selfKey;
      if (!me) return;
      const self = this.relayEndpoint(me);
      const sent = (this.sentToDevices ??= new Map());
      for (const c of this.conversations.values()) {
        const json = JSON.stringify(conversationToJson(c));
        if (sent.get(c.id) === json) continue;
        sent.set(c.id, json);
        this.sendConversation(self, c);
        this.sendSync(self, c.id);
      }
    }, 2000);
  }

  // ---- relays as a fallback when no direct connection is possible (same as the phone app) ----

  relayEndpoint(key) {
    let e = this.relayEndpoints.get(key);
    if (!e) this.relayEndpoints.set(key, (e = { publicKey: key, synced: new Set(), relay: true, syncedAt: 0, isOpen: true }));
    return e;
  }

  relayDeliver(key, json) {
    if (json.t === "items" && Array.isArray(json.items)) {
      let batch = [];
      let size = 0;
      for (const item of json.items) {
        const length = JSON.stringify(item).length;
        if (batch.length && size + length > RELAY_CHUNK) {
          this.sendSignal(key, { t: "relay", d: { t: "items", items: batch } });
          batch = [];
          size = 0;
        }
        batch.push(item);
        size += length;
      }
      if (batch.length) this.sendSignal(key, { t: "relay", d: { t: "items", items: batch } });
      return;
    }
    this.sendSignal(key, { t: "relay", d: json });
  }

  relaySyncEveryone() {
    const me = this.selfKey;
    if (!me) return;
    const now = Date.now();
    const byMember = new Map();
    for (const c of this.conversations.values()) {
      if (official.isOpen(c.id)) continue;
      for (const m of c.members) {
        if (m === me) continue;
        if (!byMember.has(m)) byMember.set(m, []);
        byMember.get(m).push(c.id);
      }
    }
    for (const [key, ids] of byMember) {
      if (this.isOpenTo(key)) continue;
      const relay = this.relayEndpoint(key);
      if (now - relay.syncedAt < RELAY_SYNC_INTERVAL_MS) continue;
      relay.syncedAt = now;
      relay.synced.clear();
      this.sendProfile(relay);
      for (const id of ids) {
        const c = this.conversations.get(id);
        if (c?.isGroup) this.sendConversation(relay, c);
        this.sendSync(relay, id);
      }
    }
  }

  onSignalEvent(event) {
    const s = this.session;
    if (!s) return;
    if (this.handledSignals.has(event.id)) return;
    this.handledSignals.add(event.id);
    if (firstTag(event, "p") !== s.publicKey) return;
    if (Date.now() / 1000 - event.created_at > 120) return;
    const from = event.pubkey;
    let json;
    try {
      json = JSON.parse(Buffer.from(open(this.signalKey(from), unb64(event.content), `S|${s.publicKey}`)).toString("utf8"));
    } catch {
      return;
    }
    if (from === s.publicKey) {
      // another device of this account
      if (!json.dev || json.dev === this.deviceId) return;
      if (json.t === "devhello") this.syncMyDevices(true);
      else if (json.t === "relay" && json.d) this.onData(this.relayEndpoint(from), json.d);
      return;
    }
    (async () => {
      try {
        await this.rememberSender(from, json.from_name || "");
        switch (json.t) {
          case "hello":
            return this.onHello(from, json.dev);
          case "offer":
            // an offer made for another device of this account is not for us
            if (json.to_dev && json.to_dev !== this.deviceId) return;
            return this.onOffer(from, json.dev, json.sid, json.sdp);
          case "answer":
            return this.onAnswer(from, json.dev, json.sid, json.sdp);
          case "invite":
            if (json.conv) this.mergeConversation(from, json.conv);
            return;
          case "join":
            return this.onJoinRequest(from, String(json.server || ""));
          case "relay":
            if (json.d && json.d.t !== "call") this.onData(this.relayEndpoint(from), json.d);
            return;
          default:
            if (String(json.t).startsWith("call-")) this.callHandler?.(from, json);
        }
      } catch (e) {
        console.warn("[air] signal handling failed", e?.message);
      }
    })();
  }

  async rememberSender(from, claimedName) {
    if (this.contacts.has(from) || !claimedName) return;
    let owner = null;
    try {
      owner = await account.ownerOf(claimedName);
    } catch {}
    if (owner !== from) return;
    this.setContacts(new Map(this.contacts).set(from, { publicKey: from, username: account.normalize(claimedName) }));
  }

  async onHello(from, dev = "") {
    if (process.env.AIR_NO_P2P) {
      // tests: behave like a network where direct connections never work
      this.relaySyncEveryone();
      return;
    }
    const existing = this.peers.get(this.linkKey(from, dev));
    if (existing && (existing.isOpen || Date.now() - existing.createdAt < 20_000)) return;
    if (!this.selfKey) return;
    if (this.selfKey < from) await this.connect(from, dev);
    else this.sendHello(from);
  }

  // ---- direct connections (WebRTC data channels) ----

  newPeer(publicKey, sessionId, dev = "") {
    const link = this.linkKey(publicKey, dev);
    const connection = new RTCPeerConnection({ iceServers: ICE_SERVERS.map((urls) => ({ urls })) });
    const peer = {
      publicKey,
      link,
      dev: dev || "",
      sessionId,
      connection,
      createdAt: Date.now(),
      channel: null,
      synced: new Set(),
      get isOpen() {
        return this.channel?.readyState === "open";
      },
    };
    peer.gathered = new Promise((resolve) => {
      peer.resolveGathered = resolve;
    });
    connection.onicegatheringstatechange = () => {
      if (connection.iceGatheringState === "complete") peer.resolveGathered();
    };
    connection.onicecandidate = (e) => {
      if (!e.candidate) peer.resolveGathered();
    };
    connection.ondatachannel = (e) => this.attach(peer, e.channel);
    connection.onconnectionstatechange = () => {
      if (["failed", "closed"].includes(connection.connectionState)) this.closePeer(link, peer);
    };
    this.peers.set(link, peer);
    return peer;
  }

  async connect(to, dev = "") {
    this.closePeer(this.linkKey(to, dev));
    const sessionId = hex(randomBytes(8));
    const peer = this.newPeer(to, sessionId, dev);
    this.attach(peer, peer.connection.createDataChannel("air-chat", { ordered: true }));
    const offer = await peer.connection.createOffer();
    await peer.connection.setLocalDescription(offer);
    await Promise.race([peer.gathered, sleep(6_000)]);
    this.sendSignal(to, { t: "offer", sid: sessionId, sdp: peer.connection.localDescription?.sdp ?? offer.sdp, ...(dev ? { to_dev: dev } : {}) });
  }

  async onOffer(from, dev, sessionId, sdp) {
    this.closePeer(this.linkKey(from, dev));
    const peer = this.newPeer(from, sessionId, dev);
    await peer.connection.setRemoteDescription({ type: "offer", sdp });
    const answer = await peer.connection.createAnswer();
    await peer.connection.setLocalDescription(answer);
    await Promise.race([peer.gathered, sleep(6_000)]);
    this.sendSignal(from, { t: "answer", sid: sessionId, sdp: peer.connection.localDescription?.sdp ?? answer.sdp });
  }

  async onAnswer(from, dev, sessionId, sdp) {
    // older apps do not say which device answers: match the session
    const peer =
      this.peers.get(this.linkKey(from, dev)) ??
      [...this.peers.values()].find((p) => p.publicKey === from && p.sessionId === sessionId);
    if (!peer || peer.sessionId !== sessionId) return;
    if (peer.connection.signalingState !== "have-local-offer") return;
    await peer.connection.setRemoteDescription({ type: "answer", sdp });
  }

  attach(peer, channel) {
    peer.channel = channel;
    channel.onopen = () => this.onPeerOpen(peer);
    channel.onclose = () => this.closePeer(peer.link, peer);
    channel.onmessage = (e) => {
      try {
        const text = typeof e.data === "string" ? e.data : Buffer.from(e.data).toString("utf8");
        this.onData(peer, JSON.parse(text));
      } catch (err) {
        console.warn("[air] bad data from peer", err?.message);
      }
    };
    if (channel.readyState === "open") this.onPeerOpen(peer);
  }

  onPeerOpen(peer) {
    if (peer.opened) return;
    peer.opened = true;
    this.setOnline(new Set([...this.online, peer.publicKey]));
    for (const id of official.openConversationIds()) this.sendSync(peer, id);
    this.sendProfile(peer);
    for (const id of this.fileMetas.keys()) {
      if (!this.hasFile(id) && this.wantedFiles.has(id)) this.send(peer, { t: "fget", id });
    }
    for (const c of this.conversations.values()) {
      if (!c.members.has(peer.publicKey)) continue;
      if (c.isGroup) this.sendConversation(peer, c);
      this.sendSync(peer, c.id);
    }
  }

  sendSync(peer, conversationId) {
    if (peer.synced.has(conversationId)) return;
    peer.synced.add(conversationId);
    this.send(peer, { t: "sync", conv: conversationId, ids: this.itemIdsOf(conversationId) });
  }

  onData(peer, j) {
    switch (j.t) {
      case "contacts":
        if (peer.publicKey !== this.selfKey || !Array.isArray(j.list)) return;
        {
          const next = new Map(this.contacts);
          let changed = false;
          for (const c of j.list) {
            if (typeof c.key !== "string" || c.key.length !== 64 || next.has(c.key) || c.key === this.selfKey) continue;
            next.set(c.key, { publicKey: c.key, username: account.normalize(c.username) });
            changed = true;
          }
          if (changed) this.setContacts(next);
        }
        return;
      case "profile":
        if (j.profile) this.onProfile(peer, j.profile, typeof j.of === "string" ? j.of : null);
        return;
      case "fget":
        this.onFileRequest(peer, String(j.id || ""));
        return;
      case "fpart":
        this.onFilePart(j);
        return;
      case "call":
        if (j.call) this.callHandler?.(peer.publicKey, j.call);
        return;
      case "conv": {
        if (!j.conv) return;
        this.mergeConversation(peer.publicKey, j.conv);
        const c = conversationFromJson(j.conv);
        if (c && this.conversationFor(peer.publicKey, c.id)) this.sendSync(peer, c.id);
        return;
      }
      case "sync": {
        const id = String(j.conv);
        const c =
          this.conversationFor(peer.publicKey, id) ??
          (id === this.directConversationId(peer.publicKey) ? this.openDirect(peer.publicKey) : null);
        if (!c) return;
        this.sendSync(peer, c.id);
        const theirs = new Set(Array.isArray(j.ids) ? j.ids : []);
        const missing = [...this.items.values()].filter((i) => i.conversation === c.id && !theirs.has(i.id));
        this.sendItems(peer, missing);
        return;
      }
      case "items": {
        if (!Array.isArray(j.items)) return;
        const incoming = j.items
          .map((x) => {
            try {
              return itemFromJson(x);
            } catch {
              return null;
            }
          })
          .filter(Boolean);
        const accepted = incoming.filter((item) => {
          const c =
            this.conversationFor(peer.publicKey, item.conversation) ??
            (item.conversation === this.directConversationId(peer.publicKey) ? this.openDirect(peer.publicKey) : null);
          return (
            c && itemValid(item) && (c.members.has(item.author) || official.isOpen(c.id)) && !servers.isBlocked(c.id, item.author)
          );
        });
        const added = this.addItems(accepted);
        official.onItems(added);
        if (peer.relay) {
          for (const meta of added.flatMap((i) => this.attachmentsOf(i))) {
            if (!this.hasFile(meta.id) && meta.size <= RELAY_MAX_FILE) this.send(peer, { t: "fget", id: meta.id });
          }
        }
        const byConv = new Map();
        for (const i of added) {
          if (!byConv.has(i.conversation)) byConv.set(i.conversation, []);
          byConv.get(i.conversation).push(i);
        }
        for (const [cid, list] of byConv) this.broadcastItems(cid, list, peer.publicKey);
        return;
      }
    }
  }

  conversationFor(peer, id) {
    const c = this.conversations.get(id);
    if (!c) return null;
    if (official.isOpen(id)) return c;
    return c.members.has(peer) && c.members.has(this.selfKey) ? c : null;
  }

  mergeConversation(from, json) {
    const incoming = conversationFromJson(json);
    if (!incoming || !incoming.members.has(from) || !incoming.members.has(this.selfKey)) return;
    const existing = this.conversations.get(incoming.id);
    if (existing && !existing.members.has(from)) return;
    const merged = existing
      ? {
          ...existing,
          members: new Set([...existing.members, ...incoming.members]),
          name: existing.owner && from === existing.owner ? incoming.name : existing.name,
          secret: existing.secret ?? incoming.secret,
          link: existing.link ?? incoming.link,
        }
      : incoming;
    if (!sameConversation(existing, merged)) this.setConversations(new Map(this.conversations).set(merged.id, merged));
    for (const m of incoming.members) if (m !== this.selfKey && !this.isOpenTo(m)) this.sendHello(m);
    if (incoming.kind === KIND.SERVER) {
      this.refreshInviteSubscriptions();
      servers.ensureJoinItem(incoming.id);
    }
  }

  sendConversation(peer, c) {
    this.send(peer, { t: "conv", conv: conversationToJson(c) });
  }

  broadcastItems(conversationId, list, except) {
    const c = this.conversations.get(conversationId);
    if (!c) return;
    const targets = official.isOpen(conversationId) ? [...new Set([...this.peers.values()].map((p) => p.publicKey))] : [...c.members];
    for (const t of targets) {
      if (t === this.selfKey || t === except || servers.isBlocked(conversationId, t)) continue;
      const open = this.openPeersOf(t);
      for (const p of open) this.sendItems(p, list);
      if (!open.length && !official.isOpen(conversationId)) this.sendItems(this.relayEndpoint(t), list);
    }
  }

  sendItems(peer, list) {
    for (let i = 0; i < list.length; i += 20) {
      this.send(peer, { t: "items", items: list.slice(i, i + 20).map(itemToJson) });
    }
  }

  send(peer, json) {
    if (peer.relay) return this.relayDeliver(peer.publicKey, json);
    const ch = peer.channel;
    if (!ch || ch.readyState !== "open") return;
    try {
      ch.send(JSON.stringify(json));
    } catch (e) {
      console.warn("[air] send failed", e?.message);
    }
  }

  closePeer(key, only = null) {
    const peer = this.peers.get(key);
    if (!peer || (only && peer !== only)) return;
    this.peers.delete(key);
    try {
      peer.channel?.close();
    } catch {}
    try {
      peer.connection.close();
    } catch {}
    if (this.online.has(peer.publicKey) && !this.isOpenTo(peer.publicKey)) {
      const next = new Set(this.online);
      next.delete(peer.publicKey);
      this.setOnline(next);
    }
  }

  // ---- storage ----

  itemIdsOf(id) {
    return [...this.items.values()].filter((i) => i.conversation === id).map((i) => i.id);
  }

  addItems(list) {
    for (const item of list) {
      for (const meta of this.attachmentsOf(item)) {
        this.rememberFileMeta(meta);
        if (item.author !== this.selfKey && !this.hasFile(meta.id) && meta.size <= 10 * 1024 * 1024) {
          setTimeout(() => this.requestFile(meta.id), 500);
        }
      }
    }
    const added = list.filter((i) => !this.items.has(i.id));
    if (!added.length) return [];
    for (const i of added) this.items.set(i.id, i);
    this.save();
    this.emit("items", added);
    return added;
  }

  load() {
    const raw = this.vault?.read();
    if (!raw) return;
    let j;
    try {
      j = JSON.parse(raw);
    } catch {
      return;
    }
    for (const c of j.contacts ?? []) this.contacts.set(c.key, { publicKey: c.key, username: c.username });
    for (const c of j.conversations ?? []) {
      const conv = conversationFromJson(c);
      if (conv) this.conversations.set(conv.id, conv);
    }
    for (const i of j.items ?? []) {
      try {
        const item = itemFromJson(i);
        this.items.set(item.id, item);
      } catch {}
    }
    for (const [k, v] of Object.entries(j.read ?? {})) this.readUpTo.set(k, v);
    for (const [k, v] of Object.entries(j.profiles ?? {})) this.profiles.set(k, this.profileFromJson(v));
    for (const f of j.files ?? []) {
      try {
        const m = fileMetaFromJson(f);
        this.fileMetas.set(m.id, m);
      } catch {}
    }
    for (const [k, v] of Object.entries(j.names ?? {})) this.names.set(k, v);
    for (const [k, v] of Object.entries(j.bots ?? {})) this.botOwners.set(k, v);
    for (const [k, v] of Object.entries(j.acks ?? {})) this.acks.set(k, v);
    for (const [k, v] of Object.entries(j.settings ?? {})) this.settings.set(k, v);
  }

  save() {
    if (!this.vault) return;
    clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.saveNow(), 300);
  }

  saveNow() {
    const target = this.vault;
    if (!target) return;
    const j = {
      contacts: [...this.contacts.values()].map((c) => ({ key: c.publicKey, username: c.username })),
      conversations: [...this.conversations.values()].map(conversationToJson),
      items: [...this.items.values()].sort((a, b) => a.createdAt - b.createdAt).map(itemToJson),
      read: Object.fromEntries(this.readUpTo),
      acks: Object.fromEntries(this.acks),
      names: Object.fromEntries(this.names),
      bots: Object.fromEntries(this.botOwners),
      profiles: Object.fromEntries([...this.profiles].map(([k, v]) => [k, this.profileJson(v, false)])),
      files: [...this.fileMetas.values()].map(fileMetaToJson),
      settings: Object.fromEntries(this.settings),
    };
    try {
      target.write(JSON.stringify(j));
    } catch (e) {
      console.error("[air] could not save", e);
    }
  }
}

export const network = new Network();
