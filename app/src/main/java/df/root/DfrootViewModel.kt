package df.root

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern

/**
 * Drives the exploit chain and exposes UI state. All chain calls go through the unchanged
 * Java bridge: [Runner], [RootProbe], [RootRecord] and [MainActivity.nativeRunAll].
 */
data class DfrootState(
    val running: Boolean = false,
    val log: String = "",
    val suProbed: Boolean = false,
    val rootLive: Boolean = false,
    val suDetail: String = "",
    val chainRan: Boolean = false,
    val autoEnabled: Boolean = false,
    val kernelVersion: String = "",
    val deviceModel: String = "",
    val managerInstalled: Boolean = false,
    val managerVersion: String = "",
    val snackbar: String? = null,
)

class DfrootViewModel(app: Application) : AndroidViewModel(app), IReporter {

    private val context: Context get() = getApplication()

    private val logFile = File(context.filesDir, "run.log")
    private val ansi = Pattern.compile("\u001b\\[[0-9;]*m")

    private val _state = MutableStateFlow(DfrootState())
    val state: StateFlow<DfrootState> = _state.asStateFlow()

    private var probeJob: Job? = null
    private var runJob: Job? = null

    init {
        _state.update {
            it.copy(
                kernelVersion = System.getProperty("os.version") ?: "",
                deviceModel = android.os.Build.MODEL,
                autoEnabled = isAutoEnabled(),
            )
        }
        probeJob = viewModelScope.launch {
            while (true) {
                refresh()
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    private suspend fun refresh() {
        val probe = withContext(Dispatchers.IO) { RootProbe.probe() }
        val manager = managerInfo()
        _state.update {
            it.copy(
                suProbed = true,
                rootLive = probe.live,
                suDetail = if (probe.live) context.getString(R.string.su_ok) else probe.detail,
                chainRan = chainRan(),
                autoEnabled = isAutoEnabled(),
                managerInstalled = manager.first,
                managerVersion = manager.second,
            )
        }
    }

    fun runChain() {
        if (_state.value.running) {
            _state.update { it.copy(snackbar = context.getString(R.string.already_running)) }
            return
        }
        if (!Runner.claim()) {
            _state.update { it.copy(snackbar = context.getString(R.string.already_running)) }
            return
        }
        _state.update { it.copy(running = true, log = "") }
        truncateLog()
        report("=== DFRoot a36xq / DirtyFrag CVE-2026-43284 ===\n")

        runJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val rc = withContext(Dispatchers.IO) { Runner.run(context, this@DfrootViewModel, false) }
                report("\nRESULT rc=$rc\n")

                if (rc == 0) {
                    RootRecord.markRooted(context)
                    report(context.getString(R.string.waiting_for_install) + "\n")
                    var lastNote = 0L
                    while (System.currentTimeMillis() - startedAt < ROOT_WAIT_MS) {
                        val probed = withContext(Dispatchers.IO) { RootProbe.probe() }
                        if (probed.live) {
                            report(context.getString(R.string.su_ok) + "\n")
                            break
                        }
                        delay(2000)
                        if (System.currentTimeMillis() - lastNote > 20000) {
                            lastNote = System.currentTimeMillis()
                            val seconds = (System.currentTimeMillis() - startedAt) / 1000
                            report(context.getString(R.string.still_installing, seconds) + "\n")
                        }
                    }
                }

                val finalProbe = withContext(Dispatchers.IO) { RootProbe.probe() }
                finishRun(context.getString(resultMessage(rc, finalProbe.live)))
            } catch (t: Throwable) {
                Log.e(TAG, "exploit exception", t)
                report("\nexception: $t\n")
                finishRun(context.getString(R.string.result_exception, t.toString()))
            }
        }
    }

    fun setAutoEnabled(enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, BootReceiver::class.java),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        report(context.getString(if (enabled) R.string.auto_enabled else R.string.auto_disabled) + "\n")
        _state.update { it.copy(autoEnabled = enabled) }
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    override fun report(msg: String) {
        Log.i(TAG, msg.trim())
        try {
            FileOutputStream(logFile, true).use { it.write(msg.toByteArray()) }
        } catch (_: Throwable) {
        }
        val shown = ansi.matcher(msg).replaceAll("")
        _state.update { s ->
            var log = s.log + shown
            if (log.length > LOG_LIMIT) log = log.substring(log.length - LOG_KEEP)
            s.copy(log = log)
        }
    }

    private fun finishRun(message: String) {
        Runner.release()
        _state.update { it.copy(running = false, snackbar = message) }
    }

    private fun truncateLog() {
        try {
            FileOutputStream(logFile, false).use { it.write(ByteArray(0)) }
        } catch (_: Throwable) {
        }
    }

    private fun chainRan(): Boolean =
        File("/dev/df").exists() || RootRecord.rootedThisBoot(context)

    private fun isAutoEnabled(): Boolean =
        context.packageManager.getComponentEnabledSetting(
            ComponentName(context, BootReceiver::class.java)
        ) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun managerInfo(): Pair<Boolean, String> = try {
        val info = context.packageManager.getPackageInfo("me.weishu.kernelsu", 0)
        true to (info.versionName ?: "")
    } catch (_: Throwable) {
        false to ""
    }

    private fun resultMessage(rc: Int, live: Boolean): Int = when {
        live -> R.string.result_success
        rc == 0 -> R.string.result_installed_not_granted
        rc == 1 -> R.string.result_ksud_failed
        else -> R.string.result_patch_failed
    }

    private companion object {
        const val TAG = "dfroot"
        const val LOG_LIMIT = 200000
        const val LOG_KEEP = 100000
        const val ROOT_WAIT_MS = 180000L
        const val PROBE_INTERVAL_MS = 5000L
    }
}
