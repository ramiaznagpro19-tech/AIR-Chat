package chat.stoat.sheets

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import chat.stoat.composables.chat.displayNameInChannel
import chat.stoat.composables.generic.SheetButton
import chat.stoat.composables.generic.SheetHeaderPadding
import chat.stoat.e2ee.AirE2EE
import chat.stoat.internals.extensions.TransparentListItemColours
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.air_e2ee_devices
import chat.stoat.shared.resources.air_e2ee_disabled_description
import chat.stoat.shared.resources.air_e2ee_enabled_description
import chat.stoat.shared.resources.air_e2ee_keys_count
import chat.stoat.shared.resources.air_e2ee_keys_requested
import chat.stoat.shared.resources.air_e2ee_no_devices
import chat.stoat.shared.resources.air_e2ee_no_key
import chat.stoat.shared.resources.air_e2ee_request_keys
import chat.stoat.shared.resources.air_e2ee_safety_number_hint
import chat.stoat.shared.resources.air_e2ee_this_device
import chat.stoat.shared.resources.air_e2ee_title
import chat.stoat.shared.resources.air_e2ee_toggle
import chat.stoat.shared.resources.air_e2ee_your_safety_number
import chat.stoat.shared.resources.ic_devices_24dp
import chat.stoat.shared.resources.ic_key_24dp
import chat.stoat.shared.resources.ic_lock_24dp
import chat.stoat.shared.resources.ic_shield_lock_24dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * AIR Chat: end-to-end encryption details for a conversation.
 */
@Composable
fun AirE2EESheet(channelId: String) {
    val context = LocalContext.current
    // Recompose when keys, devices or settings change
    @Suppress("UNUSED_VARIABLE")
    val revision = AirE2EE.revision

    val enabled = AirE2EE.isEnabled(channelId)
    val keyCount = AirE2EE.keyCount(channelId)
    val devices = AirE2EE.devices(channelId)
    val keysRequestedText = stringResource(Res.string.air_e2ee_keys_requested)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        SheetHeaderPadding {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_shield_lock_24dp),
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(Res.string.air_e2ee_title),
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }

        Text(
            text = stringResource(
                if (enabled) Res.string.air_e2ee_enabled_description
                else Res.string.air_e2ee_disabled_description
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        SheetButton(
            headlineContent = { Text(stringResource(Res.string.air_e2ee_toggle)) },
            leadingContent = {
                Icon(painterResource(Res.drawable.ic_lock_24dp), contentDescription = null)
            },
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { AirE2EE.setEnabled(channelId, it) }
                )
            },
            onClick = { AirE2EE.setEnabled(channelId, !enabled) }
        )

        SheetButton(
            headlineContent = { Text(stringResource(Res.string.air_e2ee_request_keys)) },
            supportingContent = {
                Text(
                    if (keyCount > 0) stringResource(Res.string.air_e2ee_keys_count, keyCount)
                    else stringResource(Res.string.air_e2ee_no_key)
                )
            },
            leadingContent = {
                Icon(painterResource(Res.drawable.ic_key_24dp), contentDescription = null)
            },
            onClick = {
                AirE2EE.requestKeys(channelId)
                Toast.makeText(context, keysRequestedText, Toast.LENGTH_SHORT).show()
            }
        )

        Spacer(Modifier.height(8.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(Res.string.air_e2ee_your_safety_number),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = AirE2EE.ownSafetyNumber(),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                Text(
                    text = stringResource(Res.string.air_e2ee_safety_number_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(Res.string.air_e2ee_devices),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
        )

        val others = devices.filterKeys { it != AirE2EE.ownDeviceFingerprint }
        if (others.isEmpty()) {
            Text(
                text = stringResource(Res.string.air_e2ee_no_devices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)
            )
        }
        others.forEach { (fingerprint, userId) ->
            ListItem(
                colors = TransparentListItemColours,
                modifier = Modifier.padding(horizontal = 16.dp),
                leadingContent = {
                    Icon(
                        painterResource(Res.drawable.ic_devices_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                headlineContent = { Text(displayNameInChannel(userId, channelId)) },
                supportingContent = {
                    Text(
                        text = AirE2EE.safetyNumberOf(fingerprint),
                        fontFamily = FontFamily.Monospace
                    )
                }
            )
        }
        ListItem(
            colors = TransparentListItemColours,
            modifier = Modifier.padding(horizontal = 16.dp),
            leadingContent = {
                Icon(painterResource(Res.drawable.ic_devices_24dp), contentDescription = null)
            },
            headlineContent = { Text(stringResource(Res.string.air_e2ee_this_device)) },
            supportingContent = {
                Text(text = AirE2EE.ownSafetyNumber(), fontFamily = FontFamily.Monospace)
            }
        )
    }
}
