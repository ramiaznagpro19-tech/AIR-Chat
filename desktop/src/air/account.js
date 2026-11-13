// AIR Chat accounts: username + password, same directory and key derivation as the Android app.
import fs from "node:fs";
import path from "node:path";
import { b64, hex, open as openSealed, pbkdf2Seed, privateKeyFromSeed, publicKey, randomBytes, seal, unb64, unhex } from "./crypto.js";
import { createEvent, firstTag, relays } from "./nostr.js";
import { Emitter } from "./emitter.js";

export const USERNAME_KIND = 30420;
/** The account key encrypted with the current password, after a password change. */
export const KEYSTORE_KIND = 30421;

/** Encrypted file storage on this computer. */
export class Vault {
  static dir = path.join(process.cwd(), "air-data");
  static protect = (text) => Buffer.from(text, "utf8");
  static unprotect = (buf) => buf.toString("utf8");

  constructor(name) {
    this.file = path.join(Vault.dir, name);
  }

  read() {
    try {
      return Vault.unprotect(fs.readFileSync(this.file));
    } catch {
      return null;
    }
  }

  write(text) {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = this.file + ".tmp";
    fs.writeFileSync(tmp, Vault.protect(text));
    fs.renameSync(tmp, this.file);
  }
}

const VERIFIED_USERNAMES = ["rami"];

class Account extends Emitter {
  USERNAME_TOPIC = "air-chat-username-v3";
  ownerKey = "";
  session = null;
  verifiedKeys = new Set();

  install() {
    this.vault = new Vault("account3.vault");
    try {
      const owner = fs.readFileSync(path.join(Vault.dir, "owner3.txt"), "utf8").trim();
      if (owner.length === 64) this.ownerKey = owner;
    } catch {}
    const raw = this.vault.read();
    if (raw) {
      try {
        const json = JSON.parse(raw);
        if (json.username && json.key) this.setSession(json.username, unhex(json.key));
      } catch {}
    }
  }

  useTestDirectory() {
    this.USERNAME_TOPIC = "air-chat-username-test";
    this.ownerKey = "";
  }

  setSession(username, privateKey) {
    this.session = { username, privateKey, publicKey: hex(publicKey(privateKey)) };
    return this.session;
  }

  normalize(u) {
    return String(u || "").trim().toLowerCase();
  }

  isUsernameValid(u) {
    return /^[a-z0-9._]{3,20}$/.test(this.normalize(u));
  }

  passwordProblems(p) {
    const chars = [...String(p || "")];
    const letters = chars.filter((c) => /\p{L}/u.test(c)).length >= 4;
    const digits = chars.filter((c) => /\p{Nd}/u.test(c)).length >= 3;
    const specials = chars.filter((c) => !/[\p{L}\p{Nd}\s]/u.test(c)).length >= 2;
    return { letters, digits, specials, isValid: letters && digits && specials };
  }

  usernameTag(u) {
    return `${this.USERNAME_TOPIC}:${this.normalize(u)}`;
  }

  async deriveKey(username, password) {
    return privateKeyFromSeed(await pbkdf2Seed(password, `AIR Chat account|${this.normalize(username)}`));
  }

  async ownerOf(username) {
    const tag = this.usernameTag(username);
    const events = await relays.query([{ kinds: [USERNAME_KIND], "#d": [tag] }]);
    const valid = events.filter((e) => e.kind === USERNAME_KIND && firstTag(e, "d") === tag);
    valid.sort((a, b) => a.created_at - b.created_at || (a.id < b.id ? -1 : 1));
    return valid[0]?.pubkey ?? null;
  }

  async resolveOwner() {
    if (this.ownerKey) return this.ownerKey;
    const events = await relays.query([{ kinds: [USERNAME_KIND], "#t": [this.USERNAME_TOPIC], limit: 5000 }], 8_000);
    const valid = events.filter((e) => e.kind === USERNAME_KIND && firstTag(e, "t") === this.USERNAME_TOPIC);
    valid.sort((a, b) => a.created_at - b.created_at || (a.id < b.id ? -1 : 1));
    if (!valid[0]) return null;
    this.ownerKey = valid[0].pubkey;
    try {
      fs.mkdirSync(Vault.dir, { recursive: true });
      fs.writeFileSync(path.join(Vault.dir, "owner3.txt"), this.ownerKey);
    } catch {}
    this.emit("owner");
    return this.ownerKey;
  }

  async resolveVerified() {
    const keys = [];
    for (const name of VERIFIED_USERNAMES) {
      try {
        const k = await this.ownerOf(name);
        if (k) keys.push(k);
      } catch {}
    }
    if (keys.length) {
      keys.forEach((k) => this.verifiedKeys.add(k));
      this.emit("verified");
    }
  }

  isVerified(key) {
    return this.verifiedKeys.has(key);
  }

