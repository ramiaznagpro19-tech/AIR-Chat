// Server administration without a server: port of AirServers.kt.
import crypto from "node:crypto";
import { account } from "./account.js";
import { createEvent, firstTag, relays } from "./nostr.js";
import { fileMetaFromJson, ITEM, KIND, network } from "./network.js";
import { official } from "./official.js";

const B = (n) => 1n << BigInt(n);
export const P = {
  MANAGE_CHANNEL: B(0),
  MANAGE_SERVER: B(1),
  MANAGE_PERMISSIONS: B(2),
  MANAGE_ROLE: B(3),
  MANAGE_CUSTOMISATION: B(4),
  KICK_MEMBERS: B(6),
  BAN_MEMBERS: B(7),
  TIMEOUT_MEMBERS: B(8),
  ASSIGN_ROLES: B(9),
  CHANGE_NICKNAME: B(10),
  MANAGE_NICKNAMES: B(11),
  CHANGE_AVATAR: B(12),
  VIEW_CHANNEL: B(20),
  READ_HISTORY: B(21),
  SEND_MESSAGE: B(22),
  MANAGE_MESSAGES: B(23),
  INVITE_OTHERS: B(25),
  SEND_EMBEDS: B(26),
  UPLOAD_FILES: B(27),
  REACT: B(29),
  CONNECT: B(30),
  SPEAK: B(31),
  VIDEO: B(32),
  LISTEN: B(36),
  MENTION_EVERYONE: B(37),
};
export const ALL = B(40) - 1n;
export const DEFAULT_PERMISSIONS =
  P.VIEW_CHANNEL | P.READ_HISTORY | P.SEND_MESSAGE | P.INVITE_OTHERS | P.SEND_EMBEDS | P.UPLOAD_FILES | P.REACT |
  P.CONNECT | P.SPEAK | P.VIDEO | P.LISTEN | P.CHANGE_NICKNAME | P.CHANGE_AVATAR;

const toBig = (v) => {
  try {
    return BigInt(Math.trunc(Number(v) || 0));
  } catch {
    return 0n;
  }
};
const take = (s, n) => String(s ?? "").slice(0, n);
const has = (o, k) => o != null && Object.prototype.hasOwnProperty.call(o, k);

export const LINK_KIND = 30422;
export const LINK_PREFIX = "air.chat.";
export const DISCOVER_KIND = 30424;
export const DISCOVER_MIN_MEMBERS = 1_000_000;

class Servers {
  cache = new Map();

  serverOf(conversationId) {
    const c = network.conversations.get(conversationId);
    if (!c) return null;
    if (c.kind === KIND.SERVER) return c;
    return c.parent ? network.conversations.get(c.parent) ?? null : null;
  }

  isBlocked(conversationId, key) {
    const s = this.serverOf(conversationId);
    return s ? this.state(s).removed.has(key) : false;
  }

  ownerOf(server) {
    return official.isOpen(server.id) ? account.ownerKey || null : server.owner;
  }

  state(server) {
    const items = [...network.items.values()].filter(
      (i) => i.conversation === server.id && (i.type === ITEM.OP || i.type === ITEM.JOIN),
    );
    const version = `${items.length}|${server.name}|${server.owner}|${account.ownerKey}|${server.members.size}`;
    const cached = this.cache.get(server.id);
    if (cached && cached.version === version) return cached.state;
    const state = this.fold(server, items);
    this.cache.set(server.id, { version, state });
    return state;
  }

  permissions(server, key, state = this.state(server)) {
    return this.permissionsWith(server, key, state.defaultPermissions, state.roles, state.memberRoles, state.timeouts);
  }

  permissionsWith(server, key, def, roles, memberRoles, timeouts) {
    if (key === this.ownerOf(server)) return ALL;
    let perms = def;
    const list = (memberRoles.get(key) || []).map((r) => roles.get(r)).filter(Boolean).sort((a, b) => b.rank - a.rank);
    for (const role of list) perms = (perms | role.allow) & ~role.deny;
    if ((timeouts.get(key) ?? 0) > Date.now()) perms &= ~(P.SEND_MESSAGE | P.REACT | P.SPEAK | P.VIDEO);
    return perms & ALL;
  }

