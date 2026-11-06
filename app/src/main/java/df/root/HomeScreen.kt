@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package df.root

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    state: DfrootState,
    onRun: () -> Unit,
    onAutoEnabledChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val status = when {
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
    val statusColor = when {
        state.rootLive -> colors.primary
        state.chainRan && state.suProbed -> colors.error
        else -> colors.onSurface
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.headlineLarge,
            color = statusColor,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )

        if (state.running) {
            Spacer(Modifier.height(20.dp))
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onRun,
            enabled = !state.running,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Text(
                text = stringResource(
                    when {
                        state.running -> R.string.action_running
                        state.chainRan -> R.string.action_jailbreak_again
                        else -> R.string.action_jailbreak
                    }
                ),
            )
        }

        if (state.suProbed && !state.rootLive && state.chainRan) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.grant_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
            )
        }

        Spacer(Modifier.height(28.dp))
        Surface(
            color = colors.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Fact(stringResource(R.string.stat_kernel), compactKernel(state.kernelVersion).ifEmpty { "—" })
                Fact(stringResource(R.string.stat_device), state.deviceModel.ifEmpty { "—" })
                Fact(
                    stringResource(R.string.stat_su),
                    if (state.rootLive) stringResource(R.string.su_ok) else state.suDetail.ifEmpty { "—" },
                )
                Fact(
                    stringResource(R.string.stat_manager),
                    if (state.managerInstalled) {
                        state.managerVersion.ifEmpty { stringResource(R.string.root_live) }
                    } else {
                        stringResource(R.string.manager_absent)
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.action_auto_root)) },
                    supportingContent = {
                        Text(stringResource(R.string.home_auto_note))
                    },
                    trailingContent = {
                        Switch(
                            checked = state.autoEnabled,
                            onCheckedChange = onAutoEnabledChange,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun Fact(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                text = value,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun compactKernel(version: String): String {
    if (version.isEmpty()) return version
    val parts = version.split('-')
    return if (parts.size > 3) parts.take(3).joinToString("-") else version
}
