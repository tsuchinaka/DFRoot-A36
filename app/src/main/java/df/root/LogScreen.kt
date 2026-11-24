package df.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LogScreen(
    log: String,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val listState = rememberLazyListState()
    var query by rememberSaveable { mutableStateOf("") }
    var follow by rememberSaveable { mutableStateOf(true) }
    var confirmClear by remember { mutableStateOf(false) }
    val hasLog = log.isNotEmpty()
    val lines = remember(log, query) {
        if (query.isBlank()) {
            log.lineSequence().toList()
        } else {
            log.lineSequence()
                .filter { it.contains(query, ignoreCase = true) }
                .toList()
        }
    }
    val displayed = lines.size

    LaunchedEffect(displayed, follow) {
        if (follow && displayed > 0) {
            listState.scrollToItem(displayed - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.log_count, displayed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            LogButton(stringResource(R.string.log_copy), enabled = hasLog, onClick = onCopy)
            LogButton(stringResource(R.string.log_clear), enabled = hasLog) { confirmClear = true }
            LogButton(stringResource(R.string.log_export), enabled = hasLog, onClick = onExport)
            LogButton(stringResource(R.string.log_diag), onClick = onDiagnostics)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.log_search)) },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            LogButton(
                stringResource(if (follow) R.string.log_follow_on else R.string.log_follow_off),
            ) { follow = !follow }
        }

        if (!hasLog) {
            Box(Modifier.fillMaxSize().padding(top = 24.dp)) {
                Text(
                    text = stringResource(R.string.log_placeholder),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (displayed == 0) {
            Box(Modifier.fillMaxSize().padding(top = 24.dp)) {
                Text(
                    text = stringResource(R.string.log_no_match),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 96.dp),
            ) {
                items(lines) { line ->
                    SelectionContainer {
                        Text(
                            text = line.ifEmpty { " " },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            overflow = TextOverflow.Visible,
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.log_clear)) },
            text = { Text(stringResource(R.string.log_clear_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClear()
                    },
                ) { Text(stringResource(R.string.log_clear_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.log_cancel))
                }
            },
        )
    }
}

@Composable
private fun LogButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
