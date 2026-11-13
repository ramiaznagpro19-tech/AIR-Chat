// PC side of the "same account on phone and PC" test: logs into the phone's account, waits for
// the phone's server to appear, then writes in it.
// Usage: node test/pcdevice.mjs <username> <password>
import fs from "node:fs";
import { Vault, account } from "../src/air/account.js";
import { ITEM, KIND, network } from "../src/air/network.js";
import { relays } from "../src/air/nostr.js";

const [name, password] = process.argv.slice(2);
Vault.dir = "/tmp/air-pcdevice";
fs.rmSync(Vault.dir, { recursive: true, force: true });
account.useTestDirectory();
account.install();
relays.start();
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let result;
for (let i = 0; i < 200; i++) {
  await sleep(5000);
  result = await account.login(name, password);
  if (!result.error) break;
}
if (result.error) {
  console.log("PCDEVICE could not log in:", result.error);
  process.exit(1);
}
network.start(result.session);
console.log("PCDEVICE logged in as", name);
for (let i = 0; i < 120; i++) {
  await sleep(2000);
  const server = [...network.conversations.values()].find((c) => c.kind === KIND.SERVER && c.name === "Serveur test");
  if (!server) continue;
  const channel = network.channelsOf(server.id)[0];
  if (!channel) continue;
  const messages = [...network.items.values()].filter((it) => it.conversation === channel.id && it.type === ITEM.MESSAGE);
  console.log("PCDEVICE sees the phone's server with", messages.length, "messages:", messages.map((m) => m.text).slice(0, 5));
  network.post(channel.id, ITEM.MESSAGE, "écrit sur le PC (même compte)");
  console.log("PCDEVICE wrote in the server");
  await sleep(60_000);
  process.exit(0);
}
console.log("PCDEVICE never saw the phone's server");
process.exit(1);
