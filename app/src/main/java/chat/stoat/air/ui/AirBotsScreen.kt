package chat.stoat.air.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import chat.stoat.air.AirBots
import chat.stoat.air.AirNetwork
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.ic_arrow_back_24dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/** "Mes bots": bots hosted by this device, set up without code. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirBotsScreen(navController: NavController) {
    val bots by AirBots.bots.collectAsState()
    val conversations by AirNetwork.conversations.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mes bots") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(painterResource(Res.drawable.ic_arrow_back_24dp), contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Un bot est un compte AIR Chat avec le badge BOT, hébergé par cet appareil : " +
                        "il répond dans vos serveurs tant qu'AIR Chat est ouvert ici. Pas de code : " +
                        "vous le réglez en lui écrivant des commandes dans un salon.",
                style = MaterialTheme.typography.bodyMedium
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Créer un bot", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '.' || c == '_' }.take(20) },
                        label = { Text("Nom d'utilisateur du bot") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = !busy && name.length >= 3,
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                message = when (val r = AirBots.create(name)) {
                                    is AirBots.CreateResult.Success -> {
                                        name = ""
                                        "Bot ${r.bot.username} créé. Ajoutez-le à un serveur ci-dessous."
                                    }

                                    is AirBots.CreateResult.Failure -> r.message
                                }
                                busy = false
                            }
                        }
                    ) { Text(if (busy) "Création…" else "Créer") }
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            bots.forEach { bot ->
                var menu by remember(bot.publicKey) { mutableStateOf(false) }
                var confirmDelete by remember(bot.publicKey) { mutableStateOf(false) }
                val servers = AirBots.servers()
                val joined = servers.filter { bot.publicKey in it.members }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${bot.username}  ·  BOT", fontWeight = FontWeight.Bold)
                        Text(
                            if (joined.isEmpty()) "Dans aucun serveur pour l'instant."
                            else "Serveurs : " + joined.joinToString(", ") { it.name },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column {
                                OutlinedButton(onClick = { menu = true }, enabled = servers.isNotEmpty()) {
                                    Text("Ajouter à un serveur")
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    servers.filter { bot.publicKey !in it.members }.forEach { server ->
                                        DropdownMenuItem(
                                            text = { Text(server.name) },
                                            onClick = {
                                                menu = false
                                                message = if (AirBots.addToServer(bot.publicKey, server.id)) {
                                                    "${bot.username} a rejoint ${server.name}. Écrivez ${bot.prefix}aide dans un salon."
                                                } else "Impossible d'ajouter le bot à ce serveur."
                                            }
                                        )
                                    }
                                }
                            }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(bot.username)) }) {
                                Text("Copier le nom")
                            }
                        }
                        if (confirmDelete) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { AirBots.delete(bot.publicKey); confirmDelete = false }) { Text("Supprimer") }
                                TextButton(onClick = { confirmDelete = false }) { Text("Annuler") }
                            }
                        } else {
                            TextButton(onClick = { confirmDelete = true }) { Text("Supprimer le bot") }
                        }
                    }
                }
                // recompose when conversations change
                conversations.size
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Commandes des bots", fontWeight = FontWeight.Bold)
                    Text(
                        "Pour tout le monde : !aide, !ping, !info, !des, !choisir a | b, et les commandes que vous créez.\n\n" +
                                AirBots.HELP_OWNER,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
