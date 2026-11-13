// The "server" built into AIR Chat for PC: answers the interface's API and realtime connection on
// this computer, using AIR Chat accounts and the peer-to-peer network (port of AirLocalServer.kt).
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { WebSocketServer } from "ws";
import { hex, randomBytes, ulid } from "./crypto.js";
import { account } from "./account.js";
import { channelId, deriveId, idToKey, serverId, userId } from "./ids.js";
import { bots, HELP_OWNER } from "./bots.js";
import { fileMetaTag, fileMetaToJson, ITEM, KIND, MAX_FILE_SIZE, network } from "./network.js";
import { ALL, P, servers } from "./servers.js";
import { official } from "./official.js";

const TOKEN_PREFIX = "air-local.";
const SESSION_ID = "air-session";
const ALL_PERMISSIONS = Number(ALL);
const VERIFIED_BADGE = 1 << 11;

const MSG = {
  UsernameInvalid: "Nom d'utilisateur invalide.",
  PasswordInvalid: "Le mot de passe ne respecte pas les règles (4 lettres, 3 chiffres, 2 caractères spéciaux).",
  UsernameTaken: "Ce nom d'utilisateur est déjà pris et ne pourra jamais être réutilisé.",
  WrongPassword: "Mot de passe incorrect.",
  AccountNotFound: "Aucun compte avec ce nom d'utilisateur.",
  NetworkError: "Impossible de joindre le réseau. Vérifiez votre connexion et réessayez.",
  UserNotFound: "Aucun utilisateur AIR Chat avec ce nom.",
  OwnerOffline:
    "Impossible de rejoindre : un membre de ce serveur doit avoir AIR Chat ouvert pour vous faire entrer. Réessayez plus tard.",
  UploadMissing: "Ce fichier n'est pas disponible.",
};

// The official server shows the AIR Chat logo
const OFFICIAL_ICON_FILE = fileURLToPath(new URL("../../build/icon.png", import.meta.url));
const OFFICIAL_ICON = { id: "airofficiallogo", tag: "icons", filename: "air-chat.png", contentType: "image/png", size: 0, width: 256, height: 256, sha256: "" };

const iso = (ms) => new Date(ms).toISOString();
const num = (v) => (typeof v === "bigint" ? Number(v) : v);

function knownKeys() {
  const keys = new Set();
  if (network.selfKey) keys.add(network.selfKey);
  for (const k of network.contacts.keys()) keys.add(k);
  for (const b of bots.bots) keys.add(b.publicKey);
  for (const c of network.conversations.values()) for (const m of c.members) keys.add(m);
  return keys;
}

function publicKeyOf(uid) {
  let key = idToKey.get(uid);
  if (!key) {
    knownKeys().forEach((k) => userId(k));
    key = idToKey.get(uid);
  }
  return key?.startsWith("u:") ? key.slice(2) : null;
}

function conversationOfChannel(id) {
  let key = idToKey.get(id);
  if (!key) {
    for (const k of network.conversations.keys()) channelId(k);
    key = idToKey.get(id);
  }
  if (!key?.startsWith("c:")) return null;
  const c = network.conversations.get(key.slice(2));
  return c && c.kind !== KIND.SERVER ? c : null;
}

function conversationOfServer(id) {
  let key = idToKey.get(id);
  if (!key) {
    for (const k of network.conversations.keys()) serverId(k);
    key = idToKey.get(id);
  }
  if (!key?.startsWith("s:")) return null;
  const c = network.conversations.get(key.slice(2));
  return c && c.kind === KIND.SERVER ? c : null;
}

// ---- JSON views in the format of the original API ----

function autumnJson(m) {
  const metadata = {};
  if (m.contentType.startsWith("image/")) {
    metadata.type = "Image";
    if (m.width != null) metadata.width = m.width;
    if (m.height != null) metadata.height = m.height;
  } else if (m.contentType.startsWith("video/")) metadata.type = "Video";
  else if (m.contentType.startsWith("audio/")) metadata.type = "Audio";
  else if (m.contentType.startsWith("text/")) metadata.type = "Text";
  else metadata.type = "File";
  return { _id: m.id, tag: m.tag, filename: m.filename, content_type: m.contentType, size: m.size, metadata };
}

function userJson(key) {
  const self = key === network.selfKey;
  const botOwner = network.botOwners.get(key);
  // a bot is online while the device hosting it is
  const online = self || network.online.has(key) || (!!botOwner && (botOwner === network.selfKey || network.online.has(botOwner)));
  const profile = network.profiles.get(key);
  const j = { _id: userId(key), username: network.username(key), discriminator: "0001" };
  if (profile?.displayName) j.display_name = profile.displayName;
  const avatar = profile?.avatar && network.fileMeta(profile.avatar);
  if (avatar) j.avatar = autumnJson(avatar);
  j.relationship = self ? "User" : network.contacts.has(key) ? "Friend" : "None";
  j.online = online;
  j.status = { presence: online ? "Online" : "Invisible" };
  j.badges = account.isVerified(key) ? VERIFIED_BADGE : 0;
  j.flags = 0;
  if (botOwner) j.bot = { owner: userId(botOwner) };
  return j;
}

function lastMessageId(convId) {
  let last = null;
  for (const i of network.items.values()) {
    if (i.conversation === convId && i.type === ITEM.MESSAGE && i.id.length === 26 && (!last || i.id > last)) last = i.id;
  }
  return last;
}

function channelJson(c) {
  const j = { _id: channelId(c.id) };
  const last = lastMessageId(c.id);
  if (last) j.last_message_id = last;
  if (c.kind === KIND.DM) {
    j.channel_type = "DirectMessage";
    j.active = true;
    j.recipients = [...c.members].map(userId);
  } else if (c.kind === KIND.CHANNEL) {
    const server = c.parent ? network.conversations.get(c.parent) : null;
    const meta = server ? servers.state(server).channels.get(c.id) : null;
    j.channel_type = "TextChannel";
    j.server = serverId(c.parent ?? "");
    if (c.voice) j.voice = { max_users: 8 };
    j.name = meta?.name ?? c.name;
    if (meta?.description) j.description = meta.description;
    if (meta?.defaultOverride) j.default_permissions = { a: num(meta.defaultOverride[0]), d: num(meta.defaultOverride[1]) };
    if (meta?.roleOverrides?.size) {
      j.role_permissions = Object.fromEntries([...meta.roleOverrides].map(([r, [a, d]]) => [r, { a: num(a), d: num(d) }]));
    }
  } else {
    j.channel_type = "Group";
    j.name = c.name;
    j.owner = userId(c.owner ?? [...c.members].sort()[0] ?? "");
    j.recipients = [...c.members].map(userId);
    j.permissions = ALL_PERMISSIONS;
  }
  return j;
}

function visibleChannels(server) {
  const state = servers.state(server);
  return network.channelsOf(server.id).filter((c) => state.channels.get(c.id)?.deleted !== true);
}

function roleJson(r) {
  const j = { name: r.name, permissions: { a: num(r.allow), d: num(r.deny) } };
  if (r.colour) j.colour = r.colour;
  j.hoist = r.hoist;
  j.rank = r.rank;
  return j;
}

