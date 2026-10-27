@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package df.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val LiveGreen = Color(0xFF2ED47A)
private val WarnAmber = Color(0xFFFFC24B)
private val IdleGrey = Color(0xFFB9AECB)

@Composable
fun HomeScreen(
    state: DfrootState,
    onRun: () -> Unit,
    onAutoEnabledChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()
        StatusHero(state = state, onRun = onRun)

        AnimatedVisibility(visible = state.running) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.action_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.suProbed && !state.rootLive && state.chainRan) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        text = stringResource(R.string.home_grant_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.grant_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        WideStat(
            icon = Icons.Rounded.Memory,
            label = stringResource(R.string.stat_kernel),
            value = state.kernelVersion.ifEmpty { "—" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.PhoneAndroid,
                label = stringResource(R.string.stat_device),
                value = state.deviceModel.ifEmpty { "—" },
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Rounded.Terminal,
                label = stringResource(R.string.stat_su),
                value = if (state.rootLive) stringResource(R.string.su_ok) else state.suDetail,
                valueColor = if (state.rootLive) LiveGreen else MaterialTheme.colorScheme.onSurface,
            )
        }
        WideStat(
            icon = Icons.Rounded.VerifiedUser,
            label = stringResource(R.string.stat_manager),
            value = if (state.managerInstalled) {
                state.managerVersion.ifEmpty { stringResource(R.string.root_live) }
            } else {
                stringResource(R.string.manager_absent)
            },
        )

        AutoRootCard(
            checked = state.autoEnabled,
            onCheckedChange = onAutoEnabledChange,
        )

        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.home_footer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = stringResource(R.string.home_kicker),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.home_hero_title),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_hero_sub),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusHero(state: DfrootState, onRun: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val big = when {
        !state.suProbed -> stringResource(R.string.home_big_checking)
        state.rootLive -> stringResource(R.string.home_big_live)
        else -> stringResource(R.string.home_big_idle)
    }
    val detail = when {
        !state.suProbed -> stringResource(R.string.root_checking)
        state.rootLive -> stringResource(R.string.su_ok)
        state.chainRan -> stringResource(R.string.home_big_installed)
        else -> stringResource(R.string.root_inactive)
    }
    val dotColor = when {
        !state.suProbed -> colors.onPrimary.copy(alpha = 0.6f)
        state.rootLive -> LiveGreen
        state.chainRan -> WarnAmber
        else -> IdleGrey
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "dot",
    )

    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(228.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .background(colors.primary)
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 54.dp, y = (-52).dp)
                    .size(210.dp)
                    .rotate(16f)
                    .background(colors.tertiary.copy(alpha = 0.42f), MaterialShapes.Cookie9Sided.toShape())
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-54).dp, y = 46.dp)
                    .size(160.dp)
                    .rotate(-12f)
                    .background(colors.secondary.copy(alpha = 0.32f), MaterialShapes.Clover4Leaf.toShape())
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!state.suProbed) {
                        LoadingIndicator(
                            modifier = Modifier.size(12.dp),
                            color = colors.onPrimary,
                        )
                    } else {
                        Box(
                            Modifier
                                .size(9.dp)
                                .alpha(if (state.rootLive) dotAlpha else 1f)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = stringResource(R.string.home_status_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onPrimary.copy(alpha = 0.72f),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = big,
                    style = MaterialTheme.typography.displayLarge,
                    color = colors.onPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onPrimary.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        MediumExtendedFloatingActionButton(
            onClick = onRun,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = (-14).dp, y = 24.dp),
            containerColor = colors.tertiaryContainer,
            contentColor = colors.onTertiaryContainer,
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(
                    when {
                        state.running -> R.string.action_running
                        state.chainRan -> R.string.action_jailbreak_again
                        else -> R.string.action_jailbreak
                    }
                ),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    Spacer(Modifier.height(26.dp))
}

@Composable
private fun WideStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Surface(
        modifier = modifier.heightIn(min = 112.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AutoRootCard(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoMode,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.action_auto_root),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.home_auto_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
