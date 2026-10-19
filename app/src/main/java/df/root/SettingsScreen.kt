package df.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(
    state: DfrootState,
    onRun: () -> Unit,
    onAutoEnabledChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.section_status),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.running) {
                        LoadingIndicator()
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = statusText(state),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                if (state.suProbed && !state.rootLive && state.chainRan) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.grant_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Button(
            onClick = onRun,
            enabled = !state.running,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_shield),
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(
                    if (state.running) R.string.action_running else R.string.action_jailbreak
                ),
            )
        }

        if (state.running && !state.rootLive) {
            LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.action_auto_root),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.autoEnabled, onCheckedChange = onAutoEnabledChange)
        }
    }
}

@Composable
private fun statusText(state: DfrootState): String {
    val rootState = when {
        !state.suProbed -> stringResource(R.string.root_checking)
        state.rootLive -> stringResource(R.string.root_live)
        state.chainRan -> stringResource(R.string.root_not_granted)
        else -> stringResource(R.string.root_inactive)
    }
    val su = if (state.rootLive) stringResource(R.string.su_ok) else state.suDetail
    val manager = if (state.managerInstalled) {
        stringResource(R.string.manager_present, state.managerVersion)
    } else {
        stringResource(R.string.manager_absent)
    }

    val kernel = stringResource(R.string.status_kernel)
    val device = stringResource(R.string.status_device)
    val root = stringResource(R.string.status_root)
    val suLabel = stringResource(R.string.status_su)
    val managerLabel = stringResource(R.string.status_manager)

    return buildString {
        appendLine("$kernel: ${state.kernelVersion}")
        appendLine("$device: ${state.deviceModel}")
        appendLine("$root: $rootState")
        appendLine("$suLabel: $su")
        append("$managerLabel: $manager")
    }
}
