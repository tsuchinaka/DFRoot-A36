package df.root

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

private data class Credit(val name: String, val url: String)

private const val REPO = "https://github.com/tsuchinaka/DFRoot-A36"

private val CREDITS = listOf(
    Credit("Tsuchinaka", "https://github.com/tsuchinaka"),
    Credit("diabl0w, DFRoot", "https://github.com/diabl0w/DFRoot"),
    Credit("LSPosed, LSPromise", "https://github.com/LSPosed/LSPromise"),
    Credit("polygraphene, DFReroot", "https://github.com/polygraphene/DFReroot"),
    Credit("combeng6th, DirtyInit", "https://github.com/combeng6th/DirtyInit"),
    Credit("tiann, KernelSU", "https://github.com/tiann/KernelSU"),
    Credit("rushiranpise, Root My Galaxy Next", "https://github.com/rushiranpise/Root-My-Galaxy-Next"),
    Credit("BuSung-dev, Root My Galaxy Payloads", "https://github.com/BuSung-dev/Root-My-Galaxy-Payloads"),
    Credit("marin-m, vmlinux-to-elf", "https://github.com/marin-m/vmlinux-to-elf"),
)

@Composable
fun AboutScreen() {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.about_build, version),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.about_summary),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(
                onClick = { uriHandler.openUri(REPO) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.about_repo))
            }
            TextButton(
                onClick = { uriHandler.openUri("$REPO/releases") },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.about_releases))
            }
        }
        Spacer(Modifier.height(20.dp))
        CREDITS.forEach { credit ->
            Text(
                text = credit.name,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { uriHandler.openUri(credit.url) }
                    .padding(vertical = 12.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.about_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(96.dp))
    }
}
