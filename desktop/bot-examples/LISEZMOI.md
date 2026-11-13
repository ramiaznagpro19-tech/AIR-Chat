# Bots AIR Chat avec du code (Python ou HTML)

Un bot AIR Chat est hébergé par **AIR Chat pour PC** : ouvrez AIR Chat sur le PC, puis
**Paramètres → Mes bots** → créez le bot, ajoutez-le à un serveur (« Inviter le bot ») et copiez
son **jeton**.

- `bot.py` : bot en Python (`pip install requests websocket-client`).
- `bot.html` : bot dans une page web, à ouvrir dans un navigateur sur le même PC.

Adresse de l'API : `http://127.0.0.1:47821/api`, en-tête `x-bot-token: <jeton>`.
Événements en direct : `ws://127.0.0.1:47821/events`, premier message
`{"type":"Authenticate","token":"<jeton>"}` (format compatible avec les bibliothèques de bots Stoat/Revolt).

**Ce qu'un bot peut faire** : lire les messages des serveurs et groupes où il a été ajouté,
envoyer des messages (avec réponses et fichiers), réagir, modifier et supprimer ses messages,
lire les membres.

**Ce qu'un bot ne peut pas faire** : modifier un serveur, un salon, les rôles, les permissions,
les invitations, bannir ou expulser, ni modifier l'application. Toute tentative renvoie une erreur 403.

Sans code, un bot répond aussi à `!aide`, `!ping`… et se règle depuis un salon
(`!commande`, `!bienvenue`, `!auto`, `!interdire`) — sur PC comme sur téléphone.
