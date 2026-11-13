// AIR Chat bots without any server (same as AirBots.kt on the phone): a bot is an AIR Chat
// account hosted by the device of its creator, set up with chat commands, no code.
import { hex, randomBytes, privateKeyFromSeed, publicKey as pubOf, unhex } from "./crypto.js";
import { account, Vault } from "./account.js";
import { ITEM, KIND, network } from "./network.js";
import { servers } from "./servers.js";
import { official } from "./official.js";
import { Emitter } from "./emitter.js";
import { userId } from "./ids.js";

const RECENT_MS = 3 * 60_000;

export const HELP_OWNER = `Réglages (créateur du bot uniquement) :
• !commande nom réponse — crée une commande (!nom)
• !supprimer nom — supprime une commande
• !bienvenue texte — message quand quelqu'un rejoint ({user}) · !bienvenue off
• !auto mot = réponse — répond quand un message contient le mot
• !auto-supprimer mot
• !interdire mot / !autoriser mot — supprime les messages qui contiennent ce mot
• !prefixe ? — change le préfixe des commandes`;

const newToken = () => hex(randomBytes(24));
const defaultConfig = () => ({ prefix: "!", commands: {}, auto: {}, banned: [] });
const random = (list) => list[Math.floor(Math.random() * list.length)];

class Bots extends Emitter {
  bots = [];
  owner = null;
  vault = null;

  constructor() {
    super();
    network.on("started", () => this.start());
    network.on("stopped", () => this.stop());
    network.on("items", (items) => {
      // answer just after the message is shown, like a person would
      setTimeout(() => {
        try {
          this.handle(items);
        } catch (e) {
          console.warn("[air] bot failed", e);
        }
      }, 600);
    });
  }

  start() {
    const ownerKey = network.selfKey;
    if (!ownerKey || this.owner === ownerKey) return;
    this.owner = ownerKey;
    this.vault = new Vault(`bots3_${ownerKey.slice(0, 16)}.vault`);
    this.bots = this.load();
    for (const b of this.bots) network.rememberBot(b.publicKey, ownerKey, b.username);
    this.emit("changed");
  }

  stop() {
    this.owner = null;
    this.vault = null;
    this.bots = [];
  }

  make(username, privateKey, config) {
    return {
      username,
      privateKey,
      config: { ...defaultConfig(), ...config },
      publicKey: hex(pubOf(privateKey)),
      get session() {
        return { username: this.username, privateKey: this.privateKey, publicKey: this.publicKey };
      },
      get prefix() {
        return (this.config.prefix || "!").trim() || "!";
      },
    };
  }

  /** The bot a program connects as (token given in "Mes bots"). */
  byToken(token) {
    return token ? this.bots.find((b) => b.config.token && b.config.token === token) ?? null : null;
  }

  resetToken(key) {
    const bot = this.get(key);
    if (!bot) return;
    bot.config.token = newToken();
    this.save();
  }

  isMine(key) {
    return this.bots.some((b) => b.publicKey === key);
  }

  get(key) {
    return this.bots.find((b) => b.publicKey === key) ?? null;
  }

  async create(username) {
    if (!this.owner) return { error: "Connectez-vous d'abord." };
    const key = privateKeyFromSeed(randomBytes(32));
    const r = await account.registerBot(username, key, this.owner);
    if (r.error === "UsernameInvalid") return { error: "Nom invalide : 3 à 20 caractères (lettres minuscules, chiffres, . et _)." };
    if (r.error === "UsernameTaken") return { error: "Ce nom est déjà pris." };
    if (r.error) return { error: "Impossible de joindre le réseau. Réessayez." };
    const bot = this.make(r.session.username, key, { ...defaultConfig(), token: newToken() });
    this.bots.push(bot);
    this.save();
    network.rememberBot(bot.publicKey, this.owner, bot.username);
    this.emit("changed");
    return { bot };
  }

  delete(key) {
    this.bots = this.bots.filter((b) => b.publicKey !== key);
    this.save();
    this.emit("changed");
  }

