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

// 11. Emojis are part of the app (no outside image server)
{
  const { execSync } = await import("node:child_process");
  const tmp = fs.mkdtempSync(path.join((await import("node:os")).tmpdir(), "air-emoji-"));
  execSync("npm install --no-save --no-audit --no-fund @twemoji/svg@15.0.0", { cwd: tmp, stdio: "inherit" });
  const src = path.join(tmp, "node_modules", "@twemoji", "svg");
  const dest = p("public", "emoji");
  fs.mkdirSync(dest, { recursive: true });
  for (const f of fs.readdirSync(src)) if (f.endsWith(".svg")) fs.copyFileSync(path.join(src, f), path.join(dest, f));
}
patch("components/markdown/emoji/UnicodeEmoji.tsx", [
  [
    "return `https://static.stoat.chat/emoji/${pack}/${toCodepoint(text)}.svg?v=1`;",
    "void pack;\n  const cp = toCodepoint(text);\n  return `/emoji/${cp.includes(\"200d\") ? cp : cp.replace(/-fe0f/g, \"\")}.svg`;",
  ],
]);
// no outside location lookup
patch("src/interface/channels/AgeGate.tsx", [
  ['const response = await fetch("https://geo.revolt.chat");', "const response = new Response(\"{}\");"],
]);

// 12. Copying ids and links gives the AIR Chat link of the server, or the username
patch("components/app/menus/ServerContextMenu.tsx", [["navigator.clipboard.writeText(props.server.id);", "(window as any).airShare(props.server.id);"]]);
patch("components/app/menus/UserContextMenu.tsx", [["navigator.clipboard.writeText(props.user.id);", "(window as any).airShare(props.user.id);"]]);
patch("components/app/menus/ChannelContextMenu.tsx", [
  ["navigator.clipboard.writeText(props.channel.id);", "(window as any).airShare(props.channel.id);"],
  [/navigator\.clipboard\.writeText\(\s*instance\.href\(\s*`\$\{\s*props\.channel\.server[\s\S]*?\),\s*\);/, "(window as any).airShare(props.channel.id);"],
]);
patch("components/app/menus/MessageContextMenu.tsx", [
  [/navigator\.clipboard\.writeText\(\s*instance\.href\(\s*`\$\{\s*props\.message!\.server[\s\S]*?\),\s*\);/, "(window as any).airShare(props.message!.channelId);"],
]);
patch("components/ui/components/features/profiles/ProfileBanner.tsx", [
  ["`${props.user.username}#${props.user.discriminator}`,", "props.user.username,"],
]);

// 13. Bots without code: explain how to set them up instead of linking to developer docs
patch("components/app/interface/settings/user/bots/MyBots.tsx", [
  [
    /<CategoryButton\s+action="external"\s+icon=\{<MdLibraryBooks \{\.\.\.iconSize\(22\)\} \/>\}[\s\S]*?<\/CategoryButton>/,
    `<CategoryButton
        icon={<MdLibraryBooks {...iconSize(22)} />}
        onClick={() => {}}
        description={
          <span style={{ "white-space": "pre-line" }}>
            {"Un bot est hébergé par cet ordinateur : il répond tant qu'AIR Chat est ouvert ici. Ajoutez-le à un serveur depuis sa page (Inviter le bot), puis écrivez !aide dans un salon.\\n\\nPour tout le monde : !aide, !ping, !info, !des, !choisir a | b, et vos commandes.\\n\\nRéglages (vous uniquement) :\\n• !commande nom réponse · !supprimer nom\\n• !bienvenue texte ({user}) · !bienvenue off\\n• !auto mot = réponse · !auto-supprimer mot\\n• !interdire mot · !autoriser mot\\n• !prefixe ?\\n\\nBot en code (Python ou page HTML) : copiez le jeton sur la page du bot. Le programme se connecte à http://127.0.0.1:47821/api avec l'en-tête x-bot-token. Il peut lire, écrire, répondre et réagir, mais ne peut jamais modifier les serveurs, les salons, les rôles ni l'application. Exemples : bot-examples (bot.py, bot.html) dans les téléchargements d'AIR Chat PC."}
          </span>
        }
      >
        Comment régler un bot (sans code)
      </CategoryButton>`,
  ],
]);

// 14. Account: an AIR Chat account is a username and a password (no email, no MFA, the
// username can never change), so only what applies is shown
patch("components/app/interface/settings/user/Account.tsx", [
  ["      <MultiFactorAuth />\n      <ManageAccount />\n", "      <span style={{ opacity: 0.8 }}>\n        Votre compte AIR Chat est votre nom d'utilisateur et votre mot de passe. Le nom\n        d'utilisateur est unique et ne change jamais ; le mot de passe peut être changé. Connectez-vous avec les mêmes sur tous\n        vos appareils (téléphone, PC) : vos serveurs, messages et amis s'y synchronisent.\n      </span>\n"],
  [/<CategoryButton\s+action="chevron"\s+onClick=\{\(\) => \{\s*props\.onClose\?\.\(\);\s*stow\(\);[\s\S]*?<\/CategoryButton>/, ""],
  [/<CategoryButton\s+action="chevron"\s+onClick=\{\(\) =>\s*openModal\(\{\s*type: "edit_email",[\s\S]*?<\/CategoryButton>/, ""],
  [/<CategoryButton\s+action="chevron"\s+onClick=\{\(\) =>\s*openModal\(\{\s*type: "edit_username",\s*client: client\(\),\s*\}\)\s*\}/, "<CategoryButton\n        onClick={() => {}}"],
]);

// 15. No reporting (there is no one to receive reports), no push notifications on PC
patch("components/app/menus/MessageContextMenu.tsx", [
  [/<ContextMenuButton icon=\{MdReport\} onClick=\{report\} destructive>\s*<Trans>Report message<\/Trans>\s*<\/ContextMenuButton>/, ""],
]);
patch("components/app/menus/ServerContextMenu.tsx", [
  [/<ContextMenuButton icon=\{MdReport\} onClick=\{report\} destructive>\s*<Trans>Report server<\/Trans>\s*<\/ContextMenuButton>/, ""],
]);
patch("components/app/menus/UserContextMenu.tsx", [
  [/<Show when=\{!props\.user\.privileged\}>\s*<ContextMenuButton\s*symbol=\{\s*<IconSlot>\s*<Symbol size=\{16\}>report<\/Symbol>[\s\S]*?<Trans>Report user<\/Trans>\s*<\/ContextMenuButton>\s*<\/Show>/, ""],
]);
patch("components/client/NotificationsController.ts", [
  ['const supportsNotification = "Notification" in window;', "// AIR Chat for PC: no notifications\n  const supportsNotification = false;"],
  ["async function setUpServiceWorkerSubscription(client: Client) {", "async function setUpServiceWorkerSubscription(client: Client) {\n  if (client) return;"],
]);
patch("components/app/interface/settings/UserSettings.tsx", [
  [/\{\s*id: "notifications",\s*icon: <MdNotifications \{\.\.\.iconSize\(20\)\} \/>,\s*title: <Trans>Notifications<\/Trans>,\s*\},/, ""],
]);
