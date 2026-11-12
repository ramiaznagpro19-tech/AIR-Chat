// End-to-end check of the PC backend on the real relays: two accounts, friend request, direct messages.
import { spawn } from "node:child_process";
import fs from "node:fs";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const procs = [];
function start(name, port) {
  fs.rmSync(`/tmp/air-${name}`, { recursive: true, force: true });
  const p = spawn("node", ["src/standalone.js"], {
    env: { ...process.env, AIR_DATA: `/tmp/air-${name}`, AIR_PORT: String(port), AIR_TEST_DIRECTORY: "1" },
    stdio: "inherit",
  });
  procs.push(p);
}
const call = async (port, method, path, body) => {
  const r = await fetch(`http://127.0.0.1:${port}/api${path}`, { method, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text();
  if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${text}`);
  return text ? JSON.parse(text) : null;
};
let failures = 0;
const check = (name, ok, extra = "") => {
  console.log(`P2PTEST ${ok ? "OK  " : "FAIL"} ${name} ${extra}`);
  if (!ok) failures++;
};
try {
  start("a", 47911);
  start("b", 47912);
  await sleep(2500);
  const stamp = Date.now().toString().slice(-8);
  const a = "pa" + stamp, b = "pb" + stamp;
  for (const [port, name] of [[47911, a], [47912, b]]) {
    await call(port, "POST", "/auth/account/create", { email: name, password: "abcd123!?" });
    await call(port, "POST", "/auth/session/login", { email: name, password: "abcd123!?" });
  }
  check("accounts", true, `${a} ${b}`);
  const friend = await call(47912, "POST", "/users/friend", { username: a });
  check("friend by username", friend.username === a);
  const dm = await call(47912, "GET", `/users/${friend._id}/dm`);
  await call(47912, "POST", `/channels/${dm._id}/messages`, { content: "Bonjour du PC B" });
  let got = [];
  for (let i = 0; i < 40 && !got.includes("Bonjour du PC B"); i++) {
    await sleep(1500);
    try {
      got = (await call(47911, "GET", `/channels/${dm._id}/messages?limit=10`)).map((m) => m.content);
    } catch {}
  }
  check("message delivered peer to peer", got.includes("Bonjour du PC B"), JSON.stringify(got));
  const server = await call(47911, "POST", "/servers/create", { name: "Serveur PC" });
  check("server created", !!server.server?._id);
  let invites = [];
  for (let i = 0; i < 10 && !invites.length; i++) {
    invites = await call(47911, "GET", `/servers/${server.server._id}/invites`);
    if (!invites.length) await sleep(2000);
  }
  const link = invites[0]?._id ?? "";
  check("server link air.chat.xxxx000X", /^air\.chat\.[a-zA-Z0-9]{8}$/.test(link), link);
  if (link) {
    const joined = await call(47912, "POST", `/invites/${link}`).catch((e) => ({ error: String(e) }));
    check("join with the link", !!joined.server, JSON.stringify(joined).slice(0, 200));
  }
} catch (e) {
  check("unexpected error", false, String(e));
} finally {
  console.log(`P2PTEST SUMMARY failures=${failures}`);
  procs.forEach((p) => p.kill());
  process.exit(failures ? 1 : 0);
}
