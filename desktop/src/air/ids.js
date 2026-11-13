// Ids in the format of the original API, derived the same way as on the phone (identical everywhere).
import { sha256, ALPHABET } from "./crypto.js";

const ID_EPOCH = 1735689600000;

export const idToKey = new Map();

export function deriveId(prefix, value) {
  let time = ID_EPOCH;
  let t = "";
  for (let i = 0; i < 10; i++) {
    t = ALPHABET[time % 32] + t;
    time = Math.floor(time / 32);
  }
  const hash = sha256(`${prefix}:${value}`);
  let out = t;
  let bits = 0;
  let acc = 0;
  let index = 0;
  while (out.length < 26) {
    if (bits < 5) {
      acc = ((acc << 8) | hash[index++]) & 0xffff;
      bits += 8;
    }
    bits -= 5;
    out += ALPHABET[(acc >> bits) & 31];
  }
  idToKey.set(out, `${prefix}:${value}`);
  return out;
}

export const userId = (key) => deriveId("u", key);
export const channelId = (conv) => deriveId("c", conv);
export const serverId = (conv) => deriveId("s", conv);

