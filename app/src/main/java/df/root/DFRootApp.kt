package df.root

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Tab { Home, Log, About }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DFRootApp(vm: DfrootViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val tabs = Tab.entries

    LaunchedEffect(state.snackbar) {
        val message = state.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeSnackbar()
    }

    BackHandler(fabMenuExpanded) { fabMenuExpanded = false }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize().padding(bottom = 96.dp),
                transitionSpec = {
                    val forward = targetState > initialState
                    val offset = { full: Int -> if (forward) full / 10 else -full / 10 }
                    (fadeIn() + slideInHorizontally { offset(it) })
                        .togetherWith(fadeOut() + slideOutHorizontally { -offset(it) })
                },
                label = "tab",
            ) { index ->
                when (tabs[index]) {
                    Tab.Home -> HomeScreen(
                        state = state,
                        onRun = vm::runChain,
                        onAutoEnabledChange = vm::setAutoEnabled,
                    )

                    Tab.Log -> LogScreen(log = state.log)
                    Tab.About -> AboutScreen()
                }
            }

            FloatingActionButtonMenu(
                expanded = fabMenuExpanded,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                button = {
                    ToggleFloatingActionButton(
                        checked = fabMenuExpanded,
                        onCheckedChange = { fabMenuExpanded = it },
                    ) {
                        val icon by remember {
                            derivedStateOf {
                                if (fabMenuExpanded) Icons.Rounded.Close else Icons.Rounded.Menu
                            }
                        }
                        Icon(
                            painter = rememberVectorPainter(icon),
                            contentDescription = stringResource(
                                if (fabMenuExpanded) R.string.menu_close else R.string.menu_open,
                            ),
                            modifier = Modifier.animateIcon({ checkedProgress }),
                        )
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        tab = Tab.Home.ordinal
                        fabMenuExpanded = false
                    },
                    icon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
                    text = { Text(stringResource(R.string.tab_home)) },
                    containerColor = if (tabs[tab] == Tab.Home) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (tabs[tab] == Tab.Home) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        tab = Tab.Log.ordinal
                        fabMenuExpanded = false
                    },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    text = { Text(stringResource(R.string.tab_log)) },
                    containerColor = if (tabs[tab] == Tab.Log) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (tabs[tab] == Tab.Log) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        tab = Tab.About.ordinal
                        fabMenuExpanded = false
                    },
                    icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                    text = { Text(stringResource(R.string.tab_about)) },
                    containerColor = if (tabs[tab] == Tab.About) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (tabs[tab] == Tab.About) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
