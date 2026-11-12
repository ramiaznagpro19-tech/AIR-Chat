// AIR Chat cryptography, byte-compatible with the Android app (AirCrypto, Secp256k1).
import crypto from "node:crypto";
import { schnorr, secp256k1 } from "@noble/curves/secp256k1";

const N = secp256k1.CURVE.n;

export const randomBytes = (n) => new Uint8Array(crypto.randomBytes(n));

export function hex(bytes) {
  return Buffer.from(bytes).toString("hex");
}

export function unhex(s) {
  return new Uint8Array(Buffer.from(s, "hex"));
}

/** URL-safe base64 without padding (Android: URL_SAFE | NO_WRAP | NO_PADDING). */
export function b64(bytes) {
  return Buffer.from(bytes).toString("base64url");
}

export function unb64(text) {
  return new Uint8Array(Buffer.from(text, "base64url"));
}

export function sha256(data) {
  return new Uint8Array(crypto.createHash("sha256").update(typeof data === "string" ? Buffer.from(data, "utf8") : data).digest());
}

export function hkdf(ikm, salt, info, length = 32) {
  const prk = crypto.createHmac("sha256", salt.length ? Buffer.from(salt) : Buffer.alloc(32)).update(Buffer.from(ikm)).digest();
  const out = [];
  let previous = Buffer.alloc(0);
  let total = 0;
  for (let counter = 1; total < length; counter++) {
    previous = crypto.createHmac("sha256", prk).update(previous).update(Buffer.from(info, "utf8")).update(Buffer.from([counter])).digest();
    out.push(previous);
    total += previous.length;
  }
  return new Uint8Array(Buffer.concat(out).subarray(0, length));
}

/** AES-256-GCM: iv(12) || ciphertext || tag(16), with a text AAD. */
export function seal(key, plaintext, aad) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", Buffer.from(key), iv);
  cipher.setAAD(Buffer.from(aad, "utf8"));
  const body = Buffer.concat([cipher.update(Buffer.from(plaintext)), cipher.final()]);
  return new Uint8Array(Buffer.concat([iv, body, cipher.getAuthTag()]));
}

export function open(key, sealed, aad) {
  const buf = Buffer.from(sealed);
  if (buf.length <= 28) throw new Error("Ciphertext too short");
  const iv = buf.subarray(0, 12);
  const tag = buf.subarray(buf.length - 16);
  const decipher = crypto.createDecipheriv("aes-256-gcm", Buffer.from(key), iv);
  decipher.setAAD(Buffer.from(aad, "utf8"));
  decipher.setAuthTag(tag);
  return new Uint8Array(Buffer.concat([decipher.update(buf.subarray(12, buf.length - 16)), decipher.final()]));
}

// ---- secp256k1 (BIP-340) ----

const toBig = (bytes) => BigInt("0x" + (hex(bytes) || "0"));
const bytes32 = (v) => unhex(v.toString(16).padStart(64, "0"));

/** Same derivation as Secp256k1.privateKeyFromSeed on Android. */
export function privateKeyFromSeed(seed) {
  const d = (toBig(sha256(seed)) % (N - 1n)) + 1n;
  return bytes32(d);
}

export function randomPrivateKey() {
  return privateKeyFromSeed(randomBytes(32));
}

/** x-only public key (32 bytes). */
export function publicKey(privateKey) {
  return schnorr.getPublicKey(privateKey);
}

export function signSchnorr(msg32, privateKey) {
  return schnorr.sign(msg32, privateKey, randomBytes(32));
}

export function verifySchnorr(msg32, pub, sig) {
  try {
    return schnorr.verify(sig, msg32, pub);
  } catch {
    return false;
  }
}

/** x coordinate of privateKey * liftX(publicKey) (even-y lift, like Android). */
export function sharedSecret(privateKey, xOnlyPublic) {
  const point = secp256k1.getSharedSecret(privateKey, unhex("02" + hex(xOnlyPublic)), true);
  return point.subarray(1, 33);
}

export function pbkdf2Seed(password, salt) {
  return new Promise((resolve, reject) =>
    crypto.pbkdf2(Buffer.from(password, "utf8"), Buffer.from(salt, "utf8"), 210_000, 32, "sha256", (err, key) =>
      err ? reject(err) : resolve(new Uint8Array(key)),
    ),
  );
}

// ---- JSON exactly as Android's org.json writes it (needed for signatures) ----

function quoteOrgJson(s) {
  let out = '"';
  for (const ch of s) {
    const c = ch.codePointAt(0);
    switch (ch) {
      case '"':
      case "\\":
      case "/":
        out += "\\" + ch;
        break;
      case "\t":
        out += "\\t";
        break;
      case "\b":
        out += "\\b";
        break;
      case "\n":
        out += "\\n";
        break;
      case "\r":
        out += "\\r";
        break;
      case "\f":
        out += "\\f";
        break;
      default:
        out += c <= 0x1f ? "\\u" + c.toString(16).padStart(4, "0") : ch;
    }
  }
  return out + '"';
}

export function orgJsonArray(values) {
  return (
    "[" +
    values
      .map((v) => {
        if (Array.isArray(v)) return orgJsonArray(v);
        if (typeof v === "number" || typeof v === "bigint") return String(v);
        if (typeof v === "boolean") return String(v);
        return quoteOrgJson(String(v));
      })
      .join(",") +
    "]"
  );
}

// ---- ULID (same alphabet as the Android app) ----

const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
let lastTime = 0;
let lastRandom = null;

export function ulid() {
  let now = Date.now();
  if (now <= lastTime && lastRandom) {
    now = lastTime;
    // increment the random part
    for (let i = lastRandom.length - 1; i >= 0; i--) {
      if (lastRandom[i] < 31) {
        lastRandom[i]++;
        break;
      }
      lastRandom[i] = 0;
    }
  } else {
    lastRandom = Array.from(crypto.randomBytes(16)).map((b) => b & 31);
    lastTime = now;
  }
  let time = now;
  let t = "";
  for (let i = 0; i < 10; i++) {
    t = ALPHABET[time % 32] + t;
    time = Math.floor(time / 32);
  }
  return t + lastRandom.map((v) => ALPHABET[v]).join("");
}

export { ALPHABET };
