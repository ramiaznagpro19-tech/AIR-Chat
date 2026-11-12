/**
 * AIR Chat calls on PC: voice and video calls up to 8 people, HD to 4K, and screen sharing.
 * Same call protocol as the phone app (AirCall.kt): every participant connects directly to
 * every other one (WebRTC, encrypted end to end), signalling goes through the AIR Chat network.
 */

type Quality = { label: string; width: number; height: number; fps: number; bitrate: number };

const QUALITIES: Quality[] = [
  { label: "HD 720p", width: 1280, height: 720, fps: 30, bitrate: 2_500_000 },
  { label: "Full HD 1080p 60", width: 1920, height: 1080, fps: 60, bitrate: 6_000_000 },
  { label: "2K 1440p 60", width: 2560, height: 1440, fps: 60, bitrate: 12_000_000 },
  { label: "4K 2160p 30", width: 3840, height: 2160, fps: 30, bitrate: 20_000_000 },
];

const ICE = [
  { urls: "stun:stun.l.google.com:19302" },
  { urls: "stun:stun1.l.google.com:19302" },
  { urls: "stun:stun.cloudflare.com:3478" },
];
const MAX_PARTICIPANTS = 8;

type State = "idle" | "outgoing" | "incoming" | "connecting" | "active";
type Member = { key: string; name: string };
type Link = {
  peer: string;
  pc: RTCPeerConnection;
  connected: boolean;
  video: RTCRtpTransceiver;
  audio: RTCRtpTransceiver;
  stream: MediaStream;
};

class AirCall {
  ws: WebSocket | null = null;
  state: State = "idle";
  callId: string | null = null;
  conversation: string | null = null;
  caller: string | null = null;
  video = false;
  names = new Map<string, string>();
  links = new Map<string, Link>();
  mic: MediaStreamTrack | null = null;
  camera: MediaStreamTrack | null = null;
  screen: MediaStreamTrack | null = null;
  micOn = true;
  quality = QUALITIES[0];
  ui: HTMLDivElement | null = null;
  ringTimer: number | undefined;

  connect() {
    const token = this.token();
    if (!token) {
      setTimeout(() => this.connect(), 3000);
      return;
    }
    const ws = new WebSocket(`ws://${location.host}/air-call?token=${encodeURIComponent(token)}`);
    this.ws = ws;
    ws.onmessage = (e) => {
      try {
        this.onBackend(JSON.parse(e.data));
      } catch (err) {
        console.warn("[air-call]", err);
      }
    };
    ws.onclose = () => {
      this.ws = null;
      setTimeout(() => this.connect(), 3000);
    };
  }

  token(): string | null {
    try {
      for (let i = 0; i < localStorage.length; i++) {
        const v = localStorage.getItem(localStorage.key(i)!) ?? "";
        const m = /air-local\.[0-9a-f]{64}/.exec(v);
        if (m) return m[0];
      }
    } catch {
      // ignore
    }
    return null;
  }

