package df.root;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * One boot pass of the auto-root chain, executed on the single chain worker
 * inside KeepService. BootReceiver only enqueues; this class owns the
 * sequencing, and KeepService owns the retry alarm.
 *
 * Efficiency rules:
 * - a cheap gate (apex crash_dump64, vendor libbinderdebug, sys.boot_completed)
 *   stops the chain from burning attempts before the system is ready;
 * - the overlay wait only runs when the kernel can actually be patched
 *   (rc == 0 or the /dev/df mutex exists), so a failed pass no longer polls
 *   su for three minutes;
 * - retries follow a ladder (15/30/45/60 s) while the boot is settling and
 *   cap at 90 s afterwards;
 * - the wakelock is held per attempt and per overlay wait, never for the
 *   whole pass;
 * - log lines are buffered in memory and flushed once at the end of the
 *   pass, so the race window pays no per-line file I/O.
 */
final class BootChain {

    private static final String TAG = "dfroot";
    private static final String STAGE_FILE = "root-stage";
    private static final String UNLOCK_ATTEMPT_FILE = "unlock-attempt-at";

    private static final long[] LADDER = {15000L, 30000L, 45000L, 60000L};
    private static final long LADDER_CAP_MS = 90000L;
    private static final long BOOT_SETTLE_MS = 180000L;
    private static final int MAX_EXPLOIT_ATTEMPTS = 2;
    private static final long ATTEMPT_GAP_MS = 30000L;
    private static final long OVERLAY_WAIT_MS = 180000L;
    private static final long USER_UNLOCK_COOLDOWN_MS = 300000L;
    private static final int GATE_MAX_PENDING = 8;
    private static final long GATE_RETRY_MS = 15000L;
    private static final long WAKE_ATTEMPT_MS = 120000L;
    private static final long WAKE_OVERLAY_MS = 200000L;
    private static final long WAKE_GAP_MS = 45000L;
    private static final long LOG_CAP_BYTES = 512L * 1024L;
    private static final long LOG_KEEP_BYTES = 256L * 1024L;

    /** Elapsed-realtime of the next armed retry, or 0 when none is armed. */
    static volatile long nextRetryAt;

    private static final java.util.concurrent.locks.ReentrantLock PASS =
            new java.util.concurrent.locks.ReentrantLock();

    private static String stageBootId = "";
    private static String stageName = "";
    private static int ladder;
    private static int gatePending;
    private static boolean gateOpen;
    private static int stageFails;
    private static long firstPassUptime = -1L;

    private BootChain() {
    }

    /** Called by KeepService when a live root is observed to be gone. */
    static void noteRootLost() {
        ladder = 0;
        gatePending = 0;
    }

    static void pass(final Context appCtx, final String action) {
        if (!PASS.tryLock()) {
            Log.i(TAG, "chain: a pass is already in flight, dropping request");
            return;
        }
        try {
            passLocked(appCtx, action);
        } finally {
            PASS.unlock();
        }
    }

    private static void passLocked(final Context appCtx, final String action) {
        Stats.chainPass();
        final Context deCtx = appCtx.createDeviceProtectedStorageContext();
        final String act = action == null ? "boot" : action;
        loadStage(deCtx);
        Buffer log = new Buffer();
        long t0 = SystemClock.elapsedRealtime();
        boolean claimed = false;
        try {
            if (!BootReceiver.isAutoEnabled(appCtx)) {
                Log.i(TAG, "chain: auto disabled, ignoring " + act);
                return;
            }
            if (!Runner.claim(appCtx)) {
                Log.i(TAG, "chain: a run is already in flight, skipping " + act);
                return;
            }
            claimed = true;
            log.report("chain: " + act + " v" + versionName(appCtx)
                    + " dev=" + android.os.Build.DEVICE
                    + " boot=" + BootId.current()
                    + " kernel=" + System.getProperty("os.version") + "\n");
            work(appCtx, deCtx, act, log, t0);
        } catch (Throwable t) {
            log.report("chain: pass failed " + t + "\n");
        } finally {
            if (claimed) {
                Runner.release();
            }
            log.report("chain: total " + (SystemClock.elapsedRealtime() - t0) + "ms "
                    + Stats.snapshot() + "\n");
            saveStage(deCtx);
            log.flush(appCtx, deCtx);
        }
    }

