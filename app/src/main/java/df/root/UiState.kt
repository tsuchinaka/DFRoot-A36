package df.root

/**
 * UI mirrors of the package-private Java readers. Keeping the composables on
 * Kotlin types stops the public state from exposing package-private classes
 * (Kotlin rejects that at compile time) and keeps the Java side free of UI
 * concerns.
 */
data class ChainUi(
    val stage: String = "",
    val fails: Int = 0,
    val lastRc: Int = -999,
    val lastMs: Long = 0L,
    val lastAct: String = "",
    val retryAt: Long = 0L,
    val ran: Boolean = false,
) {
    fun needsReboot(): Boolean = stage == "needs-reboot"
    fun noAllowlist(): Boolean = stage == "no-allowlist"
    fun unsupported(): Boolean = stage == "unsupported"
    fun stalled(): Boolean = fails >= 6
    fun hasRun(): Boolean = ran
}

data class RootUi(
    val published: Boolean = false,
    val heartbeatFresh: Boolean = false,
    val heartbeatAgeSec: Long = -1,
    val supervisorAlive: Boolean = false,
    val watchdogAlive: Boolean = false,
    val moduleLoaded: Boolean = false,
    val mutexHeld: Boolean = false,
    val scriptsInstalled: Boolean = false,
) {
    fun any(): Boolean = published || scriptsInstalled || moduleLoaded || mutexHeld || supervisorAlive
}

data class KgUi(
    val known: Boolean = false,
    val unlock: Int = -1,
    val err: Int = 0,
    val post: Int = -1,
    val parked: Boolean = false,
)

private fun ChainStatus.toUi() = ChainUi(
    stage = stage,
    fails = fails,
    lastRc = lastRc,
    lastMs = lastMs,
    lastAct = lastAct,
    retryAt = retryAt,
    ran = hasRun(),
)

private fun RootSide.toUi() = RootUi(
    published = published,
    heartbeatFresh = heartbeatFresh,
    heartbeatAgeSec = heartbeatAgeSec,
    supervisorAlive = supervisorAlive,
    watchdogAlive = watchdogAlive,
    moduleLoaded = moduleLoaded,
    mutexHeld = mutexHeld,
    scriptsInstalled = scriptsInstalled,
)

private fun KgWatchdog.Last.toUi() = KgUi(
    known = known,
    unlock = unlock,
    err = err,
    post = post,
    parked = parked,
)

internal fun readChain(ctx: android.content.Context): ChainUi = ChainStatus.read(ctx).toUi()
internal fun readRootSide(): RootUi = RootSide.read().toUi()
internal fun readKgLast(ctx: android.content.Context): KgUi = KgWatchdog.last(ctx).toUi()
