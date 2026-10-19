package df.root

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/** Entry point used by [MainActivity] to attach the Compose tree. */
object DfrootUi {

    @JvmStatic
    fun attach(activity: ComponentActivity) {
        activity.enableEdgeToEdge()
        activity.setContent {
            DFRootTheme {
                DFRootApp()
            }
        }
    }
}