    private static String versionName(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static void work(Context appCtx, Context deCtx, String act, Buffer log, long t0) {
        TargetGuard.Result target = TargetGuard.check(appCtx);
        if (!target.ok) {
            if (!"unsupported".equals(stageName)) {
                log.report("chain: target refused: " + target.detail + "\n");
            }
            stageName = "unsupported";
            KeepService.cancelRetry(appCtx);
            nextRetryAt = 0L;
            return;
        }
        if ("unsupported".equals(stageName)) {
            stageName = "";
        }
        if (RootRecord.rootedThisBoot(appCtx) && RootProbe.probe().live
                && PostRoot.heartbeatHealthy()) {
            log.report("chain: root and supervisor already healthy this boot\n");
            return;
        }
        if (RootProbe.probe().live) {
            if ("rooted".equals(stageName)) {
                log.report("chain: su live and already marked rooted this boot\n");
                return;
            }
            log.report("chain: su live before anything, overlay only\n");
            PowerManager.WakeLock wake = acquire(appCtx, WAKE_OVERLAY_MS);
            try {
                Runner.applyOverlay(deCtx, log, OVERLAY_WAIT_MS);
            } finally {
                release(wake);
            }
            markRooted(appCtx, deCtx, log, t0);
            return;
        }

        if (Intent_USER_PRESENT.equals(act) && unlockCooldownActive(deCtx)) {
            log.report("chain: " + act + " inside cooldown, skipping\n");
            return;
        }

        long gateStart = SystemClock.elapsedRealtime();
        String blocker = gateBlocker();
        if (blocker != null && gatePending < GATE_MAX_PENDING
                && !Intent_USER_UNLOCKED.equals(act) && !Intent_USER_PRESENT.equals(act)) {
            gatePending++;
            log.report("chain: gate pending #" + gatePending + " (" + blocker + ") "
                    + (SystemClock.elapsedRealtime() - gateStart) + "ms\n");
            scheduleNext(appCtx, GATE_RETRY_MS);
            return;
        }
        gatePending = 0;
        gateOpen = blocker == null;
        log.report("chain: gate open (" + (blocker == null ? "ready" : "forced " + blocker) + ") "
                + (SystemClock.elapsedRealtime() - gateStart) + "ms\n");
        if (Intent_USER_PRESENT.equals(act)) {
            markUnlockAttempt(deCtx);
        }

        if (new File("/dev/df").exists()) {
            // The mutex is O_EXCL and lives in tmpfs. If su is dead, no pass in
            // this boot can re-enter stage 2, so give it one bounded wait and
            // then stop burning wake locks on a boot that needs a reboot.
            log.report("chain: mutex held, waiting for su instead of re-exploit\n");
            PowerManager.WakeLock wake = acquire(appCtx, 60000L);
            try {
                for (int i = 0; i < 20 && !RootProbe.probe().live; i++) {
                    Thread.sleep(3000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                release(wake);
            }
            if (RootProbe.probe().live) {
                PowerManager.WakeLock overlay = acquire(appCtx, WAKE_OVERLAY_MS);
                try {
                    Runner.applyOverlay(deCtx, log, OVERLAY_WAIT_MS);
                } finally {
                    release(overlay);
                }
                markRooted(appCtx, deCtx, log, t0);
            } else {
                stageName = "needs-reboot";
                log.report("chain: /dev/df held with su dead; reboot required\n");
                nextRetryAt = SystemClock.elapsedRealtime() + 30L * 60L * 1000L;
                KeepService.scheduleRetry(appCtx, 30L * 60L * 1000L);
            }
            return;
        }

        int rc = -1;
        long attemptStart = SystemClock.elapsedRealtime();
        for (int attempt = 1; attempt <= MAX_EXPLOIT_ATTEMPTS; attempt++) {
            if (RootProbe.probe().live) {
                log.report("chain: su live before attempt " + attempt + "\n");
                break;
            }
            if (new File("/dev/df").exists()) {
                log.report("chain: mutex appeared, exploit would no-op\n");
                break;
            }
            PowerManager.WakeLock wake = acquire(appCtx, WAKE_ATTEMPT_MS);
            try {
                rc = Runner.run(deCtx, log, false);
            } catch (Throwable e) {
                log.report("chain: attempt " + attempt + " failed: " + e + "\n");
                rc = -1;
            } finally {
                release(wake);
            }
            log.report("chain: attempt " + attempt + " rc=" + rc + " "
                    + (SystemClock.elapsedRealtime() - attemptStart) + "ms\n");
            if (rc == 0 || RootProbe.probe().live || new File("/dev/df").exists()) {
                break;
            }
            if (attempt < MAX_EXPLOIT_ATTEMPTS) {
                PowerManager.WakeLock gap = acquire(appCtx, WAKE_GAP_MS);
                try {
                    Thread.sleep(ATTEMPT_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } finally {
                    release(gap);
                }
            }
        }
        log.report("chain: attempts " + (SystemClock.elapsedRealtime() - attemptStart) + "ms\n");

        if (RootProbe.probe().live) {
            markRooted(appCtx, deCtx, log, t0);
            return;
        }

        // rc == 0 means the chain confirmed success; rc == 2 with the mutex
        // means the watchdog missed the marker but stage 1 ran. Every other rc
        // is a diagnosed failure (module, mount, staging, ksud) and waiting for
        // su would only burn three minutes of polls.
        boolean patchPossible = rc == 0 || (rc == 2 && new File("/dev/df").exists());
        if (patchPossible) {
            if (rc != 0) {
                // rc == 0 already waited inside Runner.run; the mutex path did not.
                PowerManager.WakeLock wake = acquire(appCtx, WAKE_OVERLAY_MS);
                try {
                    Runner.applyOverlay(deCtx, log, OVERLAY_WAIT_MS);
                } finally {
                    release(wake);
                }
            }
            if (RootProbe.probe().live) {
                markRooted(appCtx, deCtx, log, t0);
            } else if (new File("/sys/module/kernelsu").isDirectory()) {
                // The module is in the kernel, so the primitive worked and this
                // uid is simply not allowlisted in the manager. Re-exploiting
                // cannot fix that; stop hammering the kernel for it.
                stageName = "no-allowlist";
                log.report("chain: KSU loaded but this uid is not allowlisted in the manager\n");
                nextRetryAt = SystemClock.elapsedRealtime() + 5L * 60L * 1000L;
                KeepService.scheduleRetry(appCtx, 5L * 60L * 1000L);
            } else {
                log.report("chain: kernel patched but su not live, arming watchdog\n");
                failPass(appCtx);
            }
        } else {
            log.report("chain: no kernel patch this pass, skipping overlay wait\n");
            failPass(appCtx);
        }
    }

    private static void markRooted(Context appCtx, Context deCtx, Buffer log, long t0) {
        RootRecord.markRooted(deCtx);
        stageName = "rooted";
        ladder = 0;
        gatePending = 0;
        stageFails = 0;
        KeepService.cancelRetry(appCtx);
        nextRetryAt = 0L;
        log.report("chain: rooted " + (SystemClock.elapsedRealtime() - t0) + "ms\n");
    }

    private static void failPass(Context appCtx) {
        int index = Math.min(ladder, LADDER.length - 1);
        long settleLeft = BOOT_SETTLE_MS
                - (SystemClock.elapsedRealtime() - firstPassUptime);
        long delay = settleLeft > 0 ? LADDER[index] : LADDER_CAP_MS;
        ladder++;
        stageFails++;
        // A patched firmware keeps the KMI strings and silently defeats the
        // module. After six failures in one boot, stop the 90 s hammer and drop
        // to one pass per half hour, so a dead build costs battery once in a
        // while instead of all night.
        if (stageFails >= 6) {
            delay = 30L * 60L * 1000L;
        }
        scheduleNext(appCtx, delay);
    }

    private static void scheduleNext(Context appCtx, long delayMs) {
        nextRetryAt = SystemClock.elapsedRealtime() + delayMs;
        KeepService.scheduleRetry(appCtx, delayMs);
    }

    // ------------------------------------------------------------------ gate

    private static String gateBlocker() {
        if (gateOpen) {
            return null;
        }
        if (!new File("/apex/com.android.runtime/bin/crash_dump64").exists()) {
            return "crash_dump64";
        }
        if (!new File("/vendor/lib64/libbinderdebug.so").exists()) {
            return "libbinderdebug";
        }
        String boot = Probes.prop("sys.boot_completed");
        if (!"1".equals(boot)) {
            return "boot_completed=" + boot;
        }
        return null;
    }

    // --------------------------------------------------------------- wakelock

    private static PowerManager.WakeLock acquire(Context ctx, long ms) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return null;
            }
            PowerManager.WakeLock wake = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "dfroot:chain");
            wake.setReferenceCounted(false);
            wake.acquire(ms);
            return wake;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void release(PowerManager.WakeLock wake) {
        if (wake != null) {
            try {
                wake.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // -------------------------------------------------------------- cooldown

    private static final String Intent_USER_PRESENT = "android.intent.action.USER_PRESENT";
    private static final String Intent_USER_UNLOCKED = "android.intent.action.USER_UNLOCKED";

    private static boolean unlockCooldownActive(Context deCtx) {
        try {
            File f = new File(deCtx.getFilesDir(), UNLOCK_ATTEMPT_FILE);
            if (!f.isFile()) {
                return false;
            }
            byte[] buf = new byte[32];
            FileInputStream in = new FileInputStream(f);
            int n;
            try {
                n = in.read(buf);
            } finally {
                in.close();
            }
            if (n <= 0) {
                return false;
            }
            long last = Long.parseLong(new String(buf, 0, n, "UTF-8").trim());
            return SystemClock.elapsedRealtime() - last < USER_UNLOCK_COOLDOWN_MS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void markUnlockAttempt(Context deCtx) {
        try {
            File f = new File(deCtx.getFilesDir(), UNLOCK_ATTEMPT_FILE);
            FileOutputStream out = new FileOutputStream(f, false);
            try {
                out.write(Long.toString(SystemClock.elapsedRealtime())
                        .getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    // ----------------------------------------------------------------- stage

    private static void loadStage(Context deCtx) {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(deCtx.getFilesDir(), STAGE_FILE)),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    map.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
        } catch (Throwable ignored) {
        }
        String current = BootId.current();
        if (!current.equals(map.getOrDefault("boot_id", ""))) {
            stageBootId = current;
            stageName = "";
            ladder = 0;
            gatePending = 0;
            gateOpen = false;
            stageFails = 0;
            firstPassUptime = SystemClock.elapsedRealtime();
        } else {
            stageBootId = current;
            stageName = map.getOrDefault("stage", "");
            ladder = parse(map.get("ladder"));
            gatePending = parse(map.get("pending"));
            gateOpen = "1".equals(map.get("gate_open"));
            stageFails = parse(map.get("fails"));
            firstPassUptime = parseLong(map.get("first"), -1L);
        }
    }

    private static void saveStage(Context deCtx) {
        try {
            File f = new File(deCtx.getFilesDir(), STAGE_FILE);
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp, false);
            try {
                out.write(("boot_id=" + stageBootId + "\n"
                        + "stage=" + stageName + "\n"
                        + "ladder=" + ladder + "\n"
                        + "pending=" + gatePending + "\n"
                        + "gate_open=" + (gateOpen ? "1" : "0") + "\n"
                        + "fails=" + stageFails + "\n"
                        + "first=" + firstPassUptime + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }


    private static int parse(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- buffer

    private static final class Buffer implements IReporter {
        private final StringBuilder sb = new StringBuilder();

        @Override
        public void report(String msg) {
            if (msg == null || msg.isEmpty()) {
                return;
            }
            Log.i(TAG, msg.trim());
            sb.append(msg);
            if (sb.length() > 262144) {
                sb.delete(0, sb.length() - 131072);
            }
        }

        void flush(Context appCtx, Context deCtx) {
            if (sb.length() == 0) {
                return;
            }
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
            append(new File(deCtx.getFilesDir(), "boot.log"), bytes);
            append(new File(appCtx.getFilesDir(), "run.log"), bytes);
        }

        private static void append(File file, byte[] bytes) {
            synchronized (Buffer.class) {
                LogFiles.append(file, bytes, LOG_CAP_BYTES, LOG_KEEP_BYTES);
            }
        }
    }
}