  addToServer(key, serverId) {
    const bot = this.get(key);
    const server = network.conversations.get(serverId);
    if (!bot || !server || server.kind !== KIND.SERVER || official.isOpen(serverId)) return false;
    const state = servers.state(server);
    if (state.bans.has(key)) return false;
    if (state.removed.has(key)) network.post(serverId, ITEM.OP, JSON.stringify({ user: key }), { title: "readd" });
    if (!network.addMembers(serverId, [key])) return false;
    network.post(serverId, ITEM.JOIN, "", { author: bot.session });
    return true;
  }

  addToGroup(key, groupId) {
    const group = network.conversations.get(groupId);
    if (!group || group.kind !== KIND.GROUP) return false;
    return !!network.addMembers(groupId, [key]);
  }

  // ---- what bots do ----

  handle(items) {
    if (!this.bots.length) return;
    const now = Date.now();
    for (const item of [...items].sort((a, b) => a.createdAt - b.createdAt)) {
      if (this.isMine(item.author) || now - item.createdAt > RECENT_MS) continue;
      const conversation = network.conversations.get(item.conversation);
      if (!conversation || official.isOpen(conversation.id)) continue;
      for (const bot of this.bots) {
        if (!conversation.members.has(bot.publicKey)) continue;
        if (item.type === ITEM.JOIN) this.welcome(bot, conversation, item);
        else if (item.type === ITEM.MESSAGE) this.onMessage(bot, conversation, item);
      }
    }
  }

  mention(key) {
    return `<@${userId(key)}>`;
  }

  reply(bot, conversationId, to, text) {
    network.post(conversationId, ITEM.MESSAGE, String(text).slice(0, 4000), { tags: to ? [to.id] : [], author: bot.session });
  }

  welcome(bot, server, join) {
    if (server.kind !== KIND.SERVER || !bot.config.welcome) return;
    const channel = network.channelsOf(server.id)[0];
    if (!channel) return;
    this.reply(bot, channel.id, null, bot.config.welcome.replaceAll("{user}", this.mention(join.author)).replaceAll("{serveur}", server.name));
  }

  fill(text, item) {
    return String(text).replaceAll("{user}", this.mention(item.author));
  }

  onMessage(bot, conversation, item) {
    const text = item.text.trim();
    const lower = text.toLowerCase();
    const config = bot.config;
    if ((config.banned ?? []).some((w) => w && lower.includes(w.toLowerCase()))) {
      network.post(conversation.id, ITEM.DELETE, "", { parent: item.id, author: bot.session });
      this.reply(bot, conversation.id, null, `${this.mention(item.author)} ce message contenait un mot interdit et a été supprimé.`);
      return;
    }
    if (text.startsWith(bot.prefix)) {
      const body = text.slice(bot.prefix.length).trim();
      const space = body.indexOf(" ");
      const name = (space < 0 ? body : body.slice(0, space)).toLowerCase();
      const args = space < 0 ? "" : body.slice(space + 1).trim();
      this.command(bot, conversation, item, name, args);
      return;
    }
    const keyword = Object.keys(config.auto ?? {}).find((k) => k && lower.includes(k.toLowerCase()));
    if (keyword) this.reply(bot, conversation.id, item, this.fill(config.auto[keyword], item));
  }

