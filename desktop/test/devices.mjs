// The same account on two devices (e.g. phone and PC) shows the same servers, messages and friends.
import { spawn } from "node:child_process";
import fs from "node:fs";

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const procs = [];
function start(name, port) {
  fs.rmSync(`/tmp/air-${name}`, { recursive: true, force: true });
  procs.push(spawn("node", ["src/standalone.js"], { env: { ...process.env, AIR_DATA: `/tmp/air-${name}`, AIR_PORT: String(port), AIR_TEST_DIRECTORY: "1" }, stdio: "inherit" }));
}
const call = async (port, method, path, body) => {
  const r = await fetch(`http://127.0.0.1:${port}/api${path}`, { method, body: body ? JSON.stringify(body) : undefined });
  const text = await r.text();
  if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${text}`);
  return text ? JSON.parse(text) : null;
};
let failures = 0;
const check = (name, ok, extra = "") => {
  console.log(`DEVTEST ${ok ? "OK  " : "FAIL"} ${name} ${extra}`);
  if (!ok) failures++;
};
const until = async (fn, tries = 30) => {
  for (let i = 0; i < tries; i++) {
    try {
      const v = await fn();
      if (v) return v;
    } catch {}
    await sleep(1500);
  }
  return null;
};
try {
  start("d1", 47921); // "phone"
  start("d2", 47922); // "PC", same account
  start("d3", 47923); // a friend
  await sleep(2500);
  const stamp = Date.now().toString().slice(-7);
  const me = "dm" + stamp, friend = "df" + stamp;
  await call(47921, "POST", "/auth/account/create", { email: me, password: "abcd123!?" });
  await call(47921, "POST", "/auth/session/login", { email: me, password: "abcd123!?" });
  await call(47923, "POST", "/auth/account/create", { email: friend, password: "abcd123!?" });
  await call(47923, "POST", "/auth/session/login", { email: friend, password: "abcd123!?" });
  const f = await call(47921, "POST", "/users/friend", { username: friend });
  const dm = await call(47921, "GET", `/users/${f._id}/dm`);
  await call(47921, "POST", `/channels/${dm._id}/messages`, { content: "écrit sur le téléphone" });
  const server = await call(47921, "POST", "/servers/create", { name: "Serveur du téléphone" });
  await call(47921, "POST", `/channels/${server.channels[0]._id}/messages`, { content: "message serveur" });
  await call(47921, "PATCH", "/users/@me", { display_name: "Moi partout" });
  // the PC logs in with the same account
  await call(47922, "POST", "/auth/session/login", { email: me, password: "abcd123!?" });
  check("server appears on the other device", !!(await until(() => call(47922, "GET", `/servers/${server.server._id}`).then((s) => s.name === "Serveur du téléphone"))));
  check("server messages", !!(await until(() => call(47922, "GET", `/channels/${server.channels[0]._id}/messages?limit=10`).then((m) => m.some((x) => x.content === "message serveur")))));
  check("direct messages", !!(await until(() => call(47922, "GET", `/channels/${dm._id}/messages?limit=10`).then((m) => m.some((x) => x.content === "écrit sur le téléphone")))));
  check("friends", !!(await until(() => call(47922, "GET", `/users/${f._id}`).then((u) => u.relationship === "Friend"))));
  check("profile", !!(await until(() => call(47922, "GET", "/users/@me").then((u) => u.display_name === "Moi partout"))));
  // written on the PC, seen on the phone
  await call(47922, "POST", `/channels/${dm._id}/messages`, { content: "écrit sur le PC" });
  check("PC -> phone", !!(await until(() => call(47921, "GET", `/channels/${dm._id}/messages?limit=10`).then((m) => m.some((x) => x.content === "écrit sur le PC")))));
  check("the friend gets it too", !!(await until(() => call(47923, "GET", `/channels/${dm._id}/messages?limit=10`).then((m) => m.some((x) => x.content === "écrit sur le PC")))));
  const s2 = await call(47922, "POST", "/servers/create", { name: "Serveur du PC" });
  check("server created on PC appears on phone", !!(await until(() => call(47921, "GET", `/servers/${s2.server._id}`).then((s) => s.name === "Serveur du PC"))));
} catch (e) {
  check("unexpected error", false, String(e));
} finally {
  console.log(`DEVTEST SUMMARY failures=${failures}`);
  procs.forEach((p) => p.kill());
  process.exit(failures ? 1 : 0);
}
