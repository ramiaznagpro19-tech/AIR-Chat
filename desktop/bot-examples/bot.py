"""
Bot AIR Chat en Python.

1. Dans AIR Chat pour PC : Paramètres > Mes bots > créez un bot, ajoutez-le à un serveur,
   puis copiez son jeton.
2. pip install requests websocket-client
3. AIR_BOT_TOKEN=le_jeton python bot.py   (ou collez le jeton ci-dessous)

Le bot tourne sur le même PC qu'AIR Chat (qui doit rester ouvert). Il peut lire et écrire des
messages, répondre, réagir, modifier et supprimer ses messages. Il ne peut pas modifier les
serveurs, les salons, les rôles ni l'application.
"""
import json
import os
import threading
import time

import requests
import websocket

TOKEN = os.environ.get("AIR_BOT_TOKEN", "COLLEZ_LE_JETON_ICI")
API = "http://127.0.0.1:47821/api"
HEADERS = {"x-bot-token": TOKEN, "Content-Type": "application/json"}

me = requests.get(f"{API}/users/@me", headers=HEADERS).json()
print("Connecté en tant que", me["username"])


def send(channel, content, reply_to=None):
    body = {"content": content}
    if reply_to:
        body["replies"] = [{"id": reply_to, "mention": False}]
    requests.post(f"{API}/channels/{channel}/messages", headers=HEADERS, data=json.dumps(body))


def on_message(ws, raw):
    event = json.loads(raw)
    if event.get("type") != "Message" or event.get("author") == me["_id"]:
        return
    text = event.get("content", "")
    if text == "!bonjour":
        send(event["channel"], "Bonjour depuis Python 🐍", reply_to=event["_id"])
    elif text.startswith("!echo "):
        send(event["channel"], text[6:])
    elif text == "!heure":
        send(event["channel"], time.strftime("Il est %H:%M"))


def keep_alive(ws):
    while True:
        time.sleep(20)
        ws.send(json.dumps({"type": "Ping", "data": 0}))


ws = websocket.WebSocketApp(
    "ws://127.0.0.1:47821/events",
    on_open=lambda ws: (ws.send(json.dumps({"type": "Authenticate", "token": TOKEN})),
                        threading.Thread(target=keep_alive, args=(ws,), daemon=True).start()),
    on_message=on_message,
)
ws.run_forever()