function serverJson(c, withChannelObjects = false) {
  const state = servers.state(c);
  const j = { _id: serverId(c.id), owner: userId(servers.ownerOf(c) ?? ""), name: state.name };
  if (state.description) j.description = state.description;
  if (state.icon) j.icon = autumnJson(state.icon);
  else if (official.isOpen(c.id)) j.icon = autumnJson(OFFICIAL_ICON);
  if (state.banner) j.banner = autumnJson(state.banner);
  j.channels = visibleChannels(c).map((ch) => (withChannelObjects ? channelJson(ch) : channelId(ch.id)));
  j.default_permissions = num(state.defaultPermissions);
  j.roles = Object.fromEntries([...state.roles.values()].map((r) => [r.id, roleJson(r)]));
  if (official.isOpen(c.id)) j.flags = 1;
  return j;
}

function memberJson(server, key) {
  const state = servers.state(server);
  const j = {
    _id: { server: serverId(server.id), user: userId(key) },
    joined_at: iso(state.joinedAt.get(key) ?? server.createdAt),
    roles: state.memberRoles.get(key) ?? [],
  };
  const nick = state.nicknames.get(key);
  if (nick) j.nickname = nick;
  const timeout = state.timeouts.get(key);
  if (timeout && timeout > Date.now()) j.timeout = iso(timeout);
  return j;
}

function serverMembers(server) {
  const state = servers.state(server);
  const keys = new Set(server.members);
  if (official.isOpen(server.id)) for (const k of state.joinedAt.keys()) keys.add(k);
  return [...keys].filter((k) => !state.removed.has(k));
}

function messageViews(conversation) {
  const items = [...network.items.values()].filter((i) => i.conversation === conversation.id);
  const server = servers.serverOf(conversation.id);
  const state = server ? servers.state(server) : null;
  const owner = conversation.owner ?? (server ? servers.ownerOf(server) : null);
  const moderator = (key) => key === owner || (server && (servers.permissions(server, key) & P.MANAGE_MESSAGES) !== 0n);
  const base = new Map();
  for (const i of items) {
    if (i.type === ITEM.MESSAGE && i.id.length === 26 && (!state || !state.bans.has(i.author))) base.set(i.id, i);
  }
  const edits = items.filter((i) => i.type === ITEM.EDIT).sort((a, b) => a.createdAt - b.createdAt);
  const deleted = new Set(
    items
      .filter((d) => d.type === ITEM.DELETE)
      .filter((d) => {
        const m = base.get(d.parent);
        return m && (m.author === d.author || moderator(d.author));
      })
      .map((d) => d.parent),
  );
  const reactions = new Map();
  items
    .filter((i) => i.type === ITEM.REACT || i.type === ITEM.UNREACT)
    .sort((a, b) => a.createdAt - b.createdAt)
    .forEach((r) => {
      if (!r.parent) return;
      const emoji = r.text.slice(0, 64);
      if (!reactions.has(r.parent)) reactions.set(r.parent, new Map());
      const byEmoji = reactions.get(r.parent);
      if (!byEmoji.has(emoji)) byEmoji.set(emoji, new Set());
      if (r.type === ITEM.REACT) byEmoji.get(emoji).add(r.author);
      else byEmoji.get(emoji).delete(r.author);
    });
  const views = [];
  if (server && visibleChannels(server)[0]?.id === conversation.id) {
    for (const j of network.items.values()) {
      if (j.conversation === server.id && j.type === ITEM.JOIN && j.id.length === 26) {
        views.push({
          id: j.id,
          conversation,
          author: j.author,
          content: "",
          edited: null,
          reactions: new Map(),
          replies: [],
          attachments: [],
          system: { type: "user_joined", id: userId(j.author) },
        });
      }
    }
  }
  for (const m of base.values()) {
    if (deleted.has(m.id)) continue;
    let lastEdit = null;
    for (const e of edits) if (e.parent === m.id && e.author === m.author) lastEdit = e;
    const rx = new Map();
    for (const [emoji, users] of reactions.get(m.id) ?? []) if (users.size) rx.set(emoji, [...users].map(userId));
    views.push({
      id: m.id,
      conversation,
      author: m.author,
      content: lastEdit?.text ?? m.text,
      edited: lastEdit?.createdAt ?? null,
      reactions: rx,
      replies: m.tags.filter((t) => t.length === 26),
      attachments: network.attachmentsOf(m),
    });
  }
  return views;
}

function messageJson(m) {
  const j = { _id: m.id, channel: channelId(m.conversation.id), author: userId(m.author), content: m.content };
  if (m.system) {
    j.system = m.system;
    j.author = "00000000000000000000000000";
  }
  if (m.edited) j.edited = iso(m.edited);
  if (m.reactions.size) j.reactions = Object.fromEntries(m.reactions);
  if (m.replies.length) j.replies = m.replies;
  if (m.attachments.length) j.attachments = m.attachments.map(autumnJson);
  return j;
}

// ---- HTTP ----

const ok = (body = {}) => ({ code: 200, body });
const error = (code, type) => ({ code, body: { type } });

function messagesResponse(conversation, list, includeUsers) {
  network.resolveNames(new Set(list.map((m) => m.author)));
  const messages = list.map(messageJson);
  if (!includeUsers) return messages;
  const authors = new Set([...list.map((m) => m.author), ...conversation.members]);
  const server = conversation.parent ? network.conversations.get(conversation.parent) : null;
  return {
    messages,
    users: [...authors].map(userJson),
    members: server ? [...authors].filter((a) => server.members.has(a)).map((a) => memberJson(server, a)) : [],
  };
}

function limitsFor() {
  const size = MAX_FILE_SIZE;
  return {
    outgoing_friend_requests: 100,
    bots: 0,
    message_length: 8000,
    message_attachments: 5,
    servers: 200,
    voice_quality: 128000,
    video: true,
    video_resolution: [3840, 2160],
    video_aspect_ratio: [0.3, 3],
    file_upload_size_limits: { attachments: size, avatars: size, backgrounds: size, icons: size, banners: size, emojis: size },
  };
}

function rootJson(base) {
  return {
    revolt: "0.8.5",
    features: {
      captcha: { enabled: false, key: "" },
      email: false,
      invite_only: false,
      autumn: { enabled: true, url: `${base}/autumn` },
      january: { enabled: false, url: "" },
      livekit: { enabled: false, nodes: [] },
      limits: {
        global: {
          group_size: 100,
          message_embeds: 5,
          message_replies: 5,
          message_reactions: 20,
          server_emoji: 100,
          server_roles: 200,
          server_channels: 200,
          body_limit_size: 20_000_000,
          restrict_server_creation: [],
          new_user_hours: 0,
        },
        new_user: limitsFor(),
        default: limitsFor(),
      },
      legal_links: { terms_of_service: "", privacy_policy: "", guidelines: "" },
    },
    ws: base.replace(/^http/, "ws") + "/events",
    app: base,
    vapid: "",
    build: { commit_sha: "", commit_timestamp: "", semver: "AIR Chat", origin_url: "", timestamp: "" },
  };
}

