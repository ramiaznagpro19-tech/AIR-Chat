// The official AIR Chat server "AIR.CHAT.2026!" (port of AirOfficial.kt).
import { hex, sha256 } from "./crypto.js";
import { account } from "./account.js";
import { ITEM, KIND, makeConversation, network, sameConversation } from "./network.js";
import { servers } from "./servers.js";
import { Emitter } from "./emitter.js";

const LINK = "AIR.CHAT.2026!";
const HIDDEN_SETTING = "air:official_hidden";

class Official extends Emitter {
  LINK = LINK;
  NAME = "AIR Chat";
  serverId = "server:" + hex(sha256(`${LINK}#v3`)).slice(0, 24);
  generalChannelId = "channel:" + hex(sha256(`${LINK}#v3#general`)).slice(0, 24);

  isOpen(id) {
    if (id === this.serverId) return true;
    const c = network.conversations.get(id);
    if (!c) return id === this.generalChannelId;
    return c.parent === this.serverId;
  }

  openConversationIds() {
    return [...network.conversations.values()].filter((c) => this.isOpen(c.id)).map((c) => c.id);
  }

  isHidden() {
    return network.getSetting(HIDDEN_SETTING)?.[1] === "true";
  }

  setHidden(hidden) {
    network.setSetting(HIDDEN_SETTING, Date.now(), String(hidden));
    this.emit("hidden", hidden);
  }

  ensure() {
    const me = network.selfKey;
    if (!me) return;
    const owner = account.ownerKey || null;
    const existing = network.conversations.get(this.serverId);
    const server = makeConversation({
      id: this.serverId,
      isGroup: true,
      name: this.NAME,
      members: [...(existing?.members ?? []), me, ...(owner ? [owner] : [])],
      createdAt: existing?.createdAt ?? 1767225600000,
      kind: KIND.SERVER,
      owner,
      link: LINK,
    });
    if (!sameConversation(existing, server)) network.upsertConversation(server);
    if (!network.conversations.get(this.generalChannelId)) {
      network.upsertConversation(
        makeConversation({
          id: this.generalChannelId,
          isGroup: true,
          name: "général",
          members: [...server.members],
          createdAt: server.createdAt + 1,
          kind: KIND.CHANNEL,
          parent: this.serverId,
          owner,
        }),
      );
    }
    this.syncCreatedChannels();
    servers.ensureJoinItem(this.serverId);
  }

  syncCreatedChannels() {
    const server = network.conversations.get(this.serverId);
    if (!server) return;
    const state = servers.state(server);
    for (const [id, name] of state.createdChannels) {
      if (!id.startsWith("channel:") || network.conversations.has(id)) continue;
      network.upsertConversation(
        makeConversation({
          id,
          isGroup: true,
          name,
          members: [...server.members],
          createdAt: Date.now(),
          kind: KIND.CHANNEL,
          parent: this.serverId,
          owner: server.owner,
          voice: state.voiceChannels.has(id),
        }),
      );
    }
  }

  onItems(items) {
    const open = items.filter((i) => this.isOpen(i.conversation));
    if (!open.length) return;
    const server = network.conversations.get(this.serverId);
    if (!server) return;
    const fresh = open.map((i) => i.author).filter((a) => !server.members.has(a));
    if (fresh.length) network.upsertConversation({ ...server, members: new Set([...server.members, ...fresh]) });
    if (open.some((i) => i.type === ITEM.OP)) this.syncCreatedChannels();
  }
}

export const official = new Official();
