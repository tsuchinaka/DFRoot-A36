package df.root

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.FileProvider
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
    val managerName: String = "",
    val targetOk: Boolean = true,
    val targetDetail: String = "",
    val kgEnabled: Boolean = true,
    val kgState: Int = -1,
    val lastPass: String = "",
    val snackbar: String? = null,
)

class DfrootViewModel(app: Application) : AndroidViewModel(app), IReporter {

    private val context: Context get() = getApplication()
    private val logFile = File(context.filesDir, "run.log")

    private val _state = MutableStateFlow(DfrootState())
    val state: StateFlow<DfrootState> = _state.asStateFlow()

    @Volatile
    private var foreground = true
    private var probeJob: Job? = null
    private var runJob: Job? = null
    private var managerCheckedAt = 0L
    private var managerInstalled = false
    private var managerVersion = ""
    private var managerName = ""

    init {
        val target = TargetGuard.check(context)
        _state.update {
            it.copy(
                kernelVersion = System.getProperty("os.version") ?: "",
                deviceModel = android.os.Build.MODEL,
                autoEnabled = isAutoEnabled(),
                log = loadPersistedLog(),
                targetOk = target.ok,
                targetDetail = target.detail,
                kgEnabled = KgWatchdog.enabled(context),
                kgState = TextParse.kgState(Probes.prop("knox.kg.state")),
                lastPass = lastPassLine(),
            )
        }
        probeJob = viewModelScope.launch {
            while (true) {
                refresh()
                delay(if (foreground) PROBE_INTERVAL_MS else PROBE_INTERVAL_BG_MS)
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
    }

    private suspend fun refresh() {
        val probe = withContext(Dispatchers.IO) { RootProbe.probe() }
        refreshManager()
        val pass = lastPassLine()
        _state.update {
            it.copy(
                suProbed = true,
                rootLive = probe.live,
                suDetail = if (probe.live) context.getString(R.string.su_ok) else probe.detail,
                chainRan = chainRan(),
                autoEnabled = isAutoEnabled(),
                managerInstalled = managerInstalled,
                managerVersion = managerVersion,
                managerName = managerName,
                kgEnabled = KgWatchdog.enabled(context),
                kgState = TextParse.kgState(Probes.prop("knox.kg.state")),
                lastPass = pass,
            )
        }
    }

    fun runChain() {
        if (!TargetGuard.check(context).ok) {
            snack(R.string.target_unsupported)
            return
        }
        if (_state.value.running) {
            snack(R.string.already_running)
            return
        }
        if (!Runner.claim()) {
            snack(R.string.already_running)
            return
        }
        _state.update { it.copy(running = true) }
        report("\n=== DFRoot a36xq / DirtyFrag CVE-2026-43284 ===\n")

        runJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            try {
                val rc = withContext(Dispatchers.IO) { Runner.run(context, this@DfrootViewModel, false) }
                report("\nRESULT rc=$rc\n")

                val maybeInstalled = rc == 0 || (rc == 2 && File("/dev/df").exists())
                if (maybeInstalled) {
                    if (rc == 0) {
                        RootRecord.markRooted(context)
                    }
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
        if (enabled) {
            KeepService.requestBatteryExemption(context)
        }
    }

    fun setKgEnabled(enabled: Boolean) {
        KgWatchdog.setEnabled(context, enabled)
        _state.update { it.copy(kgEnabled = enabled) }
        report(context.getString(if (enabled) R.string.kg_enabled else R.string.kg_disabled) + "\n")
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    override fun report(msg: String) {
        Log.i(TAG, msg.trim())
        try {
            FileOutputStream(logFile, true).use { it.write(msg.toByteArray()) }
            trimFile(logFile)
        } catch (_: Throwable) {
        }
        _state.update { s ->
            var log = s.log + msg
            if (log.length > LOG_LIMIT) log = log.substring(log.length - LOG_KEEP)
            s.copy(log = log)
        }
    }

    fun copyLog() {
        try {
            val text = readText(logFile).ifEmpty { _state.value.log }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DFRoot log", text))
            snack(R.string.log_copied)
        } catch (t: Throwable) {
            Log.w(TAG, "copy log: $t")
        }
    }

    fun clearLog() {
        try {
            FileOutputStream(logFile, false).close()
        } catch (_: Throwable) {
        }
        _state.update { it.copy(log = "") }
        snack(R.string.log_cleared)
    }

    fun exportLog() {
        try {
            val text = readText(logFile).ifEmpty { _state.value.log }
            val file = exportFile(text, "dfroot-log.txt")
            share(file, context.getString(R.string.log_export))
        } catch (t: Throwable) {
            Log.e(TAG, "export log", t)
            snack(R.string.log_export_failed)
        }
    }

    fun exportDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = exportFile(Diagnostics.build(context), "dfroot-diagnostics.txt")
                share(file, context.getString(R.string.log_diag))
            } catch (t: Throwable) {
                Log.e(TAG, "export diagnostics", t)
                snack(R.string.log_export_failed)
            }
        }
    }

    private fun exportFile(text: String, name: String): File {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(text)
        return file
    }

    private fun share(file: File, subject: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun snack(res: Int) {
        _state.update { it.copy(snackbar = context.getString(res)) }
    }

    private fun finishRun(message: String) {
        Runner.release()
        _state.update { it.copy(running = false, snackbar = message) }
    }

    private fun loadPersistedLog(): String {
        return try {
            val boot = File(context.createDeviceProtectedStorageContext().filesDir, "boot.log")
            val bootBytes = if (boot.isFile) boot.readBytes() else ByteArray(0)
            val prefs = context.getSharedPreferences("dfroot", Context.MODE_PRIVATE)
            var upto = prefs.getLong("bootlog_upto", 0L)
            if (upto < 0 || upto > bootBytes.size) upto = 0L
            if (bootBytes.size > upto) {
                val add = String(bootBytes, upto.toInt(), bootBytes.size - upto.toInt(), Charsets.UTF_8)
                FileOutputStream(logFile, true).use { it.write(add.toByteArray()) }
                prefs.edit().putLong("bootlog_upto", bootBytes.size.toLong()).apply()
            }
            trimFile(logFile)
            readText(logFile).takeLast(LOG_KEEP)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun refreshManager() {
        val now = System.currentTimeMillis()
        if (now - managerCheckedAt < 60000L) return
        managerCheckedAt = now
        val managers = listOf(
            "me.weishu.kernelsu" to "KernelSU",
            "com.rifsxd.ksunext" to "KSU-Next",
            "com.resukisu.resukisu" to "ReSukiSU",
        )
        managerInstalled = false
        managerVersion = ""
        managerName = ""
        for ((pkg, name) in managers) {
            try {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                managerInstalled = true
                managerName = name
                managerVersion = info.versionName ?: ""
                return
            } catch (_: Throwable) {
            }
        }
    }

    private fun chainRan(): Boolean =
        File("/dev/df").exists() || RootRecord.rootedThisBoot(context)

    private fun isAutoEnabled(): Boolean = BootReceiver.isAutoEnabled(context)

    private fun lastPassLine(): String {
        return try {
            val boot = File(context.createDeviceProtectedStorageContext().filesDir, "boot.log")
            if (!boot.isFile) return ""
            val bytes = boot.readBytes()
            val from = (bytes.size - 8192).coerceAtLeast(0)
            val lines = String(bytes, from, bytes.size - from, Charsets.UTF_8).split('\n')
            lines.asReversed().firstOrNull {
                val line = it.trim()
                line.startsWith("chain: total") || line.startsWith("chain: rooted") ||
                    line.startsWith("chain: target refused") || line.startsWith("chain: no kernel patch")
            }?.trim() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun readText(file: File): String {
        if (!file.isFile) return ""
        return try {
            String(file.readBytes(), Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun trimFile(file: File) {
        if (!file.isFile || file.length() <= FILE_CAP) return
        try {
            val bytes = file.readBytes()
            val start = (bytes.size - FILE_KEEP.toInt()).coerceAtLeast(0)
            file.writeBytes(bytes.copyOfRange(start, bytes.size))
        } catch (_: Throwable) {
        }
    }

    private fun resultMessage(rc: Int, live: Boolean): Int = when {
        live -> R.string.result_success
        rc == 0 -> R.string.result_installed_not_granted
        rc == 1 -> R.string.result_ksud_failed
        rc == 2 -> R.string.result_watchdog
        rc == 4 -> R.string.result_module_failed
        rc == 5 -> R.string.result_mount_failed
        rc == 6 -> R.string.result_staging_failed
        else -> R.string.result_patch_failed
    }

    private companion object {
        const val TAG = "dfroot"
        const val LOG_LIMIT = 200000
        const val LOG_KEEP = 100000
        const val FILE_CAP = 512000L
        const val FILE_KEEP = 256000L
        const val ROOT_WAIT_MS = 180000L
        const val PROBE_INTERVAL_MS = 15000L
        const val PROBE_INTERVAL_BG_MS = 60000L
    }
}
