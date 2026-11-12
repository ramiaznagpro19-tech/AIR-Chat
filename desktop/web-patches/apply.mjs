// Turns the original web interface into AIR Chat for PC.
// Usage: node apply.mjs <path to packages/client>
// Only the look of the interface is kept: every request goes to the AIR Chat backend built into
// the app (no Stoat service is ever contacted).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const client = path.resolve(process.argv[2] ?? ".");
const p = (...parts) => path.join(client, ...parts);

function patch(file, pairs, { optional = false } = {}) {
  const full = p(file);
  let s = fs.readFileSync(full, "utf8");
  for (const [from, to] of pairs) {
    const found = typeof from === "string" ? s.includes(from) : from.test(s);
    if (!found) {
      if (optional) continue;
      throw new Error(`patch failed in ${file}: ${String(from).slice(0, 80)}`);
    }
    s = typeof from === "string" ? s.split(from).join(to) : s.replace(from, to);
  }
  fs.writeFileSync(full, s);
}

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.name === "node_modules" || e.name === "styled-system") continue;
    const f = path.join(dir, e.name);
    if (e.isDirectory()) walk(f, out);
    else out.push(f);
  }
  return out;
}

// 1. Name: "Stoat" -> "AIR Chat" in every text (source and translations, so they still match)
const brand = (s) => s.replace(/\bStoat\b/g, "AIR Chat").replace(/\bRevolt\b(?! Admin)/g, "AIR Chat");
for (const f of walk(p("components")).concat(walk(p("src")))) {
  if (!/\.(tsx?|po)$/.test(f)) continue;
  const s = fs.readFileSync(f, "utf8");
  const b = f.endsWith(".po") ? s.replace(/^(msgid|msgstr) (".*")$/gm, (m, k, v) => `${k} ${brand(v)}`) : brand(s);
  if (b !== s) fs.writeFileSync(f, b);
}
patch("index.html", [["<title>AIR Chat</title>", "<title>AIR Chat</title>"]], { optional: true });
patch("index.html", [["<title>Stoat</title>", "<title>AIR Chat</title>"]], { optional: true });

// 2. Logo everywhere
const assetsDir = fs.realpathSync(p("public", "assets"));
for (const f of fs.readdirSync(path.join(here, "assets"))) {
  fs.copyFileSync(path.join(here, "assets", f), path.join(assetsDir, "web", f));
}
for (const f of ["scripts/assets_fallback/web"]) {
  const d = p(f);
  if (fs.existsSync(d)) for (const a of fs.readdirSync(path.join(here, "assets"))) fs.copyFileSync(path.join(here, "assets", a), path.join(d, a));
}

// 3. Accounts: username instead of email, AIR Chat password rules, no email links
patch("components/auth/src/flows/Form.tsx", [
  [
    `    email: {
      type: "email" as const,
      name: () => t\`Email\`,
      placeholder: () => t\`Please enter your email.\`,
      autocomplete: "email",
    },`,
    `    email: {
      type: "text" as const,
      name: () => "Nom d'utilisateur",
      placeholder: () => "Votre nom d'utilisateur AIR Chat (3 à 20 caractères : a-z, 0-9, . et _)",
      autocomplete: "username",
      minLength: 3,
      maxLength: 20,
    },`,
  ],
]);
patch("components/auth/src/flows/FlowLogin.tsx", [
  [
    /<Column gap="sm" align class="auth-help-links">[\s\S]*?<\/Column>/,
    "",
  ],
]);
patch("components/auth/src/flows/FlowCreate.tsx", [
  [
    `            "new-password",
          ]}
        />`,
    `            "new-password",
          ]}
        />
        <span style={{ "font-size": "0.85em", opacity: 0.8 }}>
          Mot de passe : au moins 4 lettres, 3 chiffres et 2 caractères spéciaux. Le nom
          d'utilisateur est unique et ne pourra jamais être repris.
        </span>`,
  ],
]);