  command(bot, conversation, item, name, args) {
    const p = bot.prefix;
    const isOwner = item.author === this.owner;
    const config = bot.config;
    config.commands ??= {};
    const ownerOnly = (change) => {
      if (!isOwner) return this.reply(bot, conversation.id, item, "Seul le créateur du bot peut faire ça.");
      const answer = change();
      this.save();
      this.reply(bot, conversation.id, item, answer);
    };
    switch (name) {
      case "aide":
      case "help": {
        const custom = Object.keys(config.commands).sort().map((c) => p + c).join(", ");
        let text = `**${bot.username}** — bot AIR Chat\nCommandes : ${p}aide, ${p}ping, ${p}info, ${p}des, ${p}choisir a | b`;
        if (custom) text += `, ${custom}`;
        if (isOwner) text += "\n\n" + HELP_OWNER.replaceAll("!", p);
        return this.reply(bot, conversation.id, item, text);
      }
      case "ping":
        return this.reply(bot, conversation.id, item, "pong 🏓");
      case "info":
        return this.reply(
          bot,
          conversation.id,
          item,
          `Je suis **${bot.username}**, un bot AIR Chat hébergé par ${this.mention(this.owner ?? "")}. Je réponds quand son appareil a AIR Chat ouvert.`,
        );
      case "des":
      case "dé":
      case "dice":
        return this.reply(bot, conversation.id, item, `🎲 ${1 + Math.floor(Math.random() * 6)}`);
      case "choisir":
      case "choose": {
        const options = args.split("|").map((o) => o.trim()).filter(Boolean);
        return this.reply(bot, conversation.id, item, options.length ? `Je choisis : **${random(options)}**` : `Exemple : ${p}choisir pizza | burger`);
      }
      case "commande":
      case "ajouter":
        return ownerOnly(() => {
          const space = args.indexOf(" ");
          const cmd = (space < 0 ? args : args.slice(0, space)).toLowerCase().replace(p, "");
          const response = space < 0 ? "" : args.slice(space + 1).trim();
          if (!cmd || !response) return `Exemple : ${p}commande regles Soyez gentils !`;
          config.commands[cmd] = response;
          return `Commande ${p}${cmd} enregistrée.`;
        });
      case "supprimer":
        return ownerOnly(() => {
          const cmd = args.toLowerCase().replace(p, "");
          if (!(cmd in config.commands)) return `La commande ${p}${cmd} n'existe pas.`;
          delete config.commands[cmd];
          return `Commande ${p}${cmd} supprimée.`;
        });
      case "bienvenue":
        return ownerOnly(() => {
          if (!args || args.toLowerCase() === "off") {
            delete config.welcome;
            return "Message de bienvenue désactivé.";
          }
          config.welcome = args;
          return "Message de bienvenue enregistré ({user} = le nouveau membre).";
        });
      case "auto":
        return ownerOnly(() => {
          const [keyword, ...rest] = args.split("=");
          const response = rest.join("=").trim();
          if (!keyword?.trim() || !response) return `Exemple : ${p}auto bonjour = Salut {user} !`;
          config.auto ??= {};
          config.auto[keyword.trim().toLowerCase()] = response;
          return `Réponse automatique enregistrée pour « ${keyword.trim()} ».`;
        });
      case "auto-supprimer":
        return ownerOnly(() => {
          delete config.auto?.[args.toLowerCase()];
          return `Réponse automatique supprimée pour « ${args} ».`;
        });
      case "interdire":
        return ownerOnly(() => {
          if (!args) return `Exemple : ${p}interdire gros-mot`;
          config.banned ??= [];
          if (!config.banned.includes(args.toLowerCase())) config.banned.push(args.toLowerCase());
          return `Le mot « ${args} » est maintenant interdit (donnez au bot la permission de gérer les messages).`;
        });
      case "autoriser":
        return ownerOnly(() => {
          config.banned = (config.banned ?? []).filter((w) => w !== args.toLowerCase());
          return `Le mot « ${args} » est de nouveau autorisé.`;
        });
      case "prefixe":
      case "préfixe":
        return ownerOnly(() => {
          if (!args || args.length > 3 || args.includes(" ")) return `Exemple : ${p}prefixe ?`;
          config.prefix = args;
          return `Nouveau préfixe : ${args}`;
        });
      default:
        if (config.commands[name]) this.reply(bot, conversation.id, item, this.fill(config.commands[name], item));
    }
  }

  load() {
    const raw = this.vault?.read();
    if (!raw) return [];
    try {
      const list = (JSON.parse(raw).bots ?? []).map((b) => this.make(b.username, unhex(b.key), b.config ?? {}));
      // bots made before code bots existed get their token now
      let changed = false;
      for (const b of list) if (!b.config.token) (b.config.token = newToken()), (changed = true);
      if (changed) setTimeout(() => this.save(), 0);
      return list;
    } catch {
      return [];
    }
  }

  save() {
    this.vault?.write(JSON.stringify({ bots: this.bots.map((b) => ({ username: b.username, key: hex(b.privateKey), config: b.config })) }));
  }
}

export const bots = new Bots();
