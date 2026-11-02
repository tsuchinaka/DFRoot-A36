package df.root;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Boot-time entry point. Runs from direct-boot storage so it works before the
 * user unlocks, and retries through the unlock phase plus an alarm watchdog
 * because a cold boot is the least reliable moment to win the race.
 *
 * Stage-aware: /dev/df is a one-shot mutex created by the payload. Once it
 * exists in this boot a re-exploit is a no-op, so later passes only wait for
 * su and re-apply the overlay instead of burning exploit attempts.
 *
 * Unlock coverage: USER_UNLOCKED fires once per reboot at credential unlock,
 * USER_PRESENT fires on every lockscreen dismissal after boot. Unlock passes
 * are throttled to one per cooldown window so repeated locking does not
 * hammer the chain; boot-phase passes and the alarm watchdog are not
 * throttled.
 */
public class BootReceiver extends BroadcastReceiver implements IReporter {

    static final String ACTION_BOOT_RETRY = "df.root.action.BOOT_RETRY";

    private static final String TAG = "dfroot";
    private static final int MAX_EXPLOIT_ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 30000;
    private static final long OVERLAY_WAIT_MS = 180000;
    private static final long ALARM_RETRY_DELAY_MS = 90000;
    private static final long WAKE_TIMEOUT_MS = 600000;
    private static final long UNLOCK_COOLDOWN_MS = 300000;
    private static final String UNLOCK_ATTEMPT_FILE = "unlock-attempt-at";
    private static final int ALARM_REQUEST_CODE = 1001;

    private File logFile;
    private Context appCtx;

    @Override
    public void report(String msg) {
        Log.i(TAG, msg.trim());
        if (logFile == null) {
            return;
        }
        try {
            FileOutputStream out = new FileOutputStream(logFile, true);
            try {
                out.write(msg.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (IOException ignored) {
        }
        if (appCtx != null) {
            try {
                File cred = new File(appCtx.getFilesDir(), "run.log");
                FileOutputStream out = new FileOutputStream(cred, true);
                try {
                    out.write(msg.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean isAutoEnabled(Context context) {
        try {
            int state = context.getPackageManager().getComponentEnabledSetting(
                    new ComponentName(context, BootReceiver.class));
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context deCtx = context.createDeviceProtectedStorageContext();
        appCtx = context.getApplicationContext();
        logFile = new File(deCtx.getFilesDir(), "boot.log");
        String action = intent == null ? "null" : String.valueOf(intent.getAction());

        if (!isAutoEnabled(context)) {
            Log.i(TAG, "boot: auto disabled, ignoring " + action);
            return;
        }

        if (!Runner.claim()) {
            Log.i(TAG, "boot: a run is already in flight, skipping " + action);
            return;
        }

        final PendingResult pending = goAsync();
        Thread t = new Thread(() -> {
            PowerManager.WakeLock wake = null;
            try {
                try {
                    PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:boot");
                        wake.setReferenceCounted(false);
                        wake.acquire(WAKE_TIMEOUT_MS);
                    }
                } catch (Throwable ignored) {
                }

                if (RootProbe.probe().live) {
                    report("boot: " + action + " su already live, overlay only\n");
                    Runner.applyOverlay(deCtx, this, OVERLAY_WAIT_MS);
                    RootRecord.markRooted(deCtx);
                    cancelRetry(appCtx);
                    return;
                }

                if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    if (unlockCooldownActive(deCtx)) {
                        report("boot: " + action + " inside cooldown, skipping\n");
                        return;
                    }
                    markUnlockAttempt(deCtx);
                }

                if (new File("/dev/df").exists()) {
                    report("boot: " + action + " mutex held, waiting for su instead of re-exploit\n");
                    Runner.applyOverlay(deCtx, this, OVERLAY_WAIT_MS);
                    if (RootProbe.probe().live) {
                        RootRecord.markRooted(deCtx);
                        cancelRetry(appCtx);
                    } else {
                        report("boot: su not live after overlay wait, arming watchdog\n");
                        scheduleRetry(appCtx, ALARM_RETRY_DELAY_MS);
                    }
                    return;
                }

                scheduleRetry(appCtx, ALARM_RETRY_DELAY_MS);
                report("boot: " + action + "\n");
                boolean done = false;
                for (int attempt = 1; attempt <= MAX_EXPLOIT_ATTEMPTS; attempt++) {
                    if (RootProbe.probe().live) {
                        report("boot: su live before attempt " + attempt + "\n");
                        done = true;
                        break;
                    }
                    if (new File("/dev/df").exists()) {
                        report("boot: mutex appeared, exploit would no-op, switching to overlay wait\n");
                        break;
                    }
                    try {
                        int rc = Runner.run(deCtx, this, false);
                        report("boot: attempt " + attempt + " rc=" + rc + "\n");
                        if (rc == 0 || RootProbe.probe().live) {
                            done = true;
                            break;
                        }
                        if (new File("/dev/df").exists()) {
                            report("boot: mutex held after attempt " + attempt
                                    + ", further exploit no-op\n");
                            break;
                        }
                    } catch (Throwable e) {
                        report("boot: attempt " + attempt + " failed: " + e + "\n");
                    }
                    if (!done && attempt < MAX_EXPLOIT_ATTEMPTS) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }

                if (RootProbe.probe().live) {
                    RootRecord.markRooted(deCtx);
                    cancelRetry(appCtx);
                    report("boot: rooted this boot\n");
                } else {
                    Runner.applyOverlay(deCtx, this, OVERLAY_WAIT_MS);
                    if (RootProbe.probe().live) {
                        RootRecord.markRooted(deCtx);
                        cancelRetry(appCtx);
                        report("boot: rooted after overlay wait\n");
                    } else {
                        report("boot: not live yet, watchdog stays armed\n");
                    }
                }
            } finally {
                if (wake != null) {
                    try {
                        wake.release();
                    } catch (Throwable ignored) {
                    }
                }
                Runner.release();
                pending.finish();
            }
        }, "dfroot-boot");
        t.setDaemon(false);
        t.start();
    }

    private static boolean unlockCooldownActive(Context deCtx) {
        try {
            File f = new File(deCtx.getFilesDir(), UNLOCK_ATTEMPT_FILE);
            if (!f.isFile()) {
                return false;
            }
            byte[] buf = new byte[32];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
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
            return SystemClock.elapsedRealtime() - last < UNLOCK_COOLDOWN_MS;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void markUnlockAttempt(Context deCtx) {
        try {
            File f = new File(deCtx.getFilesDir(), UNLOCK_ATTEMPT_FILE);
            FileOutputStream out = new FileOutputStream(f, false);
            try {
                out.write(Long.toString(SystemClock.elapsedRealtime()).getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void scheduleRetry(Context context, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent i = new Intent(context, BootReceiver.class).setAction(ACTION_BOOT_RETRY);
            PendingIntent pi = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (Throwable ignored) {
        }
    }

    private static void cancelRetry(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(context, BootReceiver.class).setAction(ACTION_BOOT_RETRY);
            PendingIntent pi = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            if (am != null) {
                am.cancel(pi);
            }
            pi.cancel();
        } catch (Throwable ignored) {
        }
    }
}