// 4. No links to the original company's websites or apps
patch("components/auth/src/AuthPage.tsx", [[/<Footer>[\s\S]*?<\/Footer>/, "<Footer />"]]);
patch("components/auth/src/AppUpsell.tsx", [[/export function AppUpsell\(\) \{/, "export function AppUpsell() {\n  return null;"]]);

// 5. Errors from AIR Chat are already written for people: show them as they are
patch("components/i18n/errors.tsx", [[/return t`Uncaught [^`]*error: \$\{err\.type\}`;/, "return String(err.type);"]]);

console.log("AIR Chat patches applied to", client);

// 6. Découvrez AIR Chat instead of the original discovery site; no donation links
fs.copyFileSync(path.join(here, "files", "Discover.tsx"), p("src/interface/Discover.tsx"));
patch("src/interface/Home.tsx", [
  ["const showLoungeButton = instance.isStoat;", "const showLoungeButton = false;"],
  ["<Show when={instance.isStoat}>", "<Show when={true}>"],
  [/<CategoryButton\s+variant="tertiary"\s+onClick=\{\(\) => window\.open\("https:\/\/ko-fi\.com\/stoatchat"\)\}[\s\S]*?<\/CategoryButton>/, ""],
]);
patch("src/interface/navigation/servers/ServerList.tsx", [["<Show when={instance.isStoat}>", "<Show when={true}>"]]);
patch("components/app/interface/settings/user/Feedback.tsx", [["const showLoungeButton = instance.isStoat;", "const showLoungeButton = false;"]], { optional: true });
patch("components/modal/modals/CreateInvite.tsx", [
  ["inst.isStoat ? `https://stt.gg/${id}` : inst.href(`/invite/${id}`);", "id;"],
]);
patch("components/modal/modals/Changelog.tsx", [[/const CHANGELOG_ENDPOINT = "[^"]*";/, 'const CHANGELOG_ENDPOINT = "data:application/json,null";']]);

// 7. Settings: "What's New" becomes "Découvrez AIR Chat", no source/donation links
patch("components/app/interface/settings/UserSettings.tsx", [
  [
    /onClick: async \(\) => \{\s*const changelog = await fetchLatestChangelog\(\);\s*if \(!changelog\) return;\s*openModal\(\{ type: "changelog", changelog \}\);\s*\},\s*icon: <MdCampaign \{\.\.\.iconSize\(20\)\} \/>,\s*title: <Trans>What's New<\/Trans>,/,
    `onClick: () => {
                pop();
                window.location.assign("/discover");
              },
              icon: <MdCampaign {...iconSize(20)} />,
              title: "Découvrez AIR Chat",`,
  ],
  [/\{\s*href: "https:\/\/github\.com\/stoatchat",[\s\S]*?\},\s*\{\s*id: "advanced"/, '{\n              id: "advanced"'],
  [/\{\s*href: "https:\/\/ko-fi\.com\/stoatchat",[\s\S]*?\},\s*\{\s*id: "logout"/, '{\n              id: "logout"'],
]);

// 8. Verified badge (blue check), like on the phone
const VERIFIED_SVG =
  "data:image/svg+xml;utf8," +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#1D9BF0" d="m23 12-2.44-2.79.34-3.69-3.61-.82-1.89-3.2L12 2.96 8.6 1.5 6.71 4.69 3.1 5.5l.34 3.7L1 12l2.44 2.79-.34 3.7 3.61.82L8.6 22.5l3.4-1.47 3.4 1.46 1.89-3.19 3.61-.82-.34-3.69z"/><path fill="#fff" d="m10 17-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9z"/></svg>',
  );
patch("components/app/interface/channels/text/Message.tsx", [
  [
    `          <Switch fallback={<div />}>
            <Match when={props.message.iconRole}>`,
    `          <Switch fallback={<div />}>
            <Match when={((props.message.author?.badges ?? 0) & 2048) !== 0}>
              <Tooltip content="Compte vérifié" placement="top">
                <img src="${VERIFIED_SVG}" width={16} height={16} alt="Compte vérifié" />
              </Tooltip>
            </Match>
            <Match when={props.message.iconRole}>`,
  ],
]);
patch("components/ui/components/features/profiles/ProfileBadges.tsx", [
  [
    `        <BadgeRow>`,
    `        <BadgeRow>
          <Show when={(props.user.badges ?? 0) & 2048}>
            <img
              use:floating={{ tooltip: { placement: "top", content: "Compte vérifié" } }}
              src="${VERIFIED_SVG}"
            />
          </Show>`,
  ],
]);

// 9. Calls: voice, video (HD to 4K) and screen sharing over the AIR Chat network
fs.copyFileSync(path.join(here, "files", "airCall.ts"), p("src/airCall.ts"));
patch("src/index.tsx", [['import "./sentry";', 'import "./sentry";\nimport "./airCall";']]);
patch("components/rtc/state.tsx", [
  [
    "async connect(channel: Channel, auth?: { url: string; token: string }) {",
    "async connect(channel: Channel, auth?: { url: string; token: string }) {\n    if ((window as any).airCall) {\n      (window as any).airCall.start(channel.id, false);\n      return;\n    }",
  ],
]);

// 10. The interface always talks to the AIR Chat backend that serves it (this computer)
patch("components/common/lib/env.ts", [
  [
    /const DEFAULT_HOST = normalizeHost\([\s\S]*?\);/,
    "const DEFAULT_HOST = typeof location !== \"undefined\" ? location.host : \"127.0.0.1:47821\";",
  ],
  [
    /const DEFAULT_API_URL =\s*getEnv\("VITE_DEV_API_URL", true\) \|\| getEnv\("VITE_API_URL"\) \|\| STOAT_API;/,
    "const DEFAULT_API_URL = typeof location !== \"undefined\" ? location.origin + \"/api\" : \"http://127.0.0.1:47821/api\";",
  ],
]);
