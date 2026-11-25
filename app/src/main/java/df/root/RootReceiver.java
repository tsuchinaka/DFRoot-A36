package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * ADB entry point for the root chain.
 *
 * The receiver is exported but requires android.permission.DUMP, which the
 * adb shell (uid 2000) holds; ordinary apps do not. It does not run the chain
 * itself: a broadcast thread can be killed mid-exploit and leave libc page
 * cache half-patched, so the pass is queued on the :chain service worker like
 * every other entry point.
 */
public class RootReceiver extends BroadcastReceiver {
    public static final String ACTION = "df.root.action.ROOT";
    private static final String TAG = "dfroot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        Context app = context.getApplicationContext();
        TargetGuard.Result target = TargetGuard.check(app);
        if (!target.ok) {
            Log.w(TAG, "adb root: target refused: " + target.detail);
            return;
        }
        KeepService.start(app);
        KeepService.requestChain(app, "adb");
        Log.i(TAG, "adb root: pass queued");
    }
}
