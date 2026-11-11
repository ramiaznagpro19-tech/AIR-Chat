package chat.stoat.air.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.stoat.R
import chat.stoat.air.AirAccount
import chat.stoat.air.AirNetwork
import chat.stoat.api.settings.LoadedSettings
import chat.stoat.core.designsystem.theme.StoatTheme
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.airnet_err_network
import chat.stoat.shared.resources.airnet_err_not_found
import chat.stoat.shared.resources.airnet_err_password_invalid
import chat.stoat.shared.resources.airnet_err_taken
import chat.stoat.shared.resources.airnet_err_username_invalid
import chat.stoat.shared.resources.airnet_err_wrong_password
import chat.stoat.shared.resources.airnet_login
import chat.stoat.shared.resources.airnet_password
import chat.stoat.shared.resources.airnet_register
import chat.stoat.shared.resources.airnet_rule_digits
import chat.stoat.shared.resources.airnet_rule_letters
import chat.stoat.shared.resources.airnet_rule_specials
import chat.stoat.shared.resources.airnet_tagline
import chat.stoat.shared.resources.airnet_username
import chat.stoat.shared.resources.airnet_username_rules
import chat.stoat.shared.resources.airnet_working
import chat.stoat.shared.resources.ic_check_24dp
import chat.stoat.shared.resources.ic_close_24dp
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.res.painterResource as androidPainterResource

/**
 * AIR Chat: root of the app. Shows the account screen, or the conversations once logged in.
 */
@Composable
fun AirApp() {
    StoatTheme(
        requestedTheme = LoadedSettings.theme,
        requestedUserInterfaceFont = LoadedSettings.font,
    ) {
        val session by AirAccount.session.collectAsState()
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            val current = session
            if (current == null) {
                AirAuthScreen()
            } else {
                LaunchedEffect(current.publicKey) { AirNetwork.start(current) }
                AirHome(onLogout = {
                    AirNetwork.stop()
                    AirAccount.logout()
                })
            }
        }
    }
}

@Composable
fun AirLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = androidPainterResource(R.drawable.air_logo),
        contentDescription = "AIR Chat",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(MaterialTheme.shapes.extraLarge)
    )
}

/** Round avatar with the first letter of a username. */
@Composable
fun AirAvatar(name: String, size: Dp = 40.dp, online: Boolean = false) {
    val palette = listOf(
        Color(0xFFFE1A1D), Color(0xFF21B94D), Color(0xFF166AFF),
        Color(0xFF8E44AD), Color(0xFFE67E22), Color(0xFF16A085)
    )
    val colour = palette[(name.hashCode() and 0x7fffffff) % palette.size]
    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(colour)
        ) {
            Text(
                text = name.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp
            )
        }
        if (online) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size / 3.5f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF21B94D))
            )
        }
    }
}

@Composable
private fun AirAuthScreen() {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<StringResource?>(null) }
    val registering = tab == 1
    val check = AirAccount.passwordProblems(password)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        AirLogo(size = 132.dp)
        Spacer(Modifier.height(16.dp))
        Text("AIR Chat", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            stringResource(Res.string.airnet_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0; error = null }, text = { Text(stringResource(Res.string.airnet_login)) })
            Tab(selected = tab == 1, onClick = { tab = 1; error = null }, text = { Text(stringResource(Res.string.airnet_register)) })
        }
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it.trim().lowercase().take(20); error = null },
            label = { Text(stringResource(Res.string.airnet_username)) },
            singleLine = true,
            enabled = !working,
            supportingText = {
                if (registering) Text(stringResource(Res.string.airnet_username_rules))
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it.take(128); error = null },
            label = { Text(stringResource(Res.string.airnet_password)) },
            singleLine = true,
            enabled = !working,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        if (registering) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PasswordRule(check.letters, Res.string.airnet_rule_letters)
                PasswordRule(check.digits, Res.string.airnet_rule_digits)
                PasswordRule(check.specials, Res.string.airnet_rule_specials)
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(24.dp))
        Button(
            enabled = !working && AirAccount.isUsernameValid(username) &&
                    (if (registering) check.isValid else password.isNotEmpty()),
            onClick = {
                working = true
                error = null
                scope.launch {
                    val result = if (registering) AirAccount.register(username, password)
                    else AirAccount.login(username, password)
                    working = false
                    error = when (result) {
                        is AirAccount.Result.Success -> null
                        AirAccount.Result.UsernameInvalid -> Res.string.airnet_err_username_invalid
                        AirAccount.Result.PasswordInvalid -> Res.string.airnet_err_password_invalid
                        AirAccount.Result.UsernameTaken -> Res.string.airnet_err_taken
                        AirAccount.Result.WrongPassword -> Res.string.airnet_err_wrong_password
                        AirAccount.Result.AccountNotFound -> Res.string.airnet_err_not_found
                        AirAccount.Result.NetworkError -> Res.string.airnet_err_network
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(12.dp))
                Text(stringResource(Res.string.airnet_working))
            } else {
                Text(stringResource(if (registering) Res.string.airnet_register else Res.string.airnet_login))
            }
        }
    }
}

@Composable
private fun PasswordRule(ok: Boolean, text: StringResource) {
    val colour = if (ok) Color(0xFF21B94D) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(if (ok) Res.drawable.ic_check_24dp else Res.drawable.ic_close_24dp),
            contentDescription = null,
            tint = colour,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(text), color = colour, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Small surface used for informational cards. */
@Composable
fun AirInfoCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

internal fun Modifier.clickableIf(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.clickable(onClick = onClick) else this
