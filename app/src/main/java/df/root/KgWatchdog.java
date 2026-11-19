package df.root;

import android.content.Context;
import android.util.Log;

/**
 * Re-applies the Knox Guard unlock if the client puts the device back into an
 * enrolled lock state while root is still live. Reads the property without
 * su; the actual fix needs root and is rate-limited.
 */
final class KgWatchdog {

    private static final String TAG = "dfroot";
    private static final String PREF = "kg_enabled";
    private static final long COOLDOWN_MS = 600000L;

    private static volatile long lastFix;
    private static boolean forcedOnce;

    private KgWatchdog() {
    }

    static boolean enabled(Context ctx) {
        try {
            return ctx.getSharedPreferences("dfroot", Context.MODE_PRIVATE)
                    .getBoolean(PREF, true);
        } catch (Throwable t) {
            return true;
        }
    }

    static void setEnabled(Context ctx, boolean enabled) {
        try {
            ctx.getSharedPreferences("dfroot", Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF, enabled).apply();
        } catch (Throwable ignored) {
        }
        if (enabled) {
            forcedOnce = false;
        }
    }

    /** Called from the keep worker when the root side is healthy. */
    static void maybeFix(Context ctx) {
        if (!enabled(ctx) || Runner.isClaimed()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (!forcedOnce && lastFix == 0L) {
            // The first pass after a root drop is worth one immediate check.
            forcedOnce = true;
        } else if (now - lastFix < COOLDOWN_MS) {
            return;
        }
        int state = Probes.intProp("knox.kg.state", -1);
        if (state != 2 && state != 3) {
            return;
        }
        if (!RootProbe.probe().live) {
            return;
        }
        lastFix = now;
        Log.i(TAG, "kg: re-lock detected (state=" + state + "), re-applying unlock");
        Runner.applyKgOnly(ctx, msg -> Log.i(TAG, msg.trim()));
    }
}
