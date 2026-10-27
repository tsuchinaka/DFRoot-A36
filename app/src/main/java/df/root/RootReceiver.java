package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            try {
                if (!Runner.claim()) {
                    Log.i(TAG, "adb root: a run is already in flight");
                    return;
                }
                try {
                    Log.i(TAG, "adb root: start");
                    int rc = Runner.run(app, msg -> Log.i(TAG, msg.trim()), false);
                    Log.i(TAG, "adb root: rc=" + rc);
                } finally {
                    Runner.release();
                }
            } catch (Throwable t) {
                Log.e(TAG, "adb root: failed", t);
            } finally {
                pending.finish();
            }
        }, "dfroot-adb");
        thread.setDaemon(false);
        thread.start();
    }
}
