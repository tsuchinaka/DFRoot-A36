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

    /** Last TA result, so the UI can show the failure instead of the state name. */
    static final class Last {
        int pre = -1;
        int post = -1;
        int unlock = -1;
        int err = 0;
        long at;
        boolean parked;
        boolean known;
    }

    static Last emptyLast() {
        return new Last();
    }

    static void record(Context ctx, Runner.KgResult res) {
        try {
            ctx.getSharedPreferences("dfroot", Context.MODE_PRIVATE).edit()
                    .putInt("kg_last_pre", res.pre)
                    .putInt("kg_last_post", res.post)
                    .putInt("kg_last_unlock", res.unlock)
                    .putInt("kg_last_err", res.unlockErr)
                    .putString("kg_last_boot", BootId.current())
                    .putLong("kg_last_at", SystemClock.elapsedRealtime())
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    static Last last(Context ctx) {
        Last out = new Last();        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("dfroot", Context.MODE_PRIVATE);
            out.known = sp.contains("kg_last_at");
            out.pre = sp.getInt("kg_last_pre", -1);
            out.post = sp.getInt("kg_last_post", -1);
            out.unlock = sp.getInt("kg_last_unlock", -1);
            out.err = sp.getInt("kg_last_err", 0);
            out.at = sp.getLong("kg_last_at", 0L);
            out.parked = !BootId.current().isEmpty()
                    && BootId.current().equals(sp.getString("kg_blocked_boot", ""));
        } catch (Throwable ignored) {
        }
        return out;
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
        if (!enabled(ctx) || Runner.isClaimed(ctx)) {
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
