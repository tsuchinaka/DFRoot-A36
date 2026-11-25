package df.root

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
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
    val managerPackage: String = "",
    val targetOk: Boolean = true,
    val targetDetail: String = "",
    val kgEnabled: Boolean = true,
    val kgState: Int = -1,
    val lastPass: String = "",
    val chain: ChainUi = ChainUi(),
    val rootSide: RootUi = RootUi(),
    val kgLast: KgUi = KgUi(),
    val phase: String = "",
    val runStartedAt: Long = 0L,
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
    private var managerPackage = ""
    private var lastPassCheckedAt = 0L
    private var lastPassCache = ""
    private var lastProbeAt = 0L
    private var lastProbe: RootProbe.Result? = null

    init {
        val target = TargetGuard.check(context)
        _state.update {
            it.copy(
                kernelVersion = System.getProperty("os.version") ?: "",
                deviceModel = android.os.Build.MODEL,
                autoEnabled = isAutoEnabled(),
                targetOk = target.ok,
                targetDetail = target.detail,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val log = loadPersistedLog()
            val kg = TextParse.kgState(Probes.prop("knox.kg.state"))
            val pass = lastPassLine()
            lastPassCache = pass
            lastPassCheckedAt = SystemClock.elapsedRealtime()
            // Materialize the switch state for the root supervisor. Missing
            // file means "on"; the file only exists so a toggle off survives.
            BootReceiver.setAutoIntent(context, BootReceiver.isAutoEnabled(context))
            _state.update {
                it.copy(
                    log = log,
                    kgState = kg,
                    lastPass = pass,
                    chain = readChain(context),
                    rootSide = readRootSide(context),
                    kgLast = readKgLast(context),
                )
            }
        }
        startProbe()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) {
            startProbe()
        } else {
            probeJob?.cancel()
            probeJob = null
        }
    }

    private fun startProbe() {
        if (!foreground) return
        probeJob?.cancel()
        probeJob = viewModelScope.launch {
            while (true) {
                refresh()
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    private suspend fun refresh() {
        Stats.uiRefresh()
        val snapshot = withContext(Dispatchers.IO) {
            val now = SystemClock.elapsedRealtime()
            val probe = if (lastProbe?.live == true
                && now - lastProbeAt < SU_VERIFY_MS
                && PostRoot.heartbeatHealthy()
            ) {
                lastProbe!!
            } else {
                RootProbe.probe().also {
                    lastProbe = it
                    lastProbeAt = now
                }
            }
            refreshManager()
            val pass = if (now - lastPassCheckedAt >= LAST_PASS_INTERVAL_MS) {
                lastPassCheckedAt = now
                lastPassLine().also { lastPassCache = it }
            } else {
                lastPassCache
            }
            RefreshSnapshot(
                probe = probe,
                chainRan = chainRan(),
                autoEnabled = isAutoEnabled(),
                kgEnabled = KgWatchdog.enabled(context),
                kgState = TextParse.kgState(Probes.prop("knox.kg.state")),
                lastPass = pass,
                chain = readChain(context),
                rootSide = readRootSide(context),
                kgLast = readKgLast(context),
            )
        }
        _state.update {
            it.copy(
                suProbed = true,
                rootLive = snapshot.probe.live,
                suDetail = if (snapshot.probe.live) {
                    context.getString(R.string.su_ok)
                } else {
                    snapshot.probe.detail
                },
                chainRan = snapshot.chainRan,
                autoEnabled = snapshot.autoEnabled,
                managerInstalled = managerInstalled,
                managerVersion = managerVersion,
                managerName = managerName,
                managerPackage = managerPackage,
                kgEnabled = snapshot.kgEnabled,
                kgState = snapshot.kgState,
                lastPass = snapshot.lastPass,
                chain = snapshot.chain,
                rootSide = snapshot.rootSide,
                kgLast = snapshot.kgLast,
            )
        }
    }

    private data class RefreshSnapshot(
        val probe: RootProbe.Result,
        val chainRan: Boolean,
        val autoEnabled: Boolean,
        val kgEnabled: Boolean,
        val kgState: Int,
        val lastPass: String,
        val chain: ChainUi,
        val rootSide: RootUi,
        val kgLast: KgUi,
    )

    fun runChain() {
        if (!TargetGuard.check(context).ok) {
            snack(R.string.target_unsupported)
            return
        }
        if (_state.value.running) {
            snack(R.string.already_running)
            return
        }
        if (!Runner.claim(context)) {
            snack(R.string.already_running)
            return
        }
        KeepService.start(context)
        _state.update {
            it.copy(
                running = true,
                runStartedAt = SystemClock.elapsedRealtime(),
                phase = context.getString(R.string.phase_starting),
            )
        }
        report("\n=== DFRoot a36xq / DirtyFrag CVE-2026-43284 ===\n")

        runJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
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
                    while (SystemClock.elapsedRealtime() - startedAt < ROOT_WAIT_MS) {
                        val probed = withContext(Dispatchers.IO) { RootProbe.probe() }
                        if (probed.live) {
                            report(context.getString(R.string.su_ok) + "\n")
                            break
                        }
                        delay(2000)
                        if (SystemClock.elapsedRealtime() - lastNote > 20000) {
                            lastNote = SystemClock.elapsedRealtime()
                            val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
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
        BootReceiver.setAutoIntent(context, enabled)
        report(context.getString(if (enabled) R.string.auto_enabled else R.string.auto_disabled) + "\n")
        _state.update { it.copy(autoEnabled = enabled) }
        if (enabled) {
            KeepService.start(context)
            KeepService.requestBatteryExemption(context)
        } else if (!_state.value.rootLive) {
            KeepService.cancelKeep(context)
            KeepService.cancelRetry(context)
            KeepService.stopIfIdle(context)
        }
    }

    fun setKgEnabled(enabled: Boolean) {
        KgWatchdog.setEnabled(context, enabled)
        _state.update { it.copy(kgEnabled = enabled) }
        report(context.getString(if (enabled) R.string.kg_enabled else R.string.kg_disabled) + "\n")
    }

    fun reboot() {
        viewModelScope.launch(Dispatchers.IO) {
            val out = Runner.suScript(20L, "reboot")
            if (out == null) {
                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.reboot(null)
                } catch (t: Throwable) {
                    Log.w(TAG, "reboot: $t")
                }
            }
        }
    }

    fun openManager() {
        val pkg = _state.value.managerPackage
        if (pkg.isEmpty()) {
            snack(R.string.manager_absent)
            return
        }
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                snack(R.string.manager_absent)
                return
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "open manager: $t")
        }
    }

    fun copySuCommand() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("su", "su -c id"))
            snack(R.string.su_copied)
        } catch (t: Throwable) {
            Log.w(TAG, "copy su: $t")
        }
    }

    fun consumeSnackbar() {
        _state.update { it.copy(snackbar = null) }
    }

    override fun report(msg: String) {
        Log.i(TAG, msg.trim())
        LogFiles.append(logFile, msg.toByteArray(), FILE_CAP, FILE_KEEP)
        val phase = phaseFor(msg)
        _state.update { s ->
            var log = s.log + msg
            if (log.length > LOG_LIMIT) log = log.substring(log.length - LOG_KEEP)
            s.copy(log = log, phase = phase ?: s.phase)
        }
    }

    /** Map the reporter's own stage lines to one human phase for the run card. */
    private fun phaseFor(msg: String): String? {
        val line = msg.trim()
        val key = when {
            line.startsWith("=== setup") -> R.string.phase_setup
            line.startsWith("encap port") -> R.string.phase_socket
            line.startsWith("spi:") -> R.string.phase_keys
            line.startsWith("ksud staged") -> R.string.phase_stage
            line.startsWith("=== exploit") -> R.string.phase_symbols
            line.startsWith("* patch #1") -> R.string.phase_patch_crash
            line.startsWith("* patch #2") -> R.string.phase_patch_vendor
            line.startsWith("* patching") -> R.string.phase_patch_libs
            line.startsWith("* triggering") -> R.string.phase_trigger
            line.startsWith("stage1:") -> R.string.phase_stage1
            line.startsWith("stage2: module loaded") -> R.string.phase_module
            line.startsWith("stage2: launching ksud") -> R.string.phase_ksud
            line.startsWith("***SUCCESS***") -> R.string.phase_success
            line.startsWith("=== cleanup") -> R.string.phase_cleanup
            line.startsWith("[overlay]") -> R.string.phase_overlay
            line.startsWith("[kg") -> R.string.phase_kg
            else -> null
        }
        return key?.let { context.getString(it) }
    }

    fun copyLog() {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    readText(logFile).ifEmpty { _state.value.log }
                }
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("DFRoot log", text))
                snack(R.string.log_copied)
            } catch (t: Throwable) {
                Log.w(TAG, "copy log: $t")
            }
        }
    }

    fun clearLog() {
        _state.update { it.copy(log = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                FileOutputStream(logFile, false).close()
            } catch (_: Throwable) {
            }
        }
        snack(R.string.log_cleared)
    }

    fun exportLog() {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val text = readText(logFile).ifEmpty { _state.value.log }
                    exportFile(text, "dfroot-log.txt")
                }
                share(file, context.getString(R.string.log_export))
            } catch (t: Throwable) {
                Log.e(TAG, "export log", t)
                snack(R.string.log_export_failed)
            }
        }
    }

    fun exportDiagnostics() {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    exportFile(Diagnostics.build(context), "dfroot-diagnostics.txt")
                }
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
        _state.update { it.copy(running = false, snackbar = message, phase = "", runStartedAt = 0L) }
        viewModelScope.launch(Dispatchers.IO) {
            val chain = readChain(context)
            val side = readRootSide(context)
            _state.update { it.copy(chain = chain, rootSide = side) }
        }
    }

    private fun loadPersistedLog(): String {
        return try {
            val boot = File(context.createDeviceProtectedStorageContext().filesDir, "boot.log")
            val prefs = context.getSharedPreferences("dfroot", Context.MODE_PRIVATE)
            var upto = prefs.getLong("bootlog_upto", 0L)
            val size = if (boot.isFile) boot.length() else 0L
            if (upto < 0 || upto > size) upto = 0L
            if (size > upto) {
                val count = (size - upto).coerceAtMost(LOG_IMPORT_MAX).toInt()
                val from = (size - count).coerceAtLeast(upto)
                val bytes = ByteArray((size - from).toInt())
                var read = 0
                java.io.FileInputStream(boot).use { input ->
                    input.channel.position(from)
                    while (read < bytes.size) {
                        val n = input.channel.read(java.nio.ByteBuffer.wrap(bytes, read, bytes.size - read))
                        if (n <= 0) break
                        read += n
                    }
                }
                if (read > 0) {
                    FileOutputStream(logFile, true).use { it.write(bytes, 0, read) }
                }
                prefs.edit().putLong("bootlog_upto", from + read).apply()
            }
            trimFile(logFile)
            readTail(logFile, LOG_KEEP)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun refreshManager() {
        val now = SystemClock.elapsedRealtime()
        if (now - managerCheckedAt < MANAGER_REFRESH_MS) return
        managerCheckedAt = now
        val managers = listOf(
            "me.weishu.kernelsu" to "KernelSU",
            "com.rifsxd.ksunext" to "KSU-Next",
            "com.resukisu.resukisu" to "ReSukiSU",
        )
        managerInstalled = false
        managerVersion = ""
        managerName = ""
        managerPackage = ""
        for ((pkg, name) in managers) {
            try {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                managerInstalled = true
                managerName = name
                managerPackage = pkg
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
            val lines = readTail(boot, 8192).split('\n')
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
        LogFiles.trim(file, FILE_CAP, FILE_KEEP)
    }

    private fun readTail(file: File, maxBytes: Int): String =
        LogFiles.readTail(file, maxBytes)

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
        const val LOG_IMPORT_MAX = 512L * 1024L
        const val FILE_CAP = 512L * 1024L
        const val FILE_KEEP = 256L * 1024L
        const val ROOT_WAIT_MS = 180000L
        const val PROBE_INTERVAL_MS = 30000L
        const val SU_VERIFY_MS = 300000L
        const val MANAGER_REFRESH_MS = 300000L
        const val LAST_PASS_INTERVAL_MS = 60000L
    }
}