  backend(msg: object) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg));
  }

  send(to: string, payload: Record<string, unknown>) {
    payload.cid = this.callId ?? payload.cid;
    payload.conv = this.conversation ?? payload.conv;
    this.backend({ kind: "send", to, payload });
  }

  // ---- call flow ----

  pending: ((r: { conversation: string; members: Member[] } | null) => void) | null = null;

  async start(channelId: string, video = false) {
    if (this.state !== "idle") return this.render();
    const info = await new Promise<{ conversation: string; members: Member[] } | null>((resolve) => {
      this.pending = resolve;
      this.backend({ kind: "start", channelId });
      setTimeout(() => resolve(null), 5000);
    });
    this.pending = null;
    if (!info || !info.members.length) return this.toast("Personne à appeler dans cette conversation.");
    info.members.forEach((m) => this.names.set(m.key, m.name));
    this.callId = Array.from(crypto.getRandomValues(new Uint8Array(8)), (b) => b.toString(16).padStart(2, "0")).join("");
    this.conversation = info.conversation;
    this.caller = info.members[0].key;
    this.video = video;
    this.state = "outgoing";
    await this.prepareMic();
    if (video) await this.setCamera(true);
    info.members.slice(0, MAX_PARTICIPANTS).forEach((m) => this.send(m.key, { t: "call-ring", video }));
    const ringing = this.callId;
    this.ringTimer = window.setTimeout(() => {
      if (this.callId === ringing && this.state === "outgoing") this.hangUp();
    }, 45_000);
    this.render();
  }

  async accept() {
    if (this.state !== "incoming" || !this.caller) return;
    this.state = "connecting";
    await this.prepareMic();
    if (this.video) await this.setCamera(true);
    await this.connectTo(this.caller);
    this.send(this.caller, { t: "call-join" });
    this.render();
  }

  hangUp() {
    for (const peer of this.links.keys()) this.send(peer, { t: "call-leave" });
    if ((this.state === "outgoing" || this.state === "incoming") && this.caller) {
      this.send(this.caller, { t: "call-leave" });
      if (this.state === "outgoing") this.backend({ kind: "leave-all", conversation: this.conversation, cid: this.callId });
    }
    this.cleanUp();
  }

  onBackend(msg: any) {
    if (msg.kind === "started") return this.pending?.(msg);
    if (msg.kind !== "signal") return;
    if (msg.fromName) this.names.set(msg.from, msg.fromName);
    this.onSignal(msg.from, msg.payload).catch((e) => console.warn("[air-call]", e));
  }

  async onSignal(from: string, json: any) {
    const cid = String(json.cid ?? "");
    const conv = String(json.conv ?? "");
    switch (json.t) {
      case "call-ring":
        if (this.state !== "idle") {
          if (cid !== this.callId) this.backend({ kind: "send", to: from, payload: { t: "call-busy", cid, conv } });
          return;
        }
        this.callId = cid;
        this.conversation = conv;
        this.caller = from;
        this.video = !!json.video;
        this.state = "incoming";
        this.render();
        this.notify(`${this.name(from)} vous appelle`);
        return;
      case "call-join": {
        if (cid !== this.callId) return;
        const roster = [...this.links.values()].filter((l) => l.connected && l.peer !== from).map((l) => l.peer);
        this.send(from, { t: "call-roster", peers: roster });
        if (this.state === "outgoing") this.state = "connecting";
        this.render();
        return;
      }
      case "call-roster":
        if (cid !== this.callId) return;
        for (const member of json.peers ?? []) {
          if (!this.links.has(member) && this.links.size < MAX_PARTICIPANTS) await this.connectTo(member);
        }
        return;
      case "call-offer":
        if (cid !== this.callId || this.state === "idle" || this.state === "incoming") return;
        await this.answer(from, json.sdp);
        return;
      case "call-answer":
        if (cid !== this.callId) return;
        await this.links.get(from)?.pc.setRemoteDescription({ type: "answer", sdp: json.sdp });
        return;
      case "call-leave":
      case "call-busy":
        if (cid !== this.callId) return;
        if (this.state === "incoming" && from === this.caller) return this.cleanUp();
        this.removeLink(from);
        return;
    }
  }

  async connectTo(peer: string) {
    const link = this.createLink(peer);
    const offer = await link.pc.createOffer();
    await link.pc.setLocalDescription(offer);
    await this.gathered(link.pc);
    this.send(peer, { t: "call-offer", sdp: link.pc.localDescription?.sdp ?? offer.sdp });
  }

  async answer(peer: string, sdp: string) {
    const link = this.createLink(peer, false);
    await link.pc.setRemoteDescription({ type: "offer", sdp });
    // the offer creates the transceivers: attach our tracks to them
    const all = link.pc.getTransceivers();
    const audio = all.find((t) => t.receiver.track.kind === "audio");
    const video = all.find((t) => t.receiver.track.kind === "video");
    if (audio) {
      link.audio = audio;
      audio.direction = "sendrecv";
      await audio.sender.replaceTrack(this.mic);
    }
    if (video) {
      link.video = video;
      video.direction = "sendrecv";
      await video.sender.replaceTrack(this.screen ?? this.camera);
    }
    const answer = await link.pc.createAnswer();
    await link.pc.setLocalDescription(answer);
    await this.gathered(link.pc);
    this.applyBitrate(link);
    this.send(peer, { t: "call-answer", sdp: link.pc.localDescription?.sdp ?? answer.sdp });
  }

  gathered(pc: RTCPeerConnection) {
    return new Promise<void>((resolve) => {
      if (pc.iceGatheringState === "complete") return resolve();
      const done = () => pc.iceGatheringState === "complete" && resolve();
      pc.addEventListener("icegatheringstatechange", done);
      setTimeout(resolve, 6000);
    });
  }

  createLink(peer: string, offering = true): Link {
    this.removeLink(peer, false);
    const pc = new RTCPeerConnection({ iceServers: ICE });
    const stream = new MediaStream();
    // the one making the offer adds the audio then video lines (same order as the phone app)
    const audio = (offering ? pc.addTransceiver("audio", { direction: "sendrecv" }) : null) as RTCRtpTransceiver;
    const video = (offering ? pc.addTransceiver("video", { direction: "sendrecv" }) : null) as RTCRtpTransceiver;
    if (offering) {
      if (this.mic) audio.sender.replaceTrack(this.mic);
      const outgoing = this.screen ?? this.camera;
      if (outgoing) video.sender.replaceTrack(outgoing);
    }
    const link: Link = { peer, pc, connected: false, video, audio, stream };
    pc.ontrack = (e) => {
      stream.addTrack(e.track);
      this.render();
    };
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === "connected") {
        link.connected = true;
        this.state = "active";
        clearTimeout(this.ringTimer);
        this.applyBitrate(link);
        this.render();
      } else if (pc.connectionState === "failed" || pc.connectionState === "closed") {
        if (this.links.get(peer) === link) this.removeLink(peer);
      }
    };
    this.links.set(peer, link);
    return link;
  }

  removeLink(peer: string, notify = true) {
    const link = this.links.get(peer);
    if (!link) return;
    this.links.delete(peer);
    try {
      link.pc.close();
    } catch {
      // ignore
    }
    if (notify && !this.links.size && this.state === "active") return this.cleanUp();
    this.render();
  }

  // ---- media ----

  async prepareMic() {
    if (this.mic) return;
    try {
      const s = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
      this.mic = s.getAudioTracks()[0];
      this.mic.enabled = this.micOn;
    } catch {
      this.toast("Micro indisponible : vous êtes en écoute seulement.");
    }
  }

  toggleMic() {
    this.micOn = !this.micOn;
    if (this.mic) this.mic.enabled = this.micOn;
    this.render();
  }

  async setCamera(on: boolean) {
    if (!on) {
      this.camera?.stop();
      this.camera = null;
    } else {
      try {
        const s = await navigator.mediaDevices.getUserMedia({
          video: { width: { ideal: this.quality.width }, height: { ideal: this.quality.height }, frameRate: { ideal: this.quality.fps } },
        });
        this.camera = s.getVideoTracks()[0];
      } catch {
        this.toast("Caméra indisponible.");
      }
    }
    await this.updateVideo();
  }

  async toggleScreen() {
    if (this.screen) {
      this.screen.stop();
      this.screen = null;
    } else {
      try {
        const s = await navigator.mediaDevices.getDisplayMedia({
          video: { width: { ideal: this.quality.width }, height: { ideal: this.quality.height }, frameRate: { ideal: this.quality.fps } },
          audio: false,
        });
        this.screen = s.getVideoTracks()[0];
        this.screen.contentHint = "detail";
        this.screen.onended = () => {
          this.screen = null;
          this.updateVideo();
        };
      } catch {
        return;
      }
    }
    await this.updateVideo();
  }

  async changeQuality(index: number) {
    this.quality = QUALITIES[index] ?? QUALITIES[0];
    const track = this.screen ?? this.camera;
    if (track) {
      try {
        await track.applyConstraints({ width: { ideal: this.quality.width }, height: { ideal: this.quality.height }, frameRate: { ideal: this.quality.fps } });
      } catch {
        // keep the current resolution
      }
    }
    this.links.forEach((l) => this.applyBitrate(l));
    this.render();
  }

  async updateVideo() {
    const track = this.screen ?? this.camera;
    for (const link of this.links.values()) await link.video?.sender.replaceTrack(track);
    this.render();
  }

  applyBitrate(link: Link) {
    try {
      if (!link.video) return;
      const params = link.video.sender.getParameters();
      if (!params.encodings?.length) params.encodings = [{}];
      params.encodings[0].maxBitrate = this.quality.bitrate;
      params.encodings[0].maxFramerate = this.quality.fps;
      link.video.sender.setParameters(params).catch(() => {});
    } catch {
      // not negotiated yet
    }
  }

  cleanUp() {
    clearTimeout(this.ringTimer);
    for (const link of this.links.values()) {
      try {
        link.pc.close();
      } catch {
        // ignore
      }
    }
    this.links.clear();
    [this.mic, this.camera, this.screen].forEach((t) => t?.stop());
    this.mic = this.camera = this.screen = null;
    this.state = "idle";
    this.callId = this.conversation = this.caller = null;
    this.render();
  }

  // ---- interface ----

  name(key: string | null) {
    return (key && this.names.get(key)) || "Quelqu'un";
  }

  notify(text: string) {
    try {
      if (document.hidden && Notification.permission === "granted") new Notification("AIR Chat", { body: text });
    } catch {
      // ignore
    }
  }

  toast(text: string) {
    const t = document.createElement("div");
    t.textContent = text;
    Object.assign(t.style, {
      position: "fixed",
      bottom: "24px",
      left: "50%",
      transform: "translateX(-50%)",
      background: "#222",
      color: "#fff",
      padding: "10px 16px",
      borderRadius: "12px",
      zIndex: "100000",
      font: "14px Inter, sans-serif",
    });
    document.body.appendChild(t);
    setTimeout(() => t.remove(), 3500);
  }

  button(label: string, onClick: () => void, colour = "#333") {
    const b = document.createElement("button");
    b.textContent = label;
    Object.assign(b.style, {
      background: colour,
      color: "#fff",
      border: "none",
      borderRadius: "22px",
      padding: "10px 16px",
      cursor: "pointer",
      font: "600 14px Inter, sans-serif",
    });
    b.onclick = onClick;
    return b;
  }

  render() {
    if (this.state === "idle") {
      this.ui?.remove();
      this.ui = null;
      return;
    }
    if (!this.ui) {
      this.ui = document.createElement("div");
      Object.assign(this.ui.style, {
        position: "fixed",
        inset: "0",
        background: "rgba(10,10,14,0.92)",
        zIndex: "99999",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        justifyContent: "center",
        gap: "16px",
        color: "#fff",
        font: "15px Inter, sans-serif",
      });
      document.body.appendChild(this.ui);
    }
    const ui = this.ui;
    ui.replaceChildren();

    const title = document.createElement("div");
    title.style.font = "700 20px Inter, sans-serif";
    title.textContent =
      this.state === "incoming"
        ? `${this.name(this.caller)} vous appelle${this.video ? " (vidéo)" : ""}`
        : this.state === "outgoing"
          ? `Appel de ${this.name(this.caller)}…`
          : this.state === "connecting"
            ? "Connexion…"
            : `En appel · ${this.links.size + 1} participant${this.links.size ? "s" : ""}`;
    ui.appendChild(title);

    const grid = document.createElement("div");
    Object.assign(grid.style, {
      display: "grid",
      gridTemplateColumns: `repeat(${Math.min(3, Math.max(1, this.links.size + 1))}, minmax(0, 1fr))`,
      gap: "10px",
      width: "min(1200px, 92vw)",
      maxHeight: "70vh",
    });
    const tile = (stream: MediaStream | null, label: string, muted: boolean) => {
      const box = document.createElement("div");
      Object.assign(box.style, { position: "relative", background: "#1b1b22", borderRadius: "14px", overflow: "hidden", aspectRatio: "16/9" });
      if (stream && stream.getVideoTracks().some((t) => t.readyState === "live")) {
        const v = document.createElement("video");
        v.autoplay = true;
        v.playsInline = true;
        v.muted = muted;
        v.srcObject = stream;
        Object.assign(v.style, { width: "100%", height: "100%", objectFit: "contain", background: "#000" });
        box.appendChild(v);
      } else if (stream && !muted) {
        const a = document.createElement("audio");
        a.autoplay = true;
        a.srcObject = stream;
        box.appendChild(a);
      }
      const n = document.createElement("div");
      n.textContent = label;
      Object.assign(n.style, { position: "absolute", left: "10px", bottom: "8px", background: "rgba(0,0,0,0.55)", padding: "3px 8px", borderRadius: "8px" });
      box.appendChild(n);
      return box;
    };
    if (this.state !== "incoming") {
      const local = this.screen ?? this.camera;
      grid.appendChild(tile(local ? new MediaStream([local]) : null, "Vous", true));
      for (const link of this.links.values()) {
        const t = tile(link.stream, this.name(link.peer), false);
        // audio must play even when the video element is shown
        if (link.stream.getVideoTracks().length) {
          const a = document.createElement("audio");
          a.autoplay = true;
          a.srcObject = new MediaStream(link.stream.getAudioTracks());
          t.appendChild(a);
        }
        grid.appendChild(t);
      }
      ui.appendChild(grid);
    }

    const bar = document.createElement("div");
    Object.assign(bar.style, { display: "flex", gap: "10px", flexWrap: "wrap", justifyContent: "center" });
    if (this.state === "incoming") {
      bar.appendChild(this.button("Accepter", () => this.accept(), "#1e9e4a"));
      bar.appendChild(this.button("Refuser", () => this.hangUp(), "#d33"));
    } else {
      bar.appendChild(this.button(this.micOn ? "Couper le micro" : "Activer le micro", () => this.toggleMic()));
      bar.appendChild(this.button(this.camera ? "Couper la caméra" : "Caméra", () => this.setCamera(!this.camera)));
      bar.appendChild(this.button(this.screen ? "Arrêter le partage" : "Partager l'écran", () => this.toggleScreen()));
      const q = document.createElement("select");
      QUALITIES.forEach((quality, i) => {
        const o = document.createElement("option");
        o.value = String(i);
        o.textContent = quality.label;
        o.selected = quality === this.quality;
        q.appendChild(o);
      });
      Object.assign(q.style, { borderRadius: "22px", padding: "10px", background: "#333", color: "#fff", border: "none" });
      q.onchange = () => this.changeQuality(Number(q.value));
      bar.appendChild(q);
      bar.appendChild(this.button("Raccrocher", () => this.hangUp(), "#d33"));
    }
    ui.appendChild(bar);
  }
}

const airCall = new AirCall();
(window as unknown as { airCall: AirCall }).airCall = airCall;
airCall.connect();
export default airCall;
