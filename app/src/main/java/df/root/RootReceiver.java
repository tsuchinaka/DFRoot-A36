package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

/**
 * ADB entry point for the root chain.
 *
 * The receiver is exported but requires android.permission.DUMP, which the
 * adb shell (uid 2000) holds; ordinary apps do not. It runs the same chain as
 * the UI, in a background thread, and logs to logcat under "dfroot".
 */
public class RootReceiver extends BroadcastReceiver {
    public static final String ACTION = "df.root.action.ROOT";
    private static final String TAG = "dfroot";
    private static final long WAKE_MS = 600000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            PowerManager.WakeLock wake = null;
            try {
                try {
                    PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dfroot:adb");
                        wake.setReferenceCounted(false);
                        wake.acquire(WAKE_MS);
                    }
                } catch (Throwable ignored) {
                }
                TargetGuard.Result target = TargetGuard.check(app);
                if (!target.ok) {
                    Log.w(TAG, "adb root: target refused: " + target.detail);
                    return;
                }
                if (!Runner.claim()) {
                    Log.i(TAG, "adb root: a run is already in flight");
                    return;
                }
                try {
                    KeepService.start(app);
                    Log.i(TAG, "adb root: start");
                    int rc = Runner.run(app, msg -> Log.i(TAG, msg.trim()), false);
                    Log.i(TAG, "adb root: rc=" + rc);
                } finally {
                    Runner.release();
                }
            } catch (Throwable t) {
                Log.e(TAG, "adb root: failed", t);
            } finally {
                if (wake != null) {
                    try {
                        wake.release();
                    } catch (Throwable ignored) {
                    }
                }
                pending.finish();
            }
        }, "dfroot-adb");
        thread.setDaemon(false);
        thread.start();
    }
}
