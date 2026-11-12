package chat.stoat.composables.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.badge_bot_alt
import chat.stoat.shared.resources.badge_masquerade_alt
import chat.stoat.shared.resources.badge_team_member_alt
import chat.stoat.shared.resources.badge_webhook_alt
import chat.stoat.shared.resources.ic_brightness_alert_24dp
import chat.stoat.shared.resources.ic_cloud_24dp
import chat.stoat.shared.resources.ic_hardware_24dp
import chat.stoat.shared.resources.ic_link_24dp
import chat.stoat.shared.resources.ic_smart_toy_24dp
import chat.stoat.shared.resources.ic_verified_24dp__fill
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

enum class InlineBadge {
    Bot,
    Bridge,
    PlatformModeration,
    TeamMember,
    Webhook,
    Verified
}

@Composable
fun InlineBadge(
    badge: InlineBadge,
    modifier: Modifier = Modifier,
    colour: Color = Color.Unspecified
) {
    when (badge) {
        InlineBadge.Bot -> Icon(
            painter = painterResource(Res.drawable.ic_smart_toy_24dp),
            contentDescription = stringResource(Res.string.badge_bot_alt),
            tint = colour,
            modifier = modifier
        )

        InlineBadge.Bridge -> Icon(
            painter = painterResource(Res.drawable.ic_link_24dp),
            contentDescription = stringResource(Res.string.badge_masquerade_alt),
            tint = colour,
            modifier = modifier
        )

        InlineBadge.PlatformModeration -> Icon(
            painter = painterResource(Res.drawable.ic_brightness_alert_24dp),
            contentDescription = stringResource(Res.string.badge_bot_alt),
            tint = colour,
            modifier = modifier
        )

        InlineBadge.TeamMember -> Icon(
            painter = painterResource(Res.drawable.ic_hardware_24dp),
            contentDescription = stringResource(Res.string.badge_team_member_alt),
            tint = colour,
            modifier = modifier
        )

        InlineBadge.Verified -> Icon(
            painter = painterResource(Res.drawable.ic_verified_24dp__fill),
            contentDescription = "Compte vérifié",
            tint = VerifiedBlue,
            modifier = modifier
        )

        InlineBadge.Webhook -> Icon(
            painter = painterResource(Res.drawable.ic_cloud_24dp),
            contentDescription = stringResource(Res.string.badge_webhook_alt),
            tint = colour,
            modifier = modifier.padding(start = 4.dp)
        )
    }
}

@Composable
fun InlineBadges(
    modifier: Modifier = Modifier,
    bot: Boolean = false,
    bridge: Boolean = false,
    platformModeration: Boolean = false,
    teamMember: Boolean = false,
    webhook: Boolean = false,
    verified: Boolean = false,
    colour: Color = Color.Unspecified,
    precedingIfAny: @Composable () -> Unit = {},
    followingIfAny: @Composable () -> Unit = {}
) {
    val hasBadges = bot || bridge || platformModeration || teamMember || verified

    if (hasBadges) {
        precedingIfAny()
    }

    Row {
        if (verified) {
            InlineBadge(
                badge = InlineBadge.Verified,
                modifier = modifier,
                colour = colour
            )
        }
        if (bot) {
            InlineBadge(
                badge = InlineBadge.Bot,
                modifier = modifier,
                colour = colour
            )
        }
        if (bridge) {
            InlineBadge(
                badge = InlineBadge.Bridge,
                modifier = modifier,
                colour = colour
            )
        }
        if (platformModeration) {
            InlineBadge(
                badge = InlineBadge.PlatformModeration,
                modifier = modifier,
                colour = colour
            )
        }
        if (teamMember) {
            InlineBadge(
                badge = InlineBadge.TeamMember,
                modifier = modifier,
                colour = colour
            )
        }
        if (webhook) {
            InlineBadge(
                badge = InlineBadge.Webhook,
                modifier = modifier,
                colour = colour
            )
        }
    }

    if (hasBadges) {
        followingIfAny()
    }
}
