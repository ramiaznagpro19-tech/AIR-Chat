// Screenshots of every part of AIR Chat for PC, with real data, two accounts and a bot.
// Usage: node test/screens.mjs <web dir> <output dir>
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { chromium } from "playwright";

const [webDir, outDir] = process.argv.slice(2).map((p) => path.resolve(p));
fs.rmSync(outDir, { recursive: true, force: true });
fs.mkdirSync(outDir, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const procs = [];
function backend(name, port) {
  fs.rmSync(`/tmp/air-shot-${name}`, { recursive: true, force: true });
  procs.push(
    spawn("node", ["src/standalone.js"], {
      env: { ...process.env, AIR_DATA: `/tmp/air-shot-${name}`, AIR_PORT: String(port), AIR_WEB: webDir, AIR_TEST_DIRECTORY: "1" },
      stdio: "inherit",
    }),
  );
}
let n = 0;
let failures = 0;
async function shot(page, name) {
  await sleep(1200);
  n++;
  const file = path.join(outDir, `${String(n).padStart(2, "0")}-${name}.png`);
  await page.screenshot({ path: file });
  console.log("PCSHOT OK", file);
}
async function attempt(name, fn) {
  try {
    await fn();
  } catch (e) {
    failures++;
    console.log("PCSHOT FAIL", name, String(e).slice(0, 300));
  }
}

backend("a", 47931);
backend("b", 47932);
await sleep(3000);
const browser = await chromium.launch({ args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"] });
const stamp = Date.now().toString().slice(-6);
const userA = "pc" + stamp, userB = "ami" + stamp;
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAIAAAAlC+aJAAACMUlEQVR4Xu2avU7DMBRGnQp1Y6sQU3kEXoWJhYmHY2EqPAk8AANMVVfEwkBRZMmE2L4/n29iV8oZqyr5ju+9jgPtNputO2VWpxy+ZxGozVKB2pyZBzh/uM5d8+vp2Tn383hleFMzASL3iNXtu//ExKRUQJ47xsQEFyiJnqwJVhBwFzJMHwgFUYEIYOn9BNMADroWwqKr8A7ydlJUYIb0QDtJBeZM7xG2k0hg/vRyB5FAy/ACJssv2YKSsI3ECJikL4R2oARaSM8OA99CjZMVaGf56SLonsRyPu9e/3+5fwdgJxLAXiCK/oc/INhqpFsI7h8ifUB+zhmRNLccYkl6D+wQYyYgT2/rkBAA+kebHnaIu8isArUwEMCWHy7CeLJrrZwVi0BtlgrUZqmAc4f1ES5D+cEOP42+3b/AuQ3pkv+lJE4TudwX3502FrD88SlQUQF6yQ/ro8oBSJ+EF5C3ityhH5vd9vLmQ1u0mHQLlYwmW4fh0Ksckm8RfAW0+HxJjXi/2u/63wmoNEbYC3hUe+u+oJ2yDzK2E2zxpSDIvYU29CRmHZJQAjMXwbmsA/FHgOwuFFB1sxXDsSbSi36tMn8dhqWg0zsnqIB2S7GFXT7RELNXmQjJfUUCuQfTpEjSi2YgILyiCfJ76Z7E/rqT7kvy6B5pCw3R3kMOcGVEYKKRANI7p2yhuA4m7YRF9+hmICbcGzApyR0oFYj3KNbEJLe9QJXdFtyFmgLchdphEajNL/fGsjE6T19tAAAAAElFTkSuQmCC", "base64");

async function open(port) {
  const ctx = await browser.newContext({ viewport: { width: 1366, height: 820 }, locale: "fr-FR", permissions: ["camera", "microphone"] });
  const page = await ctx.newPage();
  page.on("pageerror", (e) => console.log("PAGE ERROR", port, String(e).slice(0, 200)));
  return page;
}
const api = (page, method, p, body) =>
  page.evaluate(
    async ([method, p, body]) => {
      const r = await fetch("/api" + p, { method, body: body ? JSON.stringify(body) : undefined });
      const t = await r.text();
      return t ? JSON.parse(t) : null;
    },
    [method, p, body],
  );
const upload = (page, tag) =>
  page.evaluate(
    async ([tag, b64]) => {
      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const form = new FormData();
      form.append("file", new Blob([bytes], { type: "image/png" }), "image.png");
      const r = await fetch(`/autumn/${tag}`, { method: "POST", body: form });
      return (await r.json()).id;
    },
    [tag, png.toString("base64")],
  );

const A = await open(47931);
const B = await open(47932);
if (process.env.DEBUG_TYPING) {
  A.on("websocket", (w) => w.on("framereceived", (f) => String(f.payload).includes("Typing") && console.log("A got", String(f.payload).slice(0, 120))));
  B.on("websocket", (w) => w.on("framesent", (f) => String(f.payload).includes("Typing") && console.log("B sent", String(f.payload).slice(0, 120))));
}

// ---- accounts ----
await A.goto("http://127.0.0.1:47931/login");
await shot(A, "connexion");
await A.goto("http://127.0.0.1:47931/login/create");
await A.locator("input[name=email]").fill(userA);
await A.locator("input[type=password]").first().fill("abcd123!?");
await shot(A, "creation-de-compte");
await A.locator("button[type=submit]").click();
await sleep(7000);
await shot(A, "accueil");

await B.goto("http://127.0.0.1:47932/login/create");
await B.locator("input[name=email]").fill(userB);
await B.locator("input[type=password]").first().fill("abcd123!?");
await B.locator("button[type=submit]").click();
await sleep(6000);

// ---- data ----
const avatar = await upload(A, "avatars");
await api(A, "PATCH", "/users/@me", { display_name: "Rami PC", avatar });
const friend = await api(A, "POST", "/users/friend", { username: userB });
const dm = await api(A, "GET", `/users/${friend._id}/dm`);
await sleep(4000);
const meOnB = (await api(B, "POST", "/users/friend", { username: userA }))._id;
const dmB = await api(B, "GET", `/users/${meOnB}/dm`);
await api(B, "POST", `/channels/${dmB._id}/messages`, { content: "Salut ! 😀 Tu as vu le nouveau serveur ?" });
await api(A, "POST", `/channels/${dm._id}/messages`, { content: "Oui, je t'envoie le lien 👇" });
const server = await api(A, "POST", "/servers/create", { name: "Serveur AIR" });
const sid = server.server._id;
const general = server.channels[0]._id;
await api(A, "PATCH", `/servers/${sid}`, { icon: await upload(A, "icons"), description: "Le serveur de test d'AIR Chat" });
await api(A, "POST", `/servers/${sid}/channels`, { type: "Text", name: "annonces" });
await api(A, "POST", `/servers/${sid}/channels`, { type: "Voice", name: "vocal" });
await api(A, "POST", `/servers/${sid}/roles`, { name: "Modérateur" });
const emoji = await upload(A, "emojis");
await api(A, "PUT", `/custom/emoji/${emoji}`, { name: "air", parent: { type: "Server", id: sid } });
const first = await api(A, "POST", `/channels/${general}/messages`, { content: "Bienvenue sur **AIR Chat** 👋" });
await api(A, "PUT", `/channels/${general}/messages/${first._id}/reactions/${encodeURIComponent("🔥")}`);
await api(A, "POST", `/channels/${general}/messages`, { content: "Une image :", attachments: [await upload(A, "attachments")] });
const invites = await api(A, "GET", `/servers/${sid}/invites`);
const link = invites?.[0]?._id;
console.log("server link", link);
if (link) await api(B, "POST", `/invites/${encodeURIComponent(link)}`);
await sleep(3000);
await api(B, "POST", `/channels/${general}/messages`, { content: "Merci pour l'invitation !" });
const bot = await api(A, "POST", "/bots/create", { name: "robot" + stamp });
await api(A, "POST", `/bots/${bot._id}/invite`, { server: sid });
await sleep(2000);
await api(B, "POST", `/channels/${general}/messages`, { content: "!ping" });
await sleep(2500);
await api(A, "POST", `/channels/${general}/messages`, { content: "!aide" });
await sleep(4000);

// ---- the interface ----
const go = async (page, p) => {
  await page.goto("http://127.0.0.1:47931" + p);
  await sleep(3500);
};
await attempt("server", async () => {
  await go(A, `/server/${sid}/channel/${general}`);
  await shot(A, "serveur-salon-messages-bot");
});
await attempt("pinned", async () => {
  await api(A, "POST", `/channels/${general}/messages/${first._id}/pin`);
  await sleep(1500);
  await go(A, `/server/${sid}/channel/${general}`);
  await A.getByText("push_pin").first().click().catch(() => A.locator('[aria-label*="pin" i]').first().click());
  await sleep(1500);
  await shot(A, "messages-epingles");
  await A.keyboard.press("Escape");
});
await attempt("typing", async () => {
  await go(A, `/server/${sid}/channel/${general}`);
  await B.goto(`http://127.0.0.1:47932/server/${sid}/channel/${general}`);
  await sleep(4000);
  await B.locator('textarea, [contenteditable="true"]').last().click({ timeout: 10000 });
  const typing = B.keyboard.type("je réponds dans une seconde, je regarde le serveur", { delay: 150 });
  for (let i = 0; i < 12; i++) {
    await sleep(500);
    const t = await A.evaluate(() => /en train d.écrire|is typing/i.test(document.body.innerText));
    if (process.env.DEBUG_TYPING) console.log("A shows typing", i, t);
    if (t && i >= 2) break;
  }
  await shot(A, "en-train-d-ecrire");
  await typing;
});
await attempt("members", async () => {
  await A.getByText("robot" + stamp).last().click();
  await shot(A, "profil-du-bot");
  await A.keyboard.press("Escape");
});
await attempt("dm", async () => {
  await go(A, `/channel/${dm._id}`);
  await shot(A, "message-prive");
});
await attempt("friends", async () => {
  await go(A, "/friends");
  await shot(A, "amis");
});
await attempt("official", async () => {
  const servers = await api(A, "GET", "/users/@me");
  void servers;
  await A.goto("http://127.0.0.1:47931/");
  await sleep(2500);
  await A.locator('a[href^="/server/"]').first().click();
  await sleep(3000);
  await shot(A, "serveur-officiel-air-chat");
});
await attempt("discover", async () => {
  await go(A, "/discover");
  await shot(A, "decouvrez-air-chat");
});
const settings = async (label, name) =>
  attempt(name, async () => {
    await go(A, "/app");
    await A.locator("a").filter({ has: A.locator("text=settings") }).last().click().catch(() => A.mouse.click(28, 792));
    await sleep(1500);
    if (label) await A.getByText(label, { exact: false }).first().click();
    await sleep(1500);
    await shot(A, name);
    await A.keyboard.press("Escape");
  });
await settings(null, "parametres");
await settings(/^(Profil|Profile)$/, "parametres-profil");
await settings(/Mon compte|My Account/, "mon-compte-mot-de-passe");
await settings(/Mes bots|My Bots/, "mes-bots");
await settings(/Apparence|Appearance/, "apparence");
await settings(/Voix|Voice/, "voix-et-video");
await settings(/Langue|Language/, "langue");

const serverSettings = async (label, name) =>
  attempt(name, async () => {
    await go(A, `/server/${sid}/channel/${general}`);
    await A.mouse.click(280, 32); // the gear next to the server name
    await sleep(1500);
    if (label) await A.getByText(label).first().click();
    await sleep(1500);
    await shot(A, name);
    await A.keyboard.press("Escape");
  });
await serverSettings(null, "serveur-parametres");
await serverSettings(/Rôles|Roles/, "serveur-roles");
await serverSettings(/Émojis|Emojis/, "serveur-emojis");
await serverSettings(/Bannissements|Bans/, "serveur-bannissements");
await serverSettings(/Invitations|Invites/, "serveur-invitations");

await attempt("invite modal", async () => {
  await go(A, `/server/${sid}/channel/${general}`);
  await A.mouse.click(28, 208, { button: "right" }); // the server icon
  await sleep(1000);
  await shot(A, "menu-du-serveur");
  await A.getByText(/Créer une invitation|Inviter|Invite/).first().click();
  await sleep(2000);
  await shot(A, "lien-du-serveur");
  await A.keyboard.press("Escape");
});
await attempt("create server", async () => {
  await go(A, "/app");
  await A.locator("a").filter({ has: A.locator("text=add") }).first().click();
  await sleep(1200);
  await shot(A, "creer-ou-rejoindre");
  await A.keyboard.press("Escape");
});

// ---- call between the two PCs ----
await attempt("call", async () => {
  await go(A, `/channel/${dm._id}`);
  await B.goto("http://127.0.0.1:47932/app");
  await sleep(3000);
  await A.evaluate((id) => window.airCall.start(id, true), dm._id);
  await sleep(5000);
  await shot(B, "appel-entrant");
  await B.getByText("Accepter").click();
  await sleep(8000);
  await B.getByText("Caméra").click().catch(() => {});
  await sleep(5000);
  await shot(A, "appel-video");
  await A.evaluate(() => window.airCall.hangUp());
});

console.log(`PCSHOT SUMMARY count=${n} failures=${failures}`);
await browser.close();
procs.forEach((p) => p.kill());
process.exit(0);