  async register(username, password) {
    const name = this.normalize(username);
    if (!this.isUsernameValid(name)) return { error: "UsernameInvalid" };
    if (!this.passwordProblems(password).isValid) return { error: "PasswordInvalid" };
    let owner;
    try {
      owner = await this.ownerOf(name);
    } catch {
      return { error: "NetworkError" };
    }
    if (relays.connectedCount === 0) return { error: "NetworkError" };
    if (owner) return { error: "UsernameTaken" };
    const key = await this.deriveKey(name, password);
    const claim = createEvent(
      key,
      USERNAME_KIND,
      [
        ["d", this.usernameTag(name)],
        ["t", this.USERNAME_TOPIC],
      ],
      JSON.stringify({ username: name, app: "AIR Chat" }),
    );
    if (!(await relays.publish(claim))) return { error: "NetworkError" };
    const confirmed = await this.ownerOf(name);
    const pub = hex(publicKey(key));
    if (confirmed && confirmed !== pub) return { error: "UsernameTaken" };
    this.save(name, key);
    try {
      await this.resolveOwner();
    } catch {}
    return { session: this.session };
  }

  /** Reserves a username for a bot account hosted by [owner]. */
  async registerBot(username, key, owner) {
    const name = this.normalize(username);
    if (!this.isUsernameValid(name)) return { error: "UsernameInvalid" };
    let existing;
    try {
      existing = await this.ownerOf(name);
    } catch {
      return { error: "NetworkError" };
    }
    if (relays.connectedCount === 0) return { error: "NetworkError" };
    if (existing) return { error: "UsernameTaken" };
    const claim = createEvent(
      key,
      USERNAME_KIND,
      [
        ["d", this.usernameTag(name)],
        ["t", this.USERNAME_TOPIC],
      ],
      JSON.stringify({ username: name, app: "AIR Chat", bot: true, owner }),
    );
    if (!(await relays.publish(claim))) return { error: "NetworkError" };
    const pub = hex(publicKey(key));
    const confirmed = await this.ownerOf(name);
    if (confirmed && confirmed !== pub) return { error: "UsernameTaken" };
    return { session: { username: name, privateKey: key, publicKey: pub } };
  }

  keystoreTag(u) {
    return `${this.USERNAME_TOPIC}:keystore:${this.normalize(u)}`;
  }

  async latestKeystore(username, owner) {
    const tag = this.keystoreTag(username);
    const events = (await relays.query([{ kinds: [KEYSTORE_KIND], authors: [owner], "#d": [tag] }])).filter(
      (e) => e.kind === KEYSTORE_KIND && e.pubkey === owner && firstTag(e, "d") === tag,
    );
    events.sort((a, b) => b.created_at - a.created_at || (a.id < b.id ? 1 : -1));
    return events[0] ?? null;
  }

  /** The account key for this password, or null when the password is wrong. */
  async keyFor(username, password, owner) {
    const name = this.normalize(username);
    const keystore = await this.latestKeystore(name, owner);
    if (keystore) {
      try {
        const j = JSON.parse(keystore.content);
        const k = await pbkdf2Seed(password, `AIR Chat keystore|${name}|${j.salt}`);
        const key = openSealed(k, unb64(j.data), `AIR Chat keystore|${name}`);
        return hex(publicKey(key)) === owner ? key : null;
      } catch {
        return null;
      }
    }
    const key = await this.deriveKey(name, password);
    return hex(publicKey(key)) === owner ? key : null;
  }

  async changePassword(current, next) {
    const s = this.session;
    if (!s) return { error: "NetworkError" };
    if (!this.passwordProblems(next).isValid) return { error: "PasswordInvalid" };
    let key;
    try {
      key = await this.keyFor(s.username, current, s.publicKey);
    } catch {
      return { error: "NetworkError" };
    }
    if (!key) return { error: "WrongPassword" };
    const salt = b64(randomBytes(16));
    const k = await pbkdf2Seed(next, `AIR Chat keystore|${s.username}|${salt}`);
    const data = b64(seal(k, key, `AIR Chat keystore|${s.username}`));
    const event = createEvent(
      key,
      KEYSTORE_KIND,
      [
        ["d", this.keystoreTag(s.username)],
        ["t", "air-chat-keystore"],
      ],
      JSON.stringify({ v: 1, salt, data }),
    );
    if (!(await relays.publish(event))) return { error: "NetworkError" };
    return { ok: true };
  }

  async login(username, password) {
    const name = this.normalize(username);
    if (!this.isUsernameValid(name)) return { error: "UsernameInvalid" };
    let owner;
    try {
      owner = await this.ownerOf(name);
    } catch {
      return { error: "NetworkError" };
    }
    if (!owner && relays.connectedCount === 0) return { error: "NetworkError" };
    if (!owner) return { error: "AccountNotFound" };
    let key;
    try {
      key = await this.keyFor(name, password, owner);
    } catch {
      return { error: "NetworkError" };
    }
    if (!key) return { error: "WrongPassword" };
    this.save(name, key);
    return { session: this.session };
  }

  save(username, key) {
    this.setSession(username, key);
    this.vault.write(JSON.stringify({ username, key: hex(key) }));
  }

  logout() {
    this.session = null;
    this.vault.write("{}");
  }
}

export const account = new Account();
