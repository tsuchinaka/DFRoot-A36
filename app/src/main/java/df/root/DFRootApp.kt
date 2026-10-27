package df.root

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val tabs = Tab.entries

    LaunchedEffect(state.snackbar) {
        val message = state.snackbar ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeSnackbar()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
                ShortNavigationBar(
                    containerColor = Color.Transparent,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    ShortNavigationBarItem(
                        selected = tabs[tab] == Tab.Home,
                        onClick = { tab = Tab.Home.ordinal },
                        icon = { Icon(Icons.Rounded.Shield, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_home)) },
                    )
                    ShortNavigationBarItem(
                        selected = tabs[tab] == Tab.Log,
                        onClick = { tab = Tab.Log.ordinal },
                        icon = {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                        },
                        label = { Text(stringResource(R.string.tab_log)) },
                    )
                    ShortNavigationBarItem(
                        selected = tabs[tab] == Tab.About,
                        onClick = { tab = Tab.About.ordinal },
                        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_about)) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = tab,
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
        }
    }
}
