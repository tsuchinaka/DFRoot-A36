@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package df.root

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    state: DfrootState,
    onRun: () -> Unit,
    onAutoEnabledChange: (Boolean) -> Unit,
    onKgEnabledChange: (Boolean) -> Unit,
    onReboot: () -> Unit,
    onOpenManager: () -> Unit,
    onCopySu: () -> Unit,
) {
    val status = LocalStatusColors.current
    val colors = MaterialTheme.colorScheme
    val chain = state.chain
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var confirmReboot by remember { mutableStateOf(false) }

    LaunchedEffect(chain.retryAt, state.running, state.runStartedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val retryIn = if (chain.retryAt > now) chain.retryAt - now else -1L
    val elapsed = if (state.running && state.runStartedAt > 0) {
        (now - state.runStartedAt).coerceAtLeast(0L)
    } else {
        0L
    }

    val tone = when {
        !state.targetOk || chain.needsReboot() -> status.bad
        chain.noAllowlist() || chain.stalled() -> status.warn
        state.rootLive -> status.ok
        else -> colors.onSurface
    }
    val title = when {
        !state.targetOk -> stringResource(R.string.home_big_unsupported)
        state.running -> stringResource(R.string.home_big_running)
        chain.needsReboot() -> stringResource(R.string.chain_needs_reboot_title)
        chain.noAllowlist() -> stringResource(R.string.chain_no_allowlist_title)
        state.rootLive -> stringResource(R.string.home_big_live)
        chain.hasRun() || state.chainRan -> stringResource(R.string.home_big_installed)
        else -> stringResource(R.string.home_big_idle)
    }
    val subtitle = when {
        !state.targetOk -> state.targetDetail.ifEmpty {
            stringResource(R.string.target_unsupported)
        }
        state.running -> state.phase.ifEmpty { stringResource(R.string.action_running) }
        chain.needsReboot() -> stringResource(R.string.chain_needs_reboot_body)
        chain.noAllowlist() -> stringResource(R.string.chain_no_allowlist_body)
        state.rootLive -> stringResource(R.string.su_ok)
        chain.hasRun() -> stringResource(
            R.string.last_pass_value,
            chain.lastRc,
            (chain.lastMs / 1000L).coerceAtLeast(0L),
            shortAction(chain.lastAct),
        )
        !state.suProbed -> stringResource(R.string.root_checking)
        else -> stringResource(R.string.root_inactive)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(tone)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.section_status),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = tone,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        if (state.running) {
            item {
                Surface(
                    color = colors.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = state.phase.ifEmpty { stringResource(R.string.action_running) },
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${elapsed / 1000L}s",
                                style = MaterialTheme.typography.headlineMedium,
                                color = colors.primary,
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        item {
            Button(
                onClick = onRun,
                enabled = !state.running && state.targetOk,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp),
                shape = RoundedCornerShape(22.dp),
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
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        item {
            SectionCard(stringResource(R.string.section_chain)) {
                when {
                    chain.needsReboot() -> {
                        StatusLine(
                            text = stringResource(R.string.chain_needs_reboot_title),
                            color = status.bad,
                        )
                        Text(
                            text = stringResource(R.string.chain_needs_reboot_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        ActionRow {
                            OutlinedButton(onClick = { confirmReboot = true }) {
                                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_reboot))
                            }
                        }
                    }

                    chain.noAllowlist() -> {
                        StatusLine(
                            text = stringResource(R.string.chain_no_allowlist_title),
                            color = status.warn,
                        )
                        Text(
                            text = stringResource(R.string.chain_no_allowlist_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        ActionRow {
                            Button(onClick = onOpenManager) {
                                Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_open_manager))
                            }
                            OutlinedButton(onClick = onCopySu) {
                                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.action_copy_su))
                            }
                        }
                    }

                    else -> {
                        StatusLine(
                            text = if (state.rootLive) {
                                stringResource(R.string.root_live)
                            } else if (state.chainRan) {
                                stringResource(R.string.home_big_installed)
                            } else {
                                stringResource(R.string.root_inactive)
                            },
                            color = if (state.rootLive) status.ok else colors.onSurfaceVariant,
                        )
                        if (retryIn > 0) {
                            Caption(stringResource(R.string.chain_retry_in, formatRetry(retryIn)))
                        } else if (chain.fails == 0 && !chain.hasRun()) {
                            Caption(stringResource(R.string.chain_waiting))
                        }
                        if (chain.fails > 0) {
                            Caption(stringResource(R.string.chain_fails, chain.fails))
                        }
                        if (chain.stalled()) {
                            Caption(
                                text = stringResource(R.string.chain_backed_off),
                                color = status.warn,
                            )
                        }
                    }
                }

                if (chain.hasRun()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = colors.outlineVariant)
                    Spacer(Modifier.height(10.dp))
                    FactRow(
                        label = stringResource(R.string.last_pass_title),
                        value = stringResource(
                            R.string.last_pass_value,
                            chain.lastRc,
                            (chain.lastMs / 1000L).coerceAtLeast(0L),
                            shortAction(chain.lastAct),
                        ),
                    )
                } else if (state.lastPass.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Caption(state.lastPass, monospace = true)
                }
            }
        }

        item {
            SectionCard(stringResource(R.string.section_root_side)) {
                val side = state.rootSide
                if (!side.any()) {
                    Text(
                        text = stringResource(R.string.root_side_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    val known = side.published
                    if (!known) {
                        Caption(stringResource(R.string.root_side_unpublished))
                        Spacer(Modifier.height(8.dp))
                    }
                    DotRow(
                        label = stringResource(R.string.root_side_heartbeat),
                        value = when {
                            !known -> stringResource(R.string.state_unknown)
                            side.heartbeatFresh -> stringResource(R.string.state_fresh, side.heartbeatAgeSec)
                            else -> stringResource(R.string.state_stale)
                        },
                        ok = if (known) side.heartbeatFresh else null,
                    )
                    DotRow(
                        label = stringResource(R.string.root_side_supervisor),
                        value = if (!known) {
                            stringResource(R.string.state_unknown)
                        } else {
                            stringResource(if (side.supervisorAlive) R.string.state_running else R.string.state_absent)
                        },
                        ok = if (known) side.supervisorAlive else null,
                    )
                    DotRow(
                        label = stringResource(R.string.root_side_watchdog),
                        value = if (!known) {
                            stringResource(R.string.state_unknown)
                        } else {
                            stringResource(if (side.watchdogAlive) R.string.state_running else R.string.state_absent)
                        },
                        ok = if (known) side.watchdogAlive else null,
                    )
                    DotRow(
                        label = stringResource(R.string.root_side_module),
                        value = if (!known) {
                            stringResource(R.string.state_unknown)
                        } else {
                            stringResource(if (side.moduleLoaded) R.string.state_loaded else R.string.state_absent)
                        },
                        ok = if (known) side.moduleLoaded else null,
                    )
                    DotRow(
                        label = stringResource(R.string.root_side_mutex),
                        value = stringResource(if (side.mutexHeld) R.string.state_held else R.string.state_absent),
                        ok = !side.mutexHeld || side.supervisorAlive,
                    )
                    DotRow(
                        label = stringResource(R.string.root_side_scripts),
                        value = if (!known) {
                            stringResource(R.string.state_unknown)
                        } else {
                            stringResource(if (side.scriptsInstalled) R.string.state_installed else R.string.state_missing)
                        },
                        ok = if (known) side.scriptsInstalled else null,
                    )
                }
            }
        }

        item {
            SectionCard(stringResource(R.string.section_device)) {
                FactRow(
                    stringResource(R.string.stat_kernel),
                    compactKernel(state.kernelVersion).ifEmpty { "—" },
                )
                FactRow(stringResource(R.string.stat_device), state.deviceModel.ifEmpty { "—" })
                FactRow(
                    stringResource(R.string.stat_su),
                    if (state.rootLive) stringResource(R.string.su_ok)
                    else state.suDetail.ifEmpty { "—" },
                )
                FactRow(
                    stringResource(R.string.stat_manager),
                    if (state.managerInstalled) {
                        listOf(state.managerName, state.managerVersion)
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                            .ifEmpty { stringResource(R.string.root_live) }
                    } else {
                        stringResource(R.string.manager_absent)
                    },
                )
            }
        }

        item {
            SectionCard(stringResource(R.string.section_options)) {
                ToggleRow(
                    title = stringResource(R.string.action_auto_root),
                    subtitle = stringResource(R.string.home_auto_note),
                    checked = state.autoEnabled,
                    onCheckedChange = onAutoEnabledChange,
                )
                HorizontalDivider(
                    color = colors.outlineVariant,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                ToggleRow(
                    title = stringResource(R.string.kg_row),
                    subtitle = kgSubtitle(state),
                    checked = state.kgEnabled,
                    onCheckedChange = onKgEnabledChange,
                )
            }
        }
    }

    if (confirmReboot) {
        AlertDialog(
            onDismissRequest = { confirmReboot = false },
            title = { Text(stringResource(R.string.reboot_confirm_title)) },
            text = { Text(stringResource(R.string.reboot_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReboot = false
                        onReboot()
                    },
                ) { Text(stringResource(R.string.reboot_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReboot = false }) {
                    Text(stringResource(R.string.log_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun DotRow(label: String, value: String, ok: Boolean?) {
    val status = LocalStatusColors.current
    val color = when (ok) {
        true -> status.ok
        false -> status.bad
        null -> MaterialTheme.colorScheme.outline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(color)
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1.2f),
            )
        }
    }
}

@Composable
private fun Caption(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, monospace: Boolean = false) {
    val style = if (monospace) {
        MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    } else {
        MaterialTheme.typography.bodySmall
    }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** Whole row is the switch: one tap target, one announcement. */
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Dot(color: Color, size: Dp = 9.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(color, CircleShape),
    )
}

@Composable
private fun kgSubtitle(state: DfrootState): String {
    val last = state.kgLast
    return if (last.known) {
        val base = stringResource(R.string.kg_last, last.unlock, kgStateLabel(last.post))
        if (last.parked) "$base · ${stringResource(R.string.kg_parked)}" else base
    } else {
        kgStateLabel(state.kgState)
    }
}

@Composable
private fun kgStateLabel(state: Int): String = stringResource(
    when (state) {
        0 -> R.string.kg_state_prenormal
        1 -> R.string.kg_state_checking
        2 -> R.string.kg_state_active
        3 -> R.string.kg_state_locked
        4 -> R.string.kg_state_completed
        5 -> R.string.kg_state_error
        else -> R.string.kg_state_unknown
    },
)

private fun compactKernel(version: String): String {
    if (version.isEmpty()) return version
    val parts = version.split('-')
    return if (parts.size > 3) parts.take(3).joinToString("-") else version
}

private fun formatRetry(ms: Long): String {
    val seconds = (ms / 1000L).coerceAtLeast(0L)
    return if (seconds >= 60) "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" else "${seconds}s"
}

private fun shortAction(action: String): String = when {
    action.isEmpty() -> "—"
    action.endsWith("LOCKED_BOOT_COMPLETED") -> "locked boot"
    action.endsWith("BOOT_COMPLETED") -> "boot"
    action.endsWith("USER_PRESENT") -> "unlock"
    action.endsWith("USER_UNLOCKED") -> "user unlocked"
    action.endsWith("MY_PACKAGE_REPLACED") -> "app update"
    action == "adb" -> "adb"
    action == "tick" -> "tick"
    action == "su-drop" -> "su drop"
    action == "alarm" -> "alarm"
    else -> action.substringAfterLast('.')
}
