package df.root;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

/**
 * Re-applies the Knox Guard unlock when the client reports Active/Locked while
 * root is live. Cooldown and RPMB failures are stored per boot in prefs, so a
 * process restart does not reset the backoff and a 513/519 stays parked until
 * the next reboot.
 */
final class KgWatchdog {

    private static final String TAG = "dfroot";
    private static final String PREF = "kg_enabled";
    private static final long COOLDOWN_MS = 600000L;

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
    }

    /** Called from the keep worker when the root side is healthy. */
    static void maybeFix(Context ctx) {
        if (!enabled(ctx) || Runner.isClaimed()) {
            return;
        }
        SharedPreferences sp = ctx.getSharedPreferences("dfroot", Context.MODE_PRIVATE);
        String boot = BootId.current();
        if (!boot.isEmpty() && boot.equals(sp.getString("kg_blocked_boot", ""))) {
            return;
        }
        int state = TextParse.kgState(Probes.prop("knox.kg.state"));
        if (state != 2 && state != 3) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long last = sp.getLong("kg_fix_at", 0);
        String lastBoot = sp.getString("kg_fix_boot", "");
        if (boot.equals(lastBoot) && last > 0 && now - last < COOLDOWN_MS) {
            return;
        }
        if (!RootProbe.probe().live) {
            return;
        }
        sp.edit().putString("kg_fix_boot", boot).putLong("kg_fix_at", now).apply();
        Log.i(TAG, "kg: re-lock detected (" + TextParse.kgStateName(state)
                + "), re-applying unlock");
        Runner.KgResult result = Runner.applyKgOnly(ctx, msg -> Log.i(TAG, msg.trim()));
        if (result.unlockErr == 513 || result.unlockErr == 519) {
            sp.edit().putString("kg_blocked_boot", boot).apply();
            Log.w(TAG, "kg: parked until reboot (" + result.unlockErr + ")");
        }
    }
}
