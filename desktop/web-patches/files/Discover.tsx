import { createResource, createSignal, For, Show } from "solid-js";

import { PublicChannelInvite } from "stoat.js";

import { useClient } from "@revolt/client";
import { useModals } from "@revolt/modal";

type Listed = {
  serverId: string;
  name: string;
  description: string;
  icon: string | null;
  link: string;
  members: number;
};

/**
 * Découvrez AIR Chat: servers with at least one million members, and joining with a link.
 */
export function Discover() {
  const client = useClient();
  const { openModal, showError } = useModals();
  const [link, setLink] = createSignal("");
  const [listings] = createResource(() =>
    client()
      .api.get("/air/discover" as never)
      .then((r) => r as unknown as Listed[])
      .catch(() => [] as Listed[]),
  );

  async function join(code: string) {
    try {
      const invite = await client()
        .api.get(`/invites/${encodeURIComponent(code.trim())}` as never)
        .then((invite) => PublicChannelInvite.from(client(), invite as never));
      openModal({ type: "invite", invite });
    } catch (e) {
      showError(e);
    }
  }

  const card = {
    background: "var(--md-sys-color-surface-container)",
    color: "var(--md-sys-color-on-surface)",
    "border-radius": "16px",
    padding: "16px",
  };

  return (
    <div
      style={{
        flex: 1,
        overflow: "auto",
        padding: "32px",
        color: "var(--md-sys-color-on-surface)",
        display: "flex",
        "flex-direction": "column",
        gap: "20px",
      }}
    >
      <h1 style={{ margin: 0, "font-size": "28px" }}>Découvrez AIR Chat</h1>
      <p style={{ margin: 0, opacity: 0.8 }}>
        Les serveurs publics d'AIR Chat qui ont au moins 1 million de membres. Un
        serveur qui atteint le million peut demander à apparaître ici depuis ses
        paramètres, avec sa description et sa photo.
      </p>

      <div style={{ ...card, display: "flex", gap: "8px", "flex-wrap": "wrap", "align-items": "center" }}>
        <strong style={{ width: "100%" }}>Rejoindre avec un lien</strong>
        <input
          value={link()}
          onInput={(e) => setLink(e.currentTarget.value)}
          placeholder="air.chat.abcd123E"
          style={{
            flex: 1,
            "min-width": "220px",
            padding: "10px 12px",
            "border-radius": "10px",
            border: "1px solid var(--md-sys-color-outline)",
            background: "var(--md-sys-color-surface)",
            color: "inherit",
          }}
        />
        <button
          onClick={() => link().trim() && join(link())}
          style={{
            padding: "10px 18px",
            "border-radius": "20px",
            border: "none",
            cursor: "pointer",
            background: "var(--md-sys-color-primary)",
            color: "var(--md-sys-color-on-primary)",
          }}
        >
          Rejoindre
        </button>
        <button
          onClick={() => join("AIR.CHAT.2026!")}
          style={{
            padding: "10px 18px",
            "border-radius": "20px",
            border: "1px solid var(--md-sys-color-outline)",
            cursor: "pointer",
            background: "transparent",
            color: "inherit",
          }}
        >
          Afficher le serveur AIR Chat
        </button>
      </div>

      <Show
        when={(listings() ?? []).length > 0}
        fallback={
          <div style={{ ...card, opacity: 0.8 }}>
            {listings.loading
              ? "Chargement…"
              : "Aucun serveur n'a encore atteint 1 million de membres."}
          </div>
        }
      >
        <div style={{ display: "grid", "grid-template-columns": "repeat(auto-fill, minmax(260px, 1fr))", gap: "16px" }}>
          <For each={listings()}>
            {(s) => (
              <div style={{ ...card, display: "flex", "flex-direction": "column", gap: "8px" }}>
                <div style={{ display: "flex", gap: "12px", "align-items": "center" }}>
                  <Show when={s.icon}>
                    <img
                      src={`data:image/png;base64,${s.icon}`}
                      style={{ width: "48px", height: "48px", "border-radius": "50%", "object-fit": "cover" }}
                    />
                  </Show>
                  <div>
                    <strong>{s.name}</strong>
                    <div style={{ opacity: 0.7, "font-size": "0.85em" }}>
                      {s.members.toLocaleString("fr-FR")} membres
                    </div>
                  </div>
                </div>
                <div style={{ opacity: 0.85 }}>{s.description}</div>
                <button
                  onClick={() => join(s.link)}
                  style={{
                    "align-self": "flex-start",
                    padding: "8px 16px",
                    "border-radius": "20px",
                    border: "none",
                    cursor: "pointer",
                    background: "var(--md-sys-color-primary)",
                    color: "var(--md-sys-color-on-primary)",
                  }}
                >
                  Rejoindre
                </button>
              </div>
            )}
          </For>
        </div>
      </Show>
    </div>
  );
}