  fold(server, items) {
    let name = server.name;
    let description = null;
    let icon = null;
    let banner = null;
    let defaultPermissions = DEFAULT_PERMISSIONS;
    const roles = new Map();
    const memberRoles = new Map();
    const nicknames = new Map();
    const timeouts = new Map();
    const removed = new Set();
    const bans = new Map();
    const emojis = new Map();
    const channels = new Map();
    const createdChannels = [];
    const voiceChannels = new Set();
    const joinedAt = new Map();
    let listing = null;
    const owner = this.ownerOf(server);

    const sorted = [...items].sort((a, b) => a.createdAt - b.createdAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
    for (const item of sorted) {
      if (item.type === ITEM.JOIN) {
        if (!joinedAt.has(item.author)) joinedAt.set(item.author, item.createdAt);
        continue;
      }
      let payload;
      try {
        payload = JSON.parse(item.text);
      } catch {
        continue;
      }
      if (!payload || typeof payload !== "object") continue;
      const author = item.author;
      if (removed.has(author) && author !== owner) continue;
      const perms = this.permissionsWith(server, author, defaultPermissions, roles, memberRoles, timeouts);
      const can = (bit) => (perms & bit) === bit;
      const target = typeof payload.user === "string" ? payload.user : "";
      const targetIsOwner = target !== "" && target === owner;

      switch (item.title) {
        case "server":
          if (can(P.MANAGE_SERVER)) {
            if (has(payload, "name")) name = take(payload.name, 64).trim() ? take(payload.name, 64) : name;
            if (has(payload, "description")) description = take(payload.description, 1024).trim() ? take(payload.description, 1024) : null;
            if (has(payload, "icon")) icon = this.meta(payload.icon);
            if (has(payload, "banner")) banner = this.meta(payload.banner);
          }
          break;
        case "role.set":
          if (can(P.MANAGE_ROLE)) {
            const id = String(payload.id || "").trim();
            if (!id) break;
            const old = roles.get(id);
            roles.set(id, {
              id,
              name: take(has(payload, "name") ? payload.name : old?.name ?? "role", 32),
              colour: has(payload, "colour") ? (String(payload.colour ?? "").trim() ? String(payload.colour) : null) : old?.colour ?? null,
              hoist: has(payload, "hoist") ? !!payload.hoist : old?.hoist ?? false,
              rank: has(payload, "rank") ? Number(payload.rank) : old?.rank ?? roles.size,
              allow: old?.allow ?? 0n,
              deny: old?.deny ?? 0n,
            });
          }
          break;
        case "role.delete":
          if (can(P.MANAGE_ROLE)) roles.delete(String(payload.id));
          break;
        case "role.ranks":
          if (can(P.MANAGE_ROLE)) {
            (Array.isArray(payload.ranks) ? payload.ranks : []).forEach((r, i) => {
              const role = roles.get(String(r));
              if (role) roles.set(role.id, { ...role, rank: i });
            });
          }
          break;
        case "perm.role":
          if (can(P.MANAGE_PERMISSIONS)) {
            const role = roles.get(String(payload.role));
            if (role) roles.set(role.id, { ...role, allow: toBig(payload.a) & ALL, deny: toBig(payload.d) & ALL });
          }
          break;
        case "perm.default":
          if (can(P.MANAGE_PERMISSIONS)) defaultPermissions = toBig(payload.value) & ALL;
          break;
        case "member":
          if (target && !targetIsOwner) {
            if (has(payload, "roles") && can(P.ASSIGN_ROLES)) {
              memberRoles.set(target, (Array.isArray(payload.roles) ? payload.roles : []).map(String).filter((r) => roles.has(r)));
            }
            if (has(payload, "nickname") && ((target === author && can(P.CHANGE_NICKNAME)) || can(P.MANAGE_NICKNAMES))) {
              const nick = take(payload.nickname, 32);
              if (!nick.trim()) nicknames.delete(target);
              else nicknames.set(target, nick);
            }
            if (has(payload, "timeout") && can(P.TIMEOUT_MEMBERS)) {
              const until = Number(payload.timeout) || 0;
              if (until <= 0) timeouts.delete(target);
              else timeouts.set(target, until);
            }
          }
          break;
        case "kick":
          if (!targetIsOwner && can(P.KICK_MEMBERS)) removed.add(target);
          break;
        case "ban":
          if (!targetIsOwner && can(P.BAN_MEMBERS)) {
            removed.add(target);
            bans.set(target, String(payload.reason ?? "").trim() ? String(payload.reason) : null);
          }
          break;
        case "unban":
          if (can(P.BAN_MEMBERS)) bans.delete(target);
          break;
        case "readd":
          if (!bans.has(target)) removed.delete(target);
          break;
        case "leave":
          if (target === author && !targetIsOwner) removed.add(target);
          break;
        case "emoji.add":
          if (can(P.MANAGE_CUSTOMISATION)) {
            const id = String(payload.id || "");
            if (id.trim()) emojis.set(id, { id, name: take(payload.name, 32), creator: author });
          }
          break;
        case "emoji.delete":
          if (can(P.MANAGE_CUSTOMISATION)) emojis.delete(String(payload.id));
          break;
        case "channel.create":
          if (can(P.MANAGE_CHANNEL)) {
            const id = String(payload.id || "");
            if (id.trim()) {
              createdChannels.push([id, take(payload.name, 64).trim() ? take(payload.name, 64) : "salon"]);
              if (payload.voice === true) voiceChannels.add(id);
            }
          }
          break;
        case "channel": {
          const id = String(payload.id || "").trim();
          if (!id) break;
          let meta = { ...(channels.get(id) ?? { name: null, description: null, deleted: false, roleOverrides: new Map(), defaultOverride: null }) };
          if (can(P.MANAGE_CHANNEL)) {
            if (has(payload, "name")) meta.name = take(payload.name, 64).trim() ? take(payload.name, 64) : null;
            if (has(payload, "description")) meta.description = take(payload.description, 1024).trim() ? take(payload.description, 1024) : null;
            if (payload.deleted === true) meta.deleted = true;
          }
          if (can(P.MANAGE_PERMISSIONS)) {
            if (payload.roleOverride && typeof payload.roleOverride === "object") {
              meta.roleOverrides = new Map(meta.roleOverrides).set(String(payload.roleOverride.role ?? ""), [
                toBig(payload.roleOverride.a),
                toBig(payload.roleOverride.d),
              ]);
            }
            if (payload.defaultOverride && typeof payload.defaultOverride === "object") {
              meta.defaultOverride = [toBig(payload.defaultOverride.a), toBig(payload.defaultOverride.d)];
            }
          }
          channels.set(id, meta);
          break;
        }
        case "listing":
          if (can(P.MANAGE_SERVER)) {
            listing = {
              description: take(payload.description, 512),
              icon: payload.icon || null,
              link: payload.link || null,
              members: Number(payload.members) || 0,
              at: item.createdAt,
            };
          }
          break;
      }
    }
    return {
      name,
      description,
      icon,
      banner,
      defaultPermissions,
      roles,
      memberRoles,
      nicknames,
      timeouts,
      removed,
      bans,
      emojis,
      channels,
      createdChannels,
      voiceChannels,
      joinedAt,
      listing,
    };
  }

  meta(v) {
    if (!v || typeof v !== "object") return null;
    try {
      return fileMetaFromJson(v);
    } catch {
      return null;
    }
  }

  op(serverId, kind, payload) {
    return network.post(serverId, ITEM.OP, JSON.stringify(payload), { title: kind });
  }

  ensureJoinItem(serverId) {
    const me = network.selfKey;
    const server = network.conversations.get(serverId);
    if (!me || !server || server.kind !== KIND.SERVER) return;
    if (!server.members.has(me) && !official.isOpen(serverId)) return;
    for (const i of network.items.values()) {
      if (i.conversation === serverId && i.type === ITEM.JOIN && i.author === me) return;
    }
    network.post(serverId, ITEM.JOIN, "");
  }

  // ---- links ----

  isLink(code) {
    return code.toLowerCase().startsWith(LINK_PREFIX) || code === official.LINK;
  }

  randomLink() {
    const chars = [];
    for (let i = 0; i < 4; i++) chars.push(String.fromCharCode(97 + crypto.randomInt(26)));
    for (let i = 0; i < 3; i++) chars.push(String.fromCharCode(48 + crypto.randomInt(10)));
    chars.push(String.fromCharCode(65 + crypto.randomInt(26)));
    for (let i = chars.length - 1; i > 0; i--) {
      const j = crypto.randomInt(i + 1);
      [chars[i], chars[j]] = [chars[j], chars[i]];
    }
    return LINK_PREFIX + chars.join("");
  }

  linkTag(link) {
    return `air-chat:server-link:${link}`;
  }

  async resolveLink(link) {
    const tag = this.linkTag(link);
    const events = await relays.query([
      { kinds: [LINK_KIND], "#d": [tag] },
      { kinds: [5], "#d": [tag] },
    ]);
    const released = events.filter((e) => e.kind === 5).map((e) => [e.pubkey, e.created_at]);
    const claims = events
      .filter((e) => e.kind === LINK_KIND && firstTag(e, "d") === tag)
      .filter((c) => !released.some(([k, at]) => k === c.pubkey && at >= c.created_at))
      .sort((a, b) => a.created_at - b.created_at || (a.id < b.id ? -1 : 1));
    const e = claims[0];
    if (!e) return null;
    try {
      const j = JSON.parse(e.content);
      if (typeof j.serverId !== "string" || typeof j.inviteKey !== "string") return null;
      return { owner: e.pubkey, serverId: j.serverId, inviteKey: j.inviteKey, name: j.name ?? "" };
    } catch {
      return null;
    }
  }

  async claimLink(server, inviteKey) {
    const s = account.session;
    if (!s) return null;
    for (let attempt = 0; attempt < 5; attempt++) {
      const link = this.randomLink();
      if (await this.resolveLink(link)) continue;
      const claim = createEvent(
        s.privateKey,
        LINK_KIND,
        [
          ["d", this.linkTag(link)],
          ["t", "air-chat-server-link"],
        ],
        JSON.stringify({ serverId: server.id, inviteKey, name: server.name }),
      );
      if (!(await relays.publish(claim))) return null;
      const holder = await this.resolveLink(link);
      if (holder?.serverId === server.id) return link;
    }
    return null;
  }

  releaseLink(link) {
    const s = account.session;
    if (!s) return;
    relays.send(
      createEvent(
        s.privateKey,
        5,
        [
          ["d", this.linkTag(link)],
          ["k", String(LINK_KIND)],
        ],
        "server deleted",
      ),
    );
  }

  // ---- Discover AIR Chat ----

  async publishListing(server, description, iconBase64) {
    const s = account.session;
    if (!s || !server.link) return false;
    const members = server.members.size - this.state(server).removed.size;
    if (members < DISCOVER_MIN_MEMBERS) return false;
    const event = createEvent(
      s.privateKey,
      DISCOVER_KIND,
      [
        ["d", `air-chat:discover:${server.id}`],
        ["t", "air-chat-discover"],
      ],
      JSON.stringify({ serverId: server.id, name: server.name, description: take(description, 512), icon: iconBase64 ?? "", link: server.link, members }),
    );
    this.op(server.id, "listing", { description, link: server.link, members });
    return relays.publish(event);
  }

  async fetchListings() {
    const events = await relays.query([{ kinds: [DISCOVER_KIND], "#t": ["air-chat-discover"], limit: 200 }]);
    const out = [];
    for (const e of events) {
      try {
        const j = JSON.parse(e.content);
        const claim = await this.resolveLink(j.link);
        if (claim?.owner !== e.pubkey && j.link !== official.LINK) continue;
        if ((Number(j.members) || 0) < DISCOVER_MIN_MEMBERS) continue;
        if (out.some((o) => o.serverId === j.serverId)) continue;
        out.push({
          serverId: j.serverId,
          name: take(j.name, 64),
          description: take(j.description, 512),
          icon: j.icon || null,
          link: j.link,
          members: Number(j.members),
        });
      } catch {}
    }
    return out.sort((a, b) => b.members - a.members);
  }
}

export const servers = new Servers();
