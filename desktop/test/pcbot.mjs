// PC side of the phone <-> PC test: an AIR Chat PC account that answers every direct message.
// Usage: node test/pcbot.mjs <username>
import fs from "node:fs";
import { Vault, account } from "../src/air/account.js";
import { ITEM, network } from "../src/air/network.js";
import { relays } from "../src/air/nostr.js";

const name = process.argv[2];
Vault.dir = "/tmp/air-pcbot";
fs.rmSync(Vault.dir, { recursive: true, force: true });
account.useTestDirectory();
account.install();
relays.start();
await new Promise((r) => setTimeout(r, 3000));
let result = await account.register(name, "abcd123!?");
if (result.error) result = await account.login(name, "abcd123!?");
if (result.error) {
  console.log("PCBOT cannot register:", result.error);
  process.exit(1);
}
network.start(result.session);
console.log("PCBOT ready as", name, result.session.publicKey);
network.on("items", (items) => {
  for (const item of items) {
    if (item.author === network.selfKey || item.type !== ITEM.MESSAGE) continue;
    if (!item.conversation.startsWith("dm:")) continue;
    console.log("PCBOT received", JSON.stringify(item.text), "from", network.username(item.author));
    network.post(item.conversation, ITEM.MESSAGE, `pong du PC: ${item.text}`);
  }
});
network.on("online", (_b, now) => console.log("PCBOT online peers:", [...now].map((k) => network.username(k))));
setTimeout(() => process.exit(0), 40 * 60 * 1000);
