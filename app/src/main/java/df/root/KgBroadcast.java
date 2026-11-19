package df.root;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Client-side cleanup after a REAL TA unlock. The decompiled
 * ServiceIntentReceiver handles MANUAL_UNLOCK by clearing its local lock
 * prefs, sending KNOXGUARD_UNLOCKED and reporting MANUAL_UNLOCK to Samsung;
 * it does not touch the TA. Sending it before the TA is unlocked makes the
 * client lie about its state, so Runner only calls this after a successful
 * unlockScreen (TA Completed / no longer Active-Locked).
 */
final class KgBroadcast {

    private static final String TAG = "dfroot";
    private static final String CLIENT = "com.samsung.android.kgclient";
    private static final String MANUAL_UNLOCK =
            "com.samsung.kgclient.android.intent.action.MANUAL_UNLOCK";

    private KgBroadcast() {
    }

    /** Returns a log line; never throws. */
    static String apply(Context ctx) {
        if (!KgWatchdog.enabled(ctx)) {
            return "[kg-broadcast] disabled by preference\n";
        }
        try {
            Intent intent = new Intent(MANUAL_UNLOCK).setPackage(CLIENT);
            ctx.sendBroadcast(intent);
            return "[kg-broadcast] unlock cleanup queued\n";
        } catch (Throwable t) {
            Log.w(TAG, "kg broadcast: " + t);
            return "[kg-broadcast] blocked: " + t + "\n";
        }
    }
}