function defaultAvatarSvg(key) {
  const colours = ["#FE1A1D", "#21B94D", "#166AFF", "#8E44AD", "#E67E22"];
  let h = 0;
  for (const ch of key) h = (h * 31 + ch.charCodeAt(0)) | 0;
  const letter = network.username(key).slice(0, 1).toUpperCase().replace(/[<>&"]/g, "");
  return `<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="${colours[(h >>> 0) % colours.length]}"/><text x="128" y="128" dy=".35em" text-anchor="middle" font-family="Arial, sans-serif" font-weight="bold" font-size="128" fill="#fff">${letter}</text></svg>`;
}

function parseMultipart(raw, boundary) {
  const delimiter = Buffer.from(`--${boundary}`);
  const start = raw.indexOf(delimiter);
  if (start < 0) return null;
  const headerEnd = raw.indexOf("\r\n\r\n", start);
  if (headerEnd < 0) return null;
  const headers = raw.subarray(start, headerEnd).toString("utf8");
  const dataStart = headerEnd + 4;
  const end = raw.indexOf(Buffer.from(`\r\n--${boundary}`), dataStart);
  if (end < 0) return null;
  return {
    filename: /filename="([^"]*)"/.exec(headers)?.[1] ?? "file",
    contentType: /content-type:\s*([^\r\n]+)/i.exec(headers)?.[1]?.trim() ?? "application/octet-stream",
    bytes: Buffer.from(raw.subarray(dataStart, end)),
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let joinLock = Promise.resolve();

async function resolveInvite(code) {
  if (code === official.LINK) return { inviteKey: "", serverId: official.serverId, name: official.NAME };
  if (servers.isLink(code)) {
    for (const c of network.conversations.values()) if (c.link === code) return { inviteKey: "", serverId: c.id, name: c.name };
    const claim = await servers.resolveLink(code);
    return claim ? { inviteKey: claim.inviteKey, serverId: claim.serverId, name: claim.name } : null;
  }
  return network.parseInvite(code);
}

function emojiJson(server, e) {
  return {
    _id: e.id,
    parent: { type: "Server", id: serverId(server.id) },
    creator_id: userId(e.creator),
    name: e.name,
    animated: network.fileMeta(e.id)?.contentType === "image/gif",
  };
}

function allEmojis() {
  const out = [];
  for (const s of network.conversations.values()) {
    if (s.kind !== KIND.SERVER) continue;
    for (const e of servers.state(s).emojis.values()) out.push([s, e]);
  }
  return out;
}

export class LocalServer {
  constructor({ port = 47821, host = "127.0.0.1", webDir = null } = {}) {
    this.port = port;
    this.host = host;
    this.webDir = webDir;
    this.base = `http://${host}:${port}`;
  }

  ensureStarted() {
    const s = account.session;
    if (!s) return null;
    network.start(s);
    return s;
  }

  async listen() {
    this.http = http.createServer((req, res) => this.handle(req, res));
    this.wss = new WebSocketServer({ noServer: true });
    this.http.on("upgrade", (req, socket, head) => {
      const url = new URL(req.url, this.base);
      if (url.pathname === "/air-call") {
        return this.wss.handleUpgrade(req, socket, head, (ws) => this.callSocket(ws, url.searchParams.get("token") ?? ""));
      }
      if (url.pathname !== "/events") return socket.destroy();
      this.wss.handleUpgrade(req, socket, head, (ws) => this.realtime(ws, url.searchParams.get("token") ?? ""));
    });
    await new Promise((resolve, reject) => {
      this.http.once("error", reject);
      this.http.listen(this.port, this.host, resolve);
    });
    return this;
  }

  close() {
    this.wss?.clients.forEach((c) => c.terminate());
    this.http?.close();
  }

  async handle(req, res) {
    const url = new URL(req.url, this.base);
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader("Access-Control-Allow-Headers", "*");
    res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,OPTIONS");
    if (req.method === "OPTIONS") return res.writeHead(204).end();
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const raw = Buffer.concat(chunks);
    let reply;
    try {
      if (url.pathname.startsWith("/api")) reply = await this.route(req, url, raw);
      else if (url.pathname.startsWith("/autumn")) reply = await this.fileRoute(req, url, raw);
      else return this.serveStatic(url, res);
    } catch (e) {
      console.error("[air] request failed", req.method, url.pathname, e);
      reply = error(400, e?.message || "InternalError");
    }
    if (reply.code === 404 && reply.body?.type === "NotFound") console.warn("[air] 404", req.method, url.pathname);
    if (reply.bytes) {
      res.writeHead(reply.code, { "Content-Type": reply.contentType ?? "application/octet-stream", "Cache-Control": "max-age=31536000" });
      return res.end(reply.bytes);
    }
    res.writeHead(reply.code, { "Content-Type": "application/json" });
    res.end(JSON.stringify(reply.body ?? {}, (k, v) => (typeof v === "bigint" ? Number(v) : v)));
  }

  serveStatic(url, res) {
    if (!this.webDir) return res.writeHead(404).end();
    let file = path.normalize(path.join(this.webDir, decodeURIComponent(url.pathname)));
    if (!file.startsWith(this.webDir)) return res.writeHead(403).end();
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(this.webDir, "index.html");
    const types = {
      ".html": "text/html; charset=utf-8",
      ".js": "text/javascript",
      ".mjs": "text/javascript",
      ".css": "text/css",
      ".svg": "image/svg+xml",
      ".png": "image/png",
      ".jpg": "image/jpeg",
      ".ico": "image/x-icon",
      ".json": "application/json",
      ".webmanifest": "application/manifest+json",
      ".woff2": "font/woff2",
      ".woff": "font/woff",
      ".ttf": "font/ttf",
      ".wasm": "application/wasm",
      ".mp3": "audio/mpeg",
      ".ogg": "audio/ogg",
      ".webp": "image/webp",
      ".gif": "image/gif",
    };
    res.writeHead(200, { "Content-Type": types[path.extname(file).toLowerCase()] ?? "application/octet-stream" });
    fs.createReadStream(file).pipe(res);
  }

  // ---- files ----

  async fileRoute(req, url, raw) {
    if (!this.ensureStarted()) return error(401, "Unauthorized");
    const s = url.pathname.split("/").filter(Boolean).slice(1);
    if (req.method === "POST" && s.length === 1) {
      const boundary = /boundary="?([^";]+)"?/.exec(req.headers["content-type"] ?? "")?.[1];
      if (!boundary) return error(400, "BadRequest");
      const part = parseMultipart(raw, boundary);
      if (!part) return error(400, "BadRequest");
      if (part.bytes.length > MAX_FILE_SIZE) return error(413, "FileTooLarge");
      const meta = network.storeFile(s[0], part.filename, part.contentType, part.bytes);
      return ok({ id: meta.id });
    }
    if (req.method === "GET" && s.length >= 2) {
      const id = s[1];
      if (id === OFFICIAL_ICON.id) {
        try {
          return { code: 200, bytes: fs.readFileSync(OFFICIAL_ICON_FILE), contentType: "image/png" };
        } catch {
          return error(404, "NotFound");
        }
      }
      if (!network.hasFile(id) && !network.fileMeta(id)) return error(404, "NotFound");
      const bytes = await network.awaitFile(id);
      if (!bytes) return error(404, "NotFound");
      return { code: 200, bytes, contentType: network.fileMeta(id)?.contentType ?? "application/octet-stream" };
    }
    return error(404, "NotFound");
  }

  // ---- API ----

  async route(req, url, raw) {
    const method = req.method;
    const s = url.pathname.split("/").filter(Boolean).slice(1);
    const pathStr = "/" + s.join("/");
    const text = raw.toString("utf8");
    let json = {};
    try {
      json = text ? JSON.parse(text) : {};
    } catch {}

    if (method === "GET" && pathStr === "/") return ok(rootJson(this.base));
    if (method === "POST" && pathStr === "/auth/session/login") return this.login(json);
    if (method === "POST" && pathStr === "/auth/account/create") return this.register(json);

    const session = this.ensureStarted();
    if (!session) return error(401, "Unauthorized");
    const self = session.publicKey;

    if (method === "GET" && pathStr === "/onboard/hello") return ok({ onboarding: false });
    if (pathStr === "/onboard/complete") return ok({});
    if (method === "GET" && pathStr === "/auth/account") return ok({ _id: userId(self), email: session.username });
    if (method === "GET" && pathStr === "/auth/session/all") return ok([{ _id: SESSION_ID, name: "AIR Chat" }]);
    if (method === "POST" && pathStr === "/auth/session/logout") {
      network.stop();
      account.logout();
      return ok({});
    }
    if (s[0] === "auth" || s[0] === "push" || s[0] === "safety" || s[0] === "policy") return ok({});
    if (pathStr === "/sync/settings/fetch") {
      const out = {};
      for (const key of json.keys ?? []) {
        const v = network.getSetting(key);
        if (v) out[key] = v;
      }
      return ok(out);
    }
    if (pathStr === "/sync/settings/set") {
      const ts = Number(url.searchParams.get("timestamp")) || Date.now();
      for (const [k, v] of Object.entries(json)) network.setSetting(k, ts, typeof v === "string" ? v : JSON.stringify(v));
      return ok({});
    }
    if (method === "GET" && pathStr === "/sync/unreads") {
      const out = [];
      for (const [cid, mid] of network.acks) {
        if (network.conversations.has(cid)) out.push({ _id: { channel: channelId(cid), user: userId(self) }, last_id: mid });
      }
      return ok(out);
    }

    if (method === "GET" && pathStr === "/users/@me") return ok(userJson(self));
    if (method === "PATCH" && pathStr === "/users/@me") {
      const current = network.ownProfile();
      const remove = json.remove ?? [];
      let name = current.displayName;
      let avatar = current.avatar;
      if ("display_name" in json) name = json.display_name;
      if ("avatar" in json) avatar = json.avatar || null;
      if (remove.includes("DisplayName")) name = null;
      if (remove.includes("Avatar")) avatar = null;
      network.updateOwnProfile(name, avatar);
      return ok(userJson(self));
    }
    if (method === "POST" && pathStr === "/users/friend") return this.addFriend(String(json.username ?? ""));
    if (method === "PATCH" && s[0] === "users" && s.length === 2) {
      // profile of one of my bots
      const key = publicKeyOf(s[1]);
      if (!key || !bots.isMine(key)) return error(403, "MissingPermission");
      const current = network.profiles.get(key) ?? { displayName: null, avatar: null };
      const remove = json.remove ?? [];
      let name = current.displayName;
      let avatar = current.avatar;
      if ("display_name" in json) name = json.display_name;
      if ("avatar" in json) avatar = json.avatar || null;
      if (remove.includes("DisplayName")) name = null;
      if (remove.includes("Avatar")) avatar = null;
      network.updateBotProfile(key, name, avatar);
      return ok(userJson(key));
    }
    if (s.length >= 2 && s[0] === "users") return this.userRoute(method, s);

    if (method === "POST" && pathStr === "/channels/create") {
      const members = (json.users ?? []).map(publicKeyOf).filter(Boolean);
      return ok(channelJson(network.createGroup(json.name || "Groupe", members)));
    }
    if (s.length >= 2 && s[0] === "channels") return this.channelRoute(method, s, json, url);

    if (method === "POST" && pathStr === "/servers/create") {
      const [server] = network.createServer(String(json.name || "").trim() || "AIR Chat");
      return ok({ server: serverJson(server), channels: visibleChannels(server).map(channelJson) });
    }
    if (s.length >= 2 && s[0] === "servers") return this.serverRoute(method, s, json);
    if (s.length >= 2 && s[0] === "invites") return this.inviteRoute(method, s.slice(1).join("/"));
    if (s.length === 3 && s[0] === "custom" && s[1] === "emoji") return this.emojiRoute(method, s[2], json);
    if (s[0] === "air" && s[1] === "discover") return ok(await servers.fetchListings());
    if (s[0] === "bots") return this.botRoute(method, s.slice(1), json);
    if (s[0] === "air" && s[1] === "share" && s[2]) return ok({ text: this.shareable(decodeURIComponent(s[2])) });
    return error(404, "NotFound");
  }

  /** What to share for an id: a server's AIR Chat link, or a person's username. */
  shareable(id) {
    const linkOf = (server) =>
      official.isOpen(server.id) ? official.LINK : server.link ?? network.inviteCode(server.id) ?? id;
    const server = conversationOfServer(id);
    if (server) return linkOf(server);
    const key = publicKeyOf(id);
    if (key) return network.username(key);
    const channel = conversationOfChannel(id);
    if (channel?.parent) {
      const srv = network.conversations.get(channel.parent);
      if (srv) return linkOf(srv);
    }
    if (channel?.kind === KIND.DM) {
      const other = [...channel.members].find((m) => m !== network.selfKey);
      if (other) return network.username(other);
    }
    return id;
  }

  // ---- bots (hosted by this computer) ----

  botJson(bot) {
    return {
      _id: userId(bot.publicKey),
      owner: userId(network.selfKey ?? ""),
      token: "air-bot-" + bot.publicKey.slice(0, 16),
      public: false,
      analytics: false,
      discoverable: false,
      flags: 0,
    };
  }

  async botRoute(method, s, json) {
    if (method === "GET" && s[0] === "@me") {
      return ok({ bots: bots.bots.map((b) => this.botJson(b)), users: bots.bots.map((b) => userJson(b.publicKey)) });
    }
    if (method === "POST" && s[0] === "create") {
      const r = await bots.create(String(json.name ?? ""));
      if (r.error) return error(400, r.error);
      return ok({ ...this.botJson(r.bot), user: userJson(r.bot.publicKey) });
    }
    const key = publicKeyOf(s[0] ?? "");
    if (!key) return error(404, "NotFound");
    const bot = bots.get(key);
    if (s.length === 1 && method === "GET") {
      if (!bot) return error(404, "NotFound");
      return ok({ bot: this.botJson(bot), user: userJson(key) });
    }
    if (s.length === 1 && method === "PATCH") return bot ? ok(this.botJson(bot)) : error(404, "NotFound");
    if (s[1] === "discover") return ok({});
    if (s.length === 1 && method === "DELETE") {
      bots.delete(key);
      return ok({});
    }
    if (s[1] === "invite" && method === "GET") {
      const user = userJson(key);
      return ok({ _id: user._id, username: user.username, ...(user.avatar ? { avatar: user.avatar } : {}), description: HELP_OWNER });
    }
    if (s[1] === "invite" && method === "POST") {
      if (!bot) return error(403, "Seul le créateur du bot peut l'ajouter (le bot est hébergé par son appareil).");
      if (json.server) {
        const server = conversationOfServer(json.server);
        if (!server || !bots.addToServer(key, server.id)) return error(400, "Impossible d'ajouter le bot à ce serveur.");
        return ok({});
      }
      if (json.group) {
        const group = conversationOfChannel(json.group);
        if (!group || !bots.addToGroup(key, group.id)) return error(400, "Impossible d'ajouter le bot à ce groupe.");
        return ok({});
      }
    }
    return error(404, "NotFound");
  }

  accountError(result) {
    const map = { UsernameInvalid: 400, PasswordInvalid: 400, UsernameTaken: 409, WrongPassword: 401, AccountNotFound: 404, NetworkError: 400 };
    return error(map[result.error] ?? 400, MSG[result.error] ?? result.error);
  }

  async login(json) {
    const result = await account.login(json.email ?? json.username ?? "", json.password ?? "");
    if (result.error) return this.accountError(result);
    network.start(result.session);
    return ok({
      result: "Success",
      _id: SESSION_ID,
      user_id: userId(result.session.publicKey),
      token: TOKEN_PREFIX + result.session.publicKey,
      name: "AIR Chat",
    });
  }

  async register(json) {
    const result = await account.register(json.email ?? json.username ?? "", json.password ?? "");
    if (result.error) return this.accountError(result);
    network.start(result.session);
    return ok({});
  }

  async addFriend(rawName) {
    const contact = await network.addContact(rawName.split("#")[0].trim());
    if (!contact) return error(404, MSG.UserNotFound);
    return ok(userJson(contact.publicKey));
  }

  async userRoute(method, s) {
    let target = s[1] === "@me" ? network.selfKey : publicKeyOf(s[1]);
    if (!target) {
      if (s[2] === "default_avatar") target = s[1];
      else return error(404, "NotFound");
    }
    network.resolveNames([target]);
    if (method === "GET" && s.length === 2) return ok(userJson(target));
    if (method === "GET" && s[2] === "profile") return ok({ content: "" });
    if (method === "GET" && s[2] === "default_avatar") {
      return { code: 200, bytes: Buffer.from(defaultAvatarSvg(target)), contentType: "image/svg+xml" };
    }
    if (method === "GET" && s[2] === "dm") return ok(channelJson(network.openDirect(target)));
    if (method === "GET" && s[2] === "mutual") return ok({ users: [], servers: [], channels: [] });
    if (s[2] === "friend" && method === "PUT") {
      await network.addContact(network.username(target));
      return ok(userJson(target));
    }
    if (s[2] === "friend" && method === "DELETE") {
      network.removeContact(target);
      return ok(userJson(target));
    }
    if (s[2] === "block") return ok(userJson(target));
    return error(404, "NotFound");
  }

  async channelRoute(method, s, json, url) {
    const conversation = conversationOfChannel(s[1]);
    if (!conversation) return error(404, "NotFound");
    const sub = s.slice(2);
    const server = servers.serverOf(conversation.id);
    if (!sub.length && method === "GET") return ok(channelJson(conversation));
    if (!sub.length && method === "PATCH") {
      if (server && conversation.kind === KIND.CHANNEL) {
        const remove = json.remove ?? [];
        const op = { id: conversation.id };
        if ("name" in json) op.name = json.name;
        if ("description" in json) op.description = json.description;
        if (remove.includes("Description")) op.description = "";
        servers.op(server.id, "channel", op);
        return ok(channelJson(conversation));
      }
      const updated = json.name ? network.renameConversation(conversation.id, json.name) : null;
      return ok(channelJson(updated ?? conversation));
    }
    if (!sub.length && method === "DELETE") {
      if (server && conversation.kind === KIND.CHANNEL) servers.op(server.id, "channel", { id: conversation.id, deleted: true });
      else network.leaveConversation(conversation.id);
      return ok({});
    }
    if (sub.length === 2 && sub[0] === "permissions") {
      if (!server) return ok(channelJson(conversation));
      const p = json.permissions ?? {};
      const ad = { a: Number(p.allow) || 0, d: Number(p.deny) || 0 };
      const op = { id: conversation.id };
      if (sub[1] === "default") op.defaultOverride = ad;
      else op.roleOverride = { ...ad, role: sub[1] };
      servers.op(server.id, "channel", op);
      return ok(channelJson(conversation));
    }
    if (sub.length === 1 && sub[0] === "members") return ok([...conversation.members].map(userJson));
    if (sub.length === 2 && sub[0] === "recipients") {
      if (method === "PUT") {
        const key = publicKeyOf(sub[1]);
        if (key) network.addMembers(conversation.id, [key]);
      }
      return ok({});
    }
    if (sub.length === 2 && sub[0] === "ack") {
      network.ack(conversation.id, sub[1]);
      return ok({});
    }
    if (sub[0] === "typing") return ok({});
    if (sub[0] === "join_call") return error(400, "NotAvailable");
    if (sub.length === 1 && sub[0] === "invites" && method === "POST") {
      const srv = conversation.parent ? network.conversations.get(conversation.parent) : null;
      if (!srv) return error(400, "NotAvailable");
      const link = srv.link ?? (await network.ensureLink(srv.id)) ?? network.inviteCode(srv.id);
      if (!link) return error(400, "NotAvailable");
      return ok({ type: "Server", _id: link, server: serverId(srv.id), creator: userId(network.selfKey), channel: channelId(conversation.id) });
    }
    if (sub.length === 1 && sub[0] === "search") {
      const q = String(json.query ?? "").toLowerCase();
      const found = messageViews(conversation)
        .filter((m) => q && m.content.toLowerCase().includes(q))
        .sort((a, b) => (a.id < b.id ? 1 : -1))
        .slice(0, 100);
      return ok(messagesResponse(conversation, found, json.include_users === true));
    }
    if (sub.length === 1 && sub[0] === "messages" && method === "GET") return this.getMessages(conversation, url);
    if (sub.length === 1 && sub[0] === "messages" && method === "POST") return this.sendMessage(conversation, json);
    if (sub.length === 2 && sub[0] === "messages" && sub[1] === "bulk") {
      for (const id of json.ids ?? []) network.post(conversation.id, ITEM.DELETE, "", { parent: id });
      return ok({});
    }
    if (sub.length === 2 && sub[0] === "messages") return this.messageRoute(method, conversation, sub[1], json);
    if (sub.length >= 3 && sub[0] === "messages" && sub[2] === "reactions") {
      if (sub.length === 4) {
        network.post(conversation.id, method === "PUT" ? ITEM.REACT : ITEM.UNREACT, decodeURIComponent(sub[3]), { parent: sub[1] });
      }
      return ok({});
    }
    if (sub.length === 3 && sub[0] === "messages" && sub[2] === "pin") return ok({});
    return error(404, "NotFound");
  }

  getMessages(conversation, url) {
    const q = url.searchParams;
    const limit = Math.min(100, Math.max(1, Number(q.get("limit")) || 50));
    const before = q.get("before");
    const after = q.get("after");
    const nearby = q.get("nearby");
    const oldestFirst = q.get("sort") === "Oldest";
    const includeUsers = q.get("include_users") === "true";
    let list = messageViews(conversation).sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    if (nearby) {
      const older = list.filter((m) => m.id <= nearby).slice(-(Math.floor(limit / 2) + 1));
      const newer = list.filter((m) => m.id > nearby).slice(0, Math.floor(limit / 2));
      list = [...older, ...newer];
    } else {
      if (before) list = list.filter((m) => m.id < before);
      if (after) list = list.filter((m) => m.id > after);
      list = oldestFirst || (after && !before) ? list.slice(0, limit) : list.slice(-limit);
    }
    const ordered = oldestFirst ? list : list.reverse();
    return ok(messagesResponse(conversation, ordered, includeUsers));
  }

  sendMessage(conversation, json) {
    const ids = (json.attachments ?? []).slice(0, 5);
    const files = ids.map((id) => network.fileMeta(id)).filter(Boolean);
    if (files.length !== ids.length) return error(400, MSG.UploadMissing);
    const replies = (json.replies ?? []).map((r) => (typeof r === "string" ? r : r?.id)).filter(Boolean);
    const item = network.post(conversation.id, ITEM.MESSAGE, String(json.content ?? ""), {
      tags: [...replies, ...files.map(fileMetaTag)],
    });
    if (!item) return error(401, "Unauthorized");
    const view = messageViews(conversation).find((m) => m.id === item.id);
    if (!view) return error(400, "InternalError");
    return ok({ ...messageJson(view), nonce: json.nonce ?? "" });
  }

  messageRoute(method, conversation, messageId, json) {
    if (method === "GET") {
      const v = messageViews(conversation).find((m) => m.id === messageId);
      return v ? ok(messageJson(v)) : error(404, "NotFound");
    }
    if (method === "PATCH") {
      network.post(conversation.id, ITEM.EDIT, String(json.content ?? ""), { parent: messageId });
      const v = messageViews(conversation).find((m) => m.id === messageId);
      return v ? ok(messageJson(v)) : error(404, "NotFound");
    }
    if (method === "DELETE") {
      network.post(conversation.id, ITEM.DELETE, "", { parent: messageId });
      return ok({});
    }
    return error(404, "NotFound");
  }

  async serverRoute(method, s, json) {
    const server = conversationOfServer(s[1]);
    if (!server) return error(404, "NotFound");
    const sub = s.slice(2);
    const me = network.selfKey;
    const perms = servers.permissions(server, me);
    const can = (bit) => (perms & bit) === bit;
    const denied = error(403, "MissingPermission");
    const fileOf = (id) => (id ? network.fileMeta(id) : null);

    if (!sub.length && method === "GET") return ok(serverJson(server));
    if (!sub.length && method === "PATCH") {
      if (!can(P.MANAGE_SERVER)) return denied;
      const remove = json.remove ?? [];
      const op = {};
      if ("name" in json) op.name = json.name;
      if ("description" in json) op.description = json.description;
      if ("icon" in json) op.icon = fileOf(json.icon) ? fileMetaToJson(fileOf(json.icon)) : null;
      if ("banner" in json) op.banner = fileOf(json.banner) ? fileMetaToJson(fileOf(json.banner)) : null;
      if (remove.includes("Icon")) op.icon = null;
      if (remove.includes("Banner")) op.banner = null;
      if (remove.includes("Description")) op.description = "";
      if (Object.keys(op).length) servers.op(server.id, "server", op);
      if ("name" in json) network.renameConversation(server.id, json.name);
      return ok(serverJson(network.conversations.get(server.id) ?? server));
    }
    if (!sub.length && method === "DELETE") {
      if (official.isOpen(server.id)) official.setHidden(true);
      else if (servers.ownerOf(server) === me) {
        if (server.link) servers.releaseLink(server.link);
        network.leaveConversation(server.id);
      } else {
        servers.op(server.id, "leave", { user: me });
        network.leaveConversation(server.id);
      }
      return ok({});
    }
    if (sub[0] === "ack") return ok({});
    if (sub.length === 1 && sub[0] === "channels" && method === "POST") {
      if (!can(P.MANAGE_CHANNEL)) return denied;
      const name = String(json.name || "").trim() || "salon";
      const voice = String(json.type || "").toLowerCase() === "voice";
      if (official.isOpen(server.id)) {
        const id = "channel:" + hex(randomBytes(12));
        servers.op(server.id, "channel.create", { id, name, voice });
        official.syncCreatedChannels();
        const ch = network.conversations.get(id);
        return ch ? ok(channelJson(ch)) : error(400, "InternalError");
      }
      const ch = network.createServerChannel(server.id, name, voice);
      return ch ? ok(channelJson(ch)) : denied;
    }
    if (sub.length === 1 && sub[0] === "members") {
      const members = serverMembers(server);
      return ok({ members: members.map((k) => memberJson(server, k)), users: members.map(userJson) });
    }
    if (sub.length === 2 && sub[0] === "members") {
      const key = publicKeyOf(sub[1]);
      if (!key) return error(404, "NotFound");
      if (method === "GET") return serverMembers(server).includes(key) ? ok(memberJson(server, key)) : error(404, "NotFound");
      if (method === "PATCH") {
        const remove = json.remove ?? [];
        const op = { user: key };
        if ("nickname" in json) op.nickname = json.nickname ?? "";
        if (remove.includes("Nickname")) op.nickname = "";
        if ("roles" in json) op.roles = json.roles ?? [];
        if (remove.includes("Roles")) op.roles = [];
        if ("timeout" in json) op.timeout = Date.parse(json.timeout) || 0;
        if (remove.includes("Timeout")) op.timeout = 0;
        servers.op(server.id, "member", op);
        return ok(memberJson(server, key));
      }
      if (method === "DELETE") {
        if (!can(P.KICK_MEMBERS)) return denied;
        servers.op(server.id, "kick", { user: key });
        return ok({});
      }
    }
    if (sub.length === 1 && sub[0] === "bans") {
      const bans = servers.state(server).bans;
      return ok({
        users: [...bans.keys()].map(userJson),
        bans: [...bans].map(([k, reason]) => ({ _id: { server: serverId(server.id), user: userId(k) }, ...(reason ? { reason } : {}) })),
      });
    }
    if (sub.length === 2 && sub[0] === "bans") {
      const key = publicKeyOf(sub[1]);
      if (!key) return error(404, "NotFound");
      if (!can(P.BAN_MEMBERS)) return denied;
      if (method === "PUT") {
        servers.op(server.id, "ban", { user: key, reason: json.reason ?? "" });
        return ok({ _id: { server: serverId(server.id), user: userId(key) } });
      }
      servers.op(server.id, "unban", { user: key });
      return ok({});
    }
    if (sub.length === 1 && sub[0] === "roles" && method === "POST") {
      if (!can(P.MANAGE_ROLE)) return denied;
      const id = ulid();
      servers.op(server.id, "role.set", { id, name: json.name || "role" });
      const role = servers.state(server).roles.get(id);
      return role ? ok({ id, role: roleJson(role) }) : error(400, "InternalError");
    }
    if (sub.length === 2 && sub[0] === "roles" && sub[1] === "ranks") {
      if (!can(P.MANAGE_ROLE)) return denied;
      servers.op(server.id, "role.ranks", { ranks: json.ranks ?? [] });
      return ok(serverJson(server));
    }
    if (sub.length === 2 && sub[0] === "roles") {
      const id = sub[1];
      if (method === "GET") {
        const r = servers.state(server).roles.get(id);
        return r ? ok(roleJson(r)) : error(404, "NotFound");
      }
      if (!can(P.MANAGE_ROLE)) return denied;
      if (method === "PATCH") {
        const op = { id };
        for (const k of ["name", "colour", "hoist", "rank"]) if (k in json) op[k] = json[k];
        if ((json.remove ?? []).includes("Colour")) op.colour = "";
        servers.op(server.id, "role.set", op);
        const r = servers.state(server).roles.get(id);
        return r ? ok(roleJson(r)) : error(404, "NotFound");
      }
      if (method === "DELETE") {
        servers.op(server.id, "role.delete", { id });
        return ok({});
      }
    }
    if (sub.length === 2 && sub[0] === "permissions") {
      if (!can(P.MANAGE_PERMISSIONS)) return denied;
      if (sub[1] === "default") {
        const v = typeof json.permissions === "object" ? json.permissions?.allow : json.permissions;
        servers.op(server.id, "perm.default", { value: Number(v) || 0 });
      } else {
        const p = json.permissions ?? {};
        servers.op(server.id, "perm.role", { role: sub[1], a: Number(p.allow) || 0, d: Number(p.deny) || 0 });
      }
      return ok(serverJson(server));
    }
    if (sub.length === 1 && sub[0] === "invites") {
      const link = server.link ?? (await network.ensureLink(server.id));
      if (!link) return ok([]);
      const ch = visibleChannels(server)[0];
      return ok([{ type: "Server", _id: link, server: serverId(server.id), creator: userId(servers.ownerOf(server) ?? ""), channel: ch ? channelId(ch.id) : "" }]);
    }
    if (sub.length === 1 && sub[0] === "emojis") {
      return ok([...servers.state(server).emojis.values()].map((e) => emojiJson(server, e)));
    }
    return ok({});
  }

  emojiRoute(method, id, json) {
    if (method === "GET") {
      const f = allEmojis().find(([, e]) => e.id === id);
      return f ? ok(emojiJson(f[0], f[1])) : error(404, "NotFound");
    }
    if (method === "PUT") {
      const server = json.parent?.id ? conversationOfServer(json.parent.id) : null;
      if (!server) return error(404, "NotFound");
      servers.op(server.id, "emoji.add", { id, name: json.name ?? "" });
      const e = servers.state(server).emojis.get(id);
      return e ? ok(emojiJson(server, e)) : error(403, "MissingPermission");
    }
    if (method === "DELETE") {
      const f = allEmojis().find(([, e]) => e.id === id);
      if (!f) return error(404, "NotFound");
      servers.op(f[0].id, "emoji.delete", { id });
      return ok({});
    }
    return error(404, "NotFound");
  }

  async inviteRoute(method, rawCode) {
    const code = decodeURIComponent(rawCode.trim()).split("/").pop();
    const invite = await resolveInvite(code);
    if (!invite) return error(404, "NotFound");
    if (method === "GET") {
      const local = network.conversations.get(invite.serverId);
      const icon = local ? servers.state(local).icon : null;
      return ok({
        type: "Server",
        code,
        server_id: serverId(invite.serverId),
        server_name: invite.name,
        ...(icon ? { server_icon: autumnJson(icon) } : {}),
        channel_id: local ? channelId(visibleChannels(local)[0]?.id ?? "") : "",
        channel_name: "général",
        user_name: invite.name,
        member_count: local ? serverMembers(local).length : 1,
      });
    }
    if (method === "POST") {
      const run = joinLock.then(async () => {
        if (invite.serverId === official.serverId) {
          official.setHidden(false);
          official.ensure();
        } else if (!network.conversations.get(invite.serverId) && invite.inviteKey) {
          network.requestJoin(invite);
          for (let i = 0; i < 60 && !network.conversations.get(invite.serverId); i++) await sleep(500);
        }
        const server = network.conversations.get(invite.serverId);
        if (!server) return error(404, MSG.OwnerOffline);
        for (let i = 0; i < 16 && !network.channelsOf(server.id).length; i++) await sleep(300);
        return ok({ type: "Server", server: serverJson(server), channels: visibleChannels(server).map(channelJson) });
      });
      joinLock = run.catch(() => {});
      return run;
    }
    return ok({});
  }

  // ---- calls (the call itself runs in the window, this only carries its signalling) ----

  callSockets = new Set();

  callSocket(ws, token) {
    if (!token.startsWith(TOKEN_PREFIX) || !this.ensureStarted()) return ws.close();
    this.callSockets.add(ws);
    network.callHandler = (from, payload) => {
      if (payload?.t === "call-ring") {
        const conv = network.conversations.get(String(payload.conv ?? ""));
        if (!conv || !conv.members.has(from)) return;
      }
      const msg = JSON.stringify({ kind: "signal", from, fromName: network.username(from), payload });
      for (const s of this.callSockets) if (s.readyState === 1) s.send(msg);
    };
    const others = (conv) => {
      const srv = servers.serverOf(conv.id);
      const keys = srv && conv.kind === KIND.CHANNEL ? serverMembers(srv) : [...conv.members];
      return keys.filter((k) => k && k !== network.selfKey);
    };
    ws.on("message", (data) => {
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return;
      }
      if (msg.kind === "start") {
        const conv = conversationOfChannel(String(msg.channelId ?? ""));
        const members = conv ? others(conv).map((key) => ({ key, name: network.username(key) })) : [];
        ws.send(JSON.stringify({ kind: "started", conversation: conv?.id ?? null, members }));
      } else if (msg.kind === "send" && typeof msg.to === "string" && msg.payload) {
        network.sendCallMessage(msg.to, msg.payload);
      } else if (msg.kind === "leave-all") {
        const conv = network.conversations.get(String(msg.conversation ?? ""));
        if (conv) for (const k of others(conv)) network.sendCallMessage(k, { t: "call-leave", cid: msg.cid, conv: conv.id });
      }
    });
    ws.on("close", () => this.callSockets.delete(ws));
  }

  // ---- realtime ----

  readyFrame() {
    const visible = [...network.conversations.values()].filter((c) => !(official.isOpen(c.id) && official.isHidden()));
    return {
      type: "Ready",
      users: [...knownKeys()].map(userJson),
      servers: visible.filter((c) => c.kind === KIND.SERVER).map((c) => serverJson(c)),
      channels: visible
        .filter((c) => c.kind !== KIND.SERVER)
        .filter((c) => {
          const srv = servers.serverOf(c.id);
          return !srv || servers.state(srv).channels.get(c.id)?.deleted !== true;
        })
        .map(channelJson),
      members: visible
        .filter((c) => c.kind === KIND.SERVER)
        .flatMap((srv) => serverMembers(srv).filter((k) => k === network.selfKey).map((k) => memberJson(srv, k))),
      emojis: allEmojis().map(([srv, e]) => emojiJson(srv, e)),
      voice_states: [],
    };
  }

  realtime(ws, token) {
    const send = (type, obj = {}) => {
      if (ws.readyState === 1) ws.send(JSON.stringify({ ...obj, type }, (k, v) => (typeof v === "bigint" ? Number(v) : v)));
    };
    if (!token.startsWith(TOKEN_PREFIX) || !this.ensureStarted()) {
      send("Error", { data: { type: "InvalidSession" } });
      return ws.close();
    }
    send("Authenticated");
    network.resolveNames(knownKeys());
    ws.send(JSON.stringify(this.readyFrame()));

    const knownUsers = new Set(knownKeys());
    const announceUsers = (keys) => {
      network.resolveNames(keys);
      for (const key of keys) {
        if (knownUsers.has(key)) continue;
        knownUsers.add(key);
        send("UserRelationship", { id: userId(network.selfKey ?? ""), user: userJson(key), status: network.contacts.has(key) ? "Friend" : "None" });
      }
    };
    const offs = [];

    offs.push(
      network.on("conversations", (before, current) => {
        announceUsers([...current.values()].flatMap((c) => [...c.members]));
        const ordered = [...current.values()].sort((a, b) => (a.kind === KIND.SERVER ? 0 : 1) - (b.kind === KIND.SERVER ? 0 : 1));
        for (const c of ordered) {
          const old = before.get(c.id);
          if (official.isOpen(c.id) && official.isHidden()) continue;
          if (!old && c.kind === KIND.SERVER) {
            send("ServerCreate", { id: serverId(c.id), server: serverJson(c), channels: network.channelsOf(c.id).map(channelJson), emojis: [] });
          } else if (!old && c.kind === KIND.CHANNEL && c.parent && !before.has(c.parent) && current.has(c.parent)) {
            // sent with ServerCreate
          } else if (!old) {
            send("ChannelCreate", channelJson(c));
          } else if (old !== c && c.kind === KIND.SERVER) {
            send("ServerUpdate", { id: serverId(c.id), data: serverJson(c) });
            for (const m of c.members) if (!old.members.has(m)) send("ServerMemberJoin", { id: serverId(c.id), user: userId(m), member: memberJson(c, m) });
          } else if (old !== c) {
            send("ChannelUpdate", { id: channelId(c.id), data: channelJson(c) });
          }
        }
        for (const [id, old] of before) {
          if (current.has(id)) continue;
          if (old.kind === KIND.SERVER) send("ServerDelete", { id: serverId(id) });
          else send("ChannelDelete", { id: channelId(id) });
        }
      }),
    );

    offs.push(
      network.on("items", (fresh) => {
        announceUsers(fresh.map((i) => i.author));
        for (const item of [...fresh].sort((a, b) => a.createdAt - b.createdAt)) {
          const conversation = network.conversations.get(item.conversation);
          if (!conversation) continue;
          const channel = channelId(conversation.id);
          switch (item.type) {
            case ITEM.MESSAGE: {
              const v = messageViews(conversation).find((m) => m.id === item.id);
              if (v) send("Message", messageJson(v));
              break;
            }
            case ITEM.EDIT: {
              const v = messageViews(conversation).find((m) => m.id === item.parent);
              if (v) send("MessageUpdate", { id: v.id, channel, data: { content: v.content, ...(v.edited ? { edited: iso(v.edited) } : {}) } });
              break;
            }
            case ITEM.DELETE:
              if (!messageViews(conversation).some((m) => m.id === item.parent)) send("MessageDelete", { id: item.parent, channel });
              break;
            case ITEM.REACT:
            case ITEM.UNREACT:
              send(item.type === ITEM.REACT ? "MessageReact" : "MessageUnreact", {
                id: item.parent,
                channel_id: channel,
                user_id: userId(item.author),
                emoji_id: item.text,
              });
              break;
            case ITEM.OP: {
              if (conversation.kind !== KIND.SERVER) break;
              const sid = serverId(conversation.id);
              let payload = {};
              try {
                payload = JSON.parse(item.text);
              } catch {}
              send("ServerUpdate", { id: sid, data: serverJson(conversation) });
              const key = typeof payload.user === "string" ? payload.user : "";
              if ((item.title === "member" || item.title === "readd") && key) {
                send("ServerMemberUpdate", { id: { server: sid, user: userId(key) }, data: memberJson(conversation, key) });
              } else if (["kick", "ban", "leave"].includes(item.title) && key) {
                if (key === network.selfKey && servers.state(conversation).removed.has(key)) send("ServerDelete", { id: sid });
                else send("ServerMemberLeave", { id: sid, user: userId(key) });
              } else if (item.title === "emoji.add") {
                const e = servers.state(conversation).emojis.get(payload.id);
                if (e) send("EmojiCreate", emojiJson(conversation, e));
              } else if (item.title === "emoji.delete") {
                send("EmojiDelete", { id: payload.id });
              } else if (item.title === "channel") {
                const ch = network.conversations.get(payload.id);
                if (ch) {
                  if (servers.state(conversation).channels.get(ch.id)?.deleted) send("ChannelDelete", { id: channelId(ch.id) });
                  else send("ChannelUpdate", { id: channelId(ch.id), data: channelJson(ch) });
                }
              }
              break;
            }
            case ITEM.JOIN:
              if (conversation.kind === KIND.SERVER) {
                send("ServerMemberJoin", { id: serverId(conversation.id), user: userId(item.author), member: memberJson(conversation, item.author) });
                const first = visibleChannels(conversation)[0];
                const v = first && messageViews(first).find((m) => m.id === item.id);
                if (v) send("Message", messageJson(v));
              }
              break;
          }
        }
      }),
    );

    offs.push(
      network.on("contacts", (before, current) => {
        for (const key of current.keys()) {
          if (before.has(key)) continue;
          knownUsers.add(key);
          send("UserRelationship", { id: userId(network.selfKey ?? ""), user: userJson(key), status: "Friend" });
        }
        for (const key of before.keys()) {
          if (!current.has(key)) send("UserRelationship", { id: userId(network.selfKey ?? ""), user: userJson(key), status: "None" });
        }
      }),
    );

    offs.push(
      network.on("names", (before, current) => {
        for (const [k, v] of current) if (before.get(k) !== v) send("UserUpdate", { id: userId(k), data: { username: network.username(k) } });
      }),
    );

    offs.push(
      network.on("bots", () => {
        for (const [k, owner] of network.botOwners) {
          send("UserUpdate", { id: userId(k), data: { bot: { owner: userId(owner) }, username: network.username(k) } });
        }
      }),
    );

    offs.push(
      account.on("verified", () => {
        for (const k of account.verifiedKeys) send("UserUpdate", { id: userId(k), data: { badges: VERIFIED_BADGE } });
      }),
    );

    offs.push(
      account.on("owner", () => {
        const srv = network.conversations.get(official.serverId);
        if (srv) send("ServerUpdate", { id: serverId(srv.id), data: serverJson(srv) });
      }),
    );

    const pushProfile = (key) => {
      const user = userJson(key);
      const data = {};
      const clear = [];
      if (user.avatar) data.avatar = user.avatar;
      else clear.push("Avatar");
      if (user.display_name) data.display_name = user.display_name;
      else clear.push("DisplayName");
      send("UserUpdate", { id: userId(key), data, ...(clear.length ? { clear } : {}) });
    };
    offs.push(
      network.on("profiles", (before, current) => {
        for (const [k, v] of current) if (before.get(k) !== v) pushProfile(k);
      }),
    );
    // an avatar file arriving later from a peer
    offs.push(network.on("files", () => {}));

    offs.push(
      official.on("hidden", (hidden) => {
        const srv = network.conversations.get(official.serverId);
        if (!srv) return;
        if (hidden) send("ServerDelete", { id: serverId(srv.id) });
        else send("ServerCreate", { id: serverId(srv.id), server: serverJson(srv), channels: visibleChannels(srv).map(channelJson), emojis: [] });
      }),
    );

    offs.push(
      network.on("online", (before, current) => {
        const changed = new Set([...[...current].filter((k) => !before.has(k)), ...[...before].filter((k) => !current.has(k))]);
        for (const key of changed) {
          const online = current.has(key);
          send("UserUpdate", { id: userId(key), data: { online, status: { presence: online ? "Online" : "Invisible" } } });
        }
      }),
    );

    ws.on("message", (data) => {
      try {
        const msg = JSON.parse(data.toString());
        if (msg.type === "Ping") send("Pong", { data: msg.data });
      } catch {}
    });
    ws.on("close", () => offs.forEach((off) => off()));
  }
}
