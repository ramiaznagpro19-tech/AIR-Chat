// AIR Chat for PC (Windows): the window, and the AIR Chat backend running on this computer.
import { app, BrowserWindow, desktopCapturer, Menu, safeStorage, session, shell } from "electron";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Vault, account } from "./air/account.js";
import { LocalServer } from "./air/localserver.js";

const here = path.dirname(fileURLToPath(import.meta.url));
const PORT = 47821;
const ORIGIN = `http://127.0.0.1:${PORT}`;

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  let win = null;

  app.on("second-instance", () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });

  app.whenReady().then(async () => {
    app.setAppUserModelId("chat.air.desktop");
    Menu.setApplicationMenu(null);

    // everything is kept on this computer, encrypted with the Windows account when possible
    Vault.dir = path.join(app.getPath("userData"), "air-data");
    if (safeStorage.isEncryptionAvailable()) {
      Vault.protect = (text) => Buffer.concat([Buffer.from("AIRV1"), safeStorage.encryptString(text)]);
      Vault.unprotect = (buf) =>
        buf.subarray(0, 5).toString() === "AIRV1" ? safeStorage.decryptString(buf.subarray(5)) : buf.toString("utf8");
    }
    account.install();

    // the interface ships with the app: never keep an old copy cached by a service worker
    await session.defaultSession.clearStorageData({ storages: ["serviceworkers", "cachestorage"] }).catch(() => {});

    const server = new LocalServer({ port: PORT, webDir: path.join(here, "..", "web") });
    await server.listen();

    // AIR Chat talks only to itself on this computer and to the peer-to-peer network:
    // the interface never contacts any outside service.
    session.defaultSession.webRequest.onBeforeRequest((details, callback) => {
      const url = details.url;
      const allowed =
        url.startsWith(ORIGIN) ||
        url.startsWith("ws://127.0.0.1:" + PORT) ||
        url.startsWith("data:") ||
        url.startsWith("blob:") ||
        url.startsWith("devtools:") ||
        url.startsWith("chrome-extension:");
      if (!allowed) console.log("[air] blocked", details.url.slice(0, 200));
      callback({ cancel: !allowed });
    });
    session.defaultSession.setPermissionRequestHandler((_wc, permission, callback) => {
      callback(["media", "notifications", "clipboard-read", "clipboard-sanitized-write", "display-capture", "fullscreen"].includes(permission));
    });

    // screen sharing in calls: the whole screen
    session.defaultSession.setDisplayMediaRequestHandler(async (_request, callback) => {
      try {
        const sources = await desktopCapturer.getSources({ types: ["screen"] });
        callback(sources.length ? { video: sources[0] } : {});
      } catch {
        callback({});
      }
    });

    win = new BrowserWindow({
      width: 1280,
      height: 820,
      minWidth: 900,
      minHeight: 600,
      title: "AIR Chat",
      backgroundColor: "#111111",
      icon: path.join(here, "..", "build", "icon.png"),
      autoHideMenuBar: true,
      webPreferences: { contextIsolation: true, sandbox: true, spellcheck: true },
    });
    win.on("page-title-updated", (e) => {
      e.preventDefault();
      win.setTitle("AIR Chat");
    });
    // links written in messages open in the normal web browser
    win.webContents.setWindowOpenHandler(({ url }) => {
      if (/^https?:\/\//.test(url) && !url.startsWith(ORIGIN)) shell.openExternal(url);
      return { action: "deny" };
    });
    win.webContents.on("will-navigate", (e, url) => {
      if (!url.startsWith(ORIGIN)) {
        e.preventDefault();
        if (/^https?:\/\//.test(url)) shell.openExternal(url);
      }
    });
    win.loadURL(ORIGIN + "/");
  });

  app.on("window-all-closed", () => app.quit());
}
